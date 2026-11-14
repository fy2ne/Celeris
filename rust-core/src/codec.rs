use aes::Aes128;
use cfb8::cipher::{consts::U1, generic_array::GenericArray, BlockEncryptMut, KeyIvInit};
use flate2::{Compress, Compression, FlushCompress, Status};

type Aes128Cfb8Enc = cfb8::Encryptor<Aes128>;

pub struct FrameCodec {
    threshold: i32,
    level: u32,
    compressor: Compress,
    scratch: Vec<u8>,
    cipher: Option<Aes128Cfb8Enc>,
}

impl FrameCodec {
    pub fn new(threshold: i32, level: u32) -> Self {
        let clamped = level.min(9);
        Self {
            threshold,
            level: clamped,
            compressor: Compress::new(Compression::new(clamped), true),
            scratch: Vec::with_capacity(16384),
            cipher: None,
        }
    }

    pub fn set_compression(&mut self, threshold: i32, level: u32) {
        self.threshold = threshold;
        let clamped = level.min(9);
        if clamped != self.level {
            self.level = clamped;
            self.compressor = Compress::new(Compression::new(clamped), true);
        }
    }

    pub fn enable_encryption(&mut self, key_iv: &[u8; 16]) -> Result<(), i32> {
        let enc = Aes128Cfb8Enc::new_from_slices(key_iv, key_iv).map_err(|_| -4)?;
        self.cipher = Some(enc);
        Ok(())
    }

    /// frames and optionally compresses one raw packet (stateless, safe before lane reordering).
    pub fn frame_packet(&mut self, raw: &[u8], out: &mut Vec<u8>) -> Result<usize, i32> {
        let before = out.len();
        if self.threshold < 0 {
            write_varint(out, raw.len() as u32);
            out.extend_from_slice(raw);
            return Ok(out.len() - before);
        }

        let thresh = self.threshold as usize;
        if raw.len() < thresh {
            let inner_len = 1 + raw.len();
            write_varint(out, inner_len as u32);
            out.push(0x00);
            out.extend_from_slice(raw);
            return Ok(out.len() - before);
        }

        self.compress_payload(raw)?;

        let uncomp_len = raw.len() as u32;
        let inner_len = varint_len(uncomp_len) + self.scratch.len();
        write_varint(out, inner_len as u32);
        write_varint(out, uncomp_len);
        out.extend_from_slice(&self.scratch);
        if self.scratch.capacity() > 16 * 1024 {
            self.scratch = Vec::with_capacity(16 * 1024);
        }
        Ok(out.len() - before)
    }

    fn compress_payload(&mut self, raw: &[u8]) -> Result<(), i32> {
        self.scratch.clear();
        let bound = raw.len().saturating_add(raw.len() >> 3).saturating_add(64);
        if self.scratch.capacity() < bound {
            self.scratch.try_reserve(bound - self.scratch.capacity()).map_err(|_| -8)?;
        }
        self.compressor.reset();
        loop {
            let consumed = self.compressor.total_in() as usize;
            if self.scratch.len() == self.scratch.capacity() {
                self.scratch.try_reserve(4096).map_err(|_| -8)?;
            }
            match self.compressor.compress_vec(&raw[consumed..], &mut self.scratch, FlushCompress::Finish) {
                Ok(Status::StreamEnd) => return Ok(()),
                Ok(Status::Ok | Status::BufError) => {
                    self.scratch.try_reserve(4096).map_err(|_| -8)?;
                }
                Err(_) => return Err(-8),
            }
        }
    }

    /// cfb8 is stream-stateful, so encryption must happen strictly in final wire order.
    pub fn encrypt_wire_inplace(&mut self, wire_bytes: &mut [u8]) {
        if let Some(ref mut enc) = self.cipher {
            debug_assert_eq!(std::mem::size_of::<GenericArray<u8, U1>>(), 1);
            let blocks = unsafe {
                std::slice::from_raw_parts_mut(
                    wire_bytes.as_mut_ptr().cast::<GenericArray<u8, U1>>(),
                    wire_bytes.len(),
                )
            };
            enc.encrypt_blocks_mut(blocks);
        }
    }
}

#[inline]
pub fn varint_len(mut n: u32) -> usize {
    let mut len = 1;
    while n >= 0x80 {
        n >>= 7;
        len += 1;
    }
    len
}

#[inline]
pub fn write_varint(out: &mut Vec<u8>, mut n: u32) {
    loop {
        if (n & !0x7f) == 0 {
            out.push(n as u8);
            return;
        }
        out.push(((n & 0x7f) | 0x80) as u8);
        n >>= 7;
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use cfb8::cipher::AsyncStreamCipher;
    use cfb8::Decryptor;
    use flate2::read::ZlibDecoder;
    use std::io::Read;

    fn read_varint(buf: &[u8]) -> (u32, usize) {
        let mut acc = 0u32;
        let mut shift = 0;
        for (idx, &b) in buf.iter().enumerate() {
            acc |= ((b & 0x7f) as u32) << shift;
            if (b & 0x80) == 0 {
                return (acc, idx + 1);
            }
            shift += 7;
        }
        panic!("unterminated varint");
    }

    #[test]
    fn uncompressed_framing_exact() {
        let mut codec = FrameCodec::new(-1, 6);
        let mut out = Vec::new();
        let pkt = b"\x00\x01\x02hello";
        codec.frame_packet(pkt, &mut out).unwrap();
        assert_eq!(out[0], pkt.len() as u8);
        assert_eq!(&out[1..], pkt);
    }

    #[test]
    fn below_threshold_writes_zero_data_length() {
        let mut codec = FrameCodec::new(256, 6);
        let mut out = Vec::new();
        let pkt = vec![0x26, 0, 1, 2, 3, 4, 5, 6, 7]; // e.g. KeepAlive 9 bytes
        codec.frame_packet(&pkt, &mut out).unwrap();
        let (outer_len, o_sz) = read_varint(&out);
        assert_eq!(outer_len as usize, 1 + pkt.len());
        assert_eq!(out[o_sz], 0x00);
        assert_eq!(&out[o_sz + 1..], pkt.as_slice());
    }

    #[test]
    fn above_threshold_compresses_and_roundtrips() {
        let mut codec = FrameCodec::new(64, 6);
        let mut out = Vec::new();
        let pkt = vec![0x27u8; 512];
        codec.frame_packet(&pkt, &mut out).unwrap();

        let (outer_len, o_sz) = read_varint(&out);
        let inner = &out[o_sz..o_sz + outer_len as usize];
        let (uncomp_len, i_sz) = read_varint(inner);
        assert_eq!(uncomp_len as usize, pkt.len());

        let mut dec = ZlibDecoder::new(&inner[i_sz..]);
        let mut restored = Vec::new();
        dec.read_to_end(&mut restored).unwrap();
        assert_eq!(restored, pkt);
        assert!(out.len() < pkt.len());
    }

    #[test]
    fn cfb8_reorder_safe_when_encrypted_at_drain() {
        let secret = [0x42u8; 16];
        let mut codec = FrameCodec::new(256, 6);
        codec.enable_encryption(&secret).unwrap();

        // frame bulk first, then express, then emit express first on wire
        let mut bulk_frame = Vec::new();
        let mut express_frame = Vec::new();
        let bulk_raw = vec![0xABu8; 300];
        let express_raw = vec![0x26u8, 1, 2, 3, 4, 5, 6, 7, 8];
        codec.frame_packet(&bulk_raw, &mut bulk_frame).unwrap();
        codec.frame_packet(&express_raw, &mut express_frame).unwrap();

        let mut wire1 = express_frame.clone();
        let mut wire2 = bulk_frame.clone();
        codec.encrypt_wire_inplace(&mut wire1);
        codec.encrypt_wire_inplace(&mut wire2);

        let mut combined_wire = Vec::with_capacity(wire1.len() + wire2.len());
        combined_wire.extend_from_slice(&wire1);
        combined_wire.extend_from_slice(&wire2);

        let dec = Decryptor::<Aes128>::new_from_slices(&secret, &secret).unwrap();
        let mut plain = combined_wire;
        dec.decrypt(&mut plain);

        assert_eq!(&plain[..express_frame.len()], express_frame.as_slice());
        assert_eq!(&plain[express_frame.len()..], bulk_frame.as_slice());
    }

    #[test]
    fn reusable_compressor_resets_cleanly_across_varied_payloads() {
        let mut codec = FrameCodec::new(64, 6);
        let mut out = Vec::new();
        let payloads: Vec<Vec<u8>> = vec![
            (0..256).map(|i| (i % 7) as u8).collect(),
            (0..24_000).map(|i| ((i * 31) ^ (i >> 3)) as u8).collect(),
            vec![0x5Au8; 2048],
        ];

        for (idx, pkt) in payloads.iter().enumerate() {
            if idx == 2 {
                codec.set_compression(64, 1);
            }
            out.clear();
            codec.frame_packet(pkt, &mut out).unwrap();

            let (outer_len, o_sz) = read_varint(&out);
            let inner = &out[o_sz..o_sz + outer_len as usize];
            let (uncomp_len, i_sz) = read_varint(inner);
            assert_eq!(uncomp_len as usize, pkt.len());

            let mut dec = ZlibDecoder::new(&inner[i_sz..]);
            let mut restored = Vec::new();
            dec.read_to_end(&mut restored).unwrap();
            assert_eq!(&restored, pkt);
        }
    }
}
