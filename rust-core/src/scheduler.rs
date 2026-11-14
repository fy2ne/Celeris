use crate::codec::FrameCodec;
use crate::socket::TcpStats;
use std::collections::VecDeque;
use std::time::Instant;

pub const LANE_EXPRESS: i32 = 0;
pub const LANE_BULK: i32 = 1;
pub const HIST_BUCKETS: usize = 80;
pub const MAX_PACKET_BYTES: usize = 8 * 1024 * 1024;
pub const MAX_EXPRESS_QUEUE_BYTES: usize = 2 * 1024 * 1024;
pub const MAX_BULK_QUEUE_BYTES: usize = 6 * 1024 * 1024;

const MIN_BUDGET: usize = 16 * 1024;
const BASE_BUDGET: usize = 32 * 1024;
const MAX_BUDGET: usize = 96 * 1024;
pub const SHRINK_CAP_BYTES: usize = 16 * 1024;

#[inline]
pub fn bucket_for_us(us: u64) -> usize {
    if us < 16 {
        return us as usize;
    }
    let clamped = us.min(u32::MAX as u64) as u32;
    let bit = 31 - clamped.leading_zeros() as usize;
    let sub = ((clamped >> (bit - 2)) & 0x3) as usize;
    (16 + ((bit - 4) << 2) + sub).min(HIST_BUCKETS - 1)
}

pub struct PlayerScheduler {
    pub codec: FrameCodec,
    express_buf: Vec<u8>,
    express_ts: VecDeque<(u64, Instant)>,
    express_stream_head: u64,
    express_stream_tail: u64,
    bulk_q: VecDeque<Vec<u8>>,
    bulk_ts: VecDeque<Instant>,
    bulk_bytes_queued: usize,
    slab_pool: Vec<Vec<u8>>,
    // if one huge bulk frame exceeds out_buf capacity, finish it before injecting express
    partial_frame: Option<(Vec<u8>, usize)>,

    min_srtt_us: u32,
    last_retrans: u32,
    pub express_packets: u64,
    pub bulk_packets: u64,
    pub preempted_packets: u64,
    pub bytes_raw: u64,
    pub bytes_wire: u64,
    pub express_delay_hist: [u64; HIST_BUCKETS],
    pub express_delay_sum_us: u64,
    pub express_delay_max_us: u64,
    pub express_delay_count: u64,
    pub bulk_hold_sum_us: u64,
    pub bulk_hold_max_us: u64,
    pub bulk_hold_count: u64,
    pub queue_overflows: u64,
}

impl PlayerScheduler {
    pub fn new(compression_threshold: i32, compression_level: u32) -> Self {
        Self {
            codec: FrameCodec::new(compression_threshold, compression_level),
            express_buf: Vec::with_capacity(8192),
            express_ts: VecDeque::with_capacity(64),
            express_stream_head: 0,
            express_stream_tail: 0,
            bulk_q: VecDeque::with_capacity(32),
            bulk_ts: VecDeque::with_capacity(32),
            bulk_bytes_queued: 0,
            slab_pool: Vec::with_capacity(16),
            partial_frame: None,
            min_srtt_us: u32::MAX,
            last_retrans: 0,
            express_packets: 0,
            bulk_packets: 0,
            preempted_packets: 0,
            bytes_raw: 0,
            bytes_wire: 0,
            express_delay_hist: [0; HIST_BUCKETS],
            express_delay_sum_us: 0,
            express_delay_max_us: 0,
            express_delay_count: 0,
            bulk_hold_sum_us: 0,
            bulk_hold_max_us: 0,
            bulk_hold_count: 0,
            queue_overflows: 0,
        }
    }

    pub fn enqueue(&mut self, raw_packet: &[u8], lane: i32) -> Result<bool, i32> {
        if raw_packet.len() > MAX_PACKET_BYTES {
            return Err(-6);
        }
        let now = Instant::now();

        if lane == LANE_EXPRESS {
            if self.express_buf.len().saturating_add(raw_packet.len()).saturating_add(16) > MAX_EXPRESS_QUEUE_BYTES {
                self.queue_overflows += 1;
                return Err(-7);
            }
            let framed = self.codec.frame_packet(raw_packet, &mut self.express_buf)?;
            self.bytes_raw += raw_packet.len() as u64;
            self.express_packets += 1;
            if self.bulk_bytes_queued > 0 || self.partial_frame.is_some() {
                self.preempted_packets += 1;
            }
            self.express_stream_tail += framed as u64;
            self.express_ts.push_back((self.express_stream_tail, now));
        } else {
            let partial_rem = self.partial_frame.as_ref().map_or(0, |(f, o)| f.len().saturating_sub(*o));
            if self.bulk_bytes_queued.saturating_add(partial_rem).saturating_add(raw_packet.len()) > MAX_BULK_QUEUE_BYTES {
                self.queue_overflows += 1;
                return Err(-7);
            }
            let mut slab = self.slab_pool.pop().unwrap_or_else(|| Vec::with_capacity(16384));
            slab.clear();
            if let Err(rc) = self.codec.frame_packet(raw_packet, &mut slab) {
                self.recycle_slab(slab);
                return Err(rc);
            }
            self.bytes_raw += raw_packet.len() as u64;
            self.bulk_packets += 1;
            self.bulk_bytes_queued += slab.len();
            self.bulk_q.push_back(slab);
            self.bulk_ts.push_back(now);
        }

        Ok(!self.bulk_q.is_empty() || self.partial_frame.is_some())
    }

    pub fn has_pending(&self) -> bool {
        !self.express_buf.is_empty() || !self.bulk_q.is_empty() || self.partial_frame.is_some()
    }

    pub fn has_bulk_pending(&self) -> bool {
        !self.bulk_q.is_empty() || self.partial_frame.is_some()
    }

    pub fn compute_bulk_budget(&mut self, tcp: Option<TcpStats>, override_max: i32) -> usize {
        self.compute_pacing(tcp, override_max).0
    }

    pub fn compute_pacing(&mut self, tcp: Option<TcpStats>, override_max: i32) -> (usize, u8) {
        let mut loss_spike = false;
        let mut pace_ms = 8u8;
        let mut auto_budget = BASE_BUDGET;

        if let Some(st) = tcp {
            if st.retrans > self.last_retrans {
                loss_spike = true;
            }
            self.last_retrans = st.retrans;

            if st.srtt_us > 0 && st.srtt_us < self.min_srtt_us {
                self.min_srtt_us = st.srtt_us;
            }
            let baseline = if self.min_srtt_us == u32::MAX { st.srtt_us } else { self.min_srtt_us };
            let bloat_us = st.srtt_us.saturating_sub(baseline).saturating_add(st.rttvar_us);

            if loss_spike || bloat_us > 35_000 || (st.snd_cwnd > 0 && st.unacked >= st.snd_cwnd) {
                auto_budget = MIN_BUDGET;
                pace_ms = 25;
            } else if bloat_us < 10_000 && st.unacked <= st.snd_cwnd / 2 {
                auto_budget = MAX_BUDGET;
                pace_ms = if st.srtt_us > 0 && st.srtt_us < 15_000 { 4 } else { 8 };
            }
        }

        if override_max < 0 {
            return (0, pace_ms);
        }
        if override_max > 0 {
            return (override_max as usize, pace_ms);
        }
        (auto_budget, pace_ms)
    }

    /// drains express packets ahead of bulk chunks, preserving partial-frame atomicity.
    /// returns (bytes_written, next_pace_ms_or_zero).
    pub fn drain_wire(&mut self, out: &mut [u8], tcp: Option<TcpStats>, max_bulk_override: i32) -> (usize, u8) {
        let (budget, pace_ms) = self.compute_pacing(tcp, max_bulk_override);
        let cap = out.len();
        if cap == 0 {
            let pending_pace = if self.has_pending() { pace_ms } else { 0 };
            return (0, pending_pace);
        }

        let now = Instant::now();
        let mut cursor = 0usize;

        // 1. if a single oversized frame was mid-stream on wire, finish it before anything else
        if let Some((frame, off)) = self.partial_frame.take() {
            let rem = frame.len() - off;
            let take = rem.min(cap - cursor);
            out[cursor..cursor + take].copy_from_slice(&frame[off..off + take]);
            cursor += take;
            if off + take < frame.len() {
                self.partial_frame = Some((frame, off + take));
                self.codec.encrypt_wire_inplace(&mut out[..cursor]);
                self.bytes_wire += cursor as u64;
                return (cursor, pace_ms);
            }
            self.recycle_slab(frame);
        }

        // 2. express lane always drains ahead of queued bulk chunks
        if !self.express_buf.is_empty() && cursor < cap {
            let take = self.express_buf.len().min(cap - cursor);
            out[cursor..cursor + take].copy_from_slice(&self.express_buf[..take]);
            cursor += take;
            self.express_stream_head += take as u64;
            while let Some(&(end_pos, _)) = self.express_ts.front() {
                if end_pos > self.express_stream_head {
                    break;
                }
                if let Some((_, t0)) = self.express_ts.pop_front() {
                    let us = now.saturating_duration_since(t0).as_micros() as u64;
                    self.express_delay_hist[bucket_for_us(us)] += 1;
                    self.express_delay_sum_us = self.express_delay_sum_us.saturating_add(us);
                    if us > self.express_delay_max_us {
                        self.express_delay_max_us = us;
                    }
                    self.express_delay_count += 1;
                }
            }
            if take == self.express_buf.len() {
                self.express_buf.clear();
                if self.express_buf.capacity() > SHRINK_CAP_BYTES {
                    self.express_buf = Vec::with_capacity(SHRINK_CAP_BYTES);
                }
                if self.express_ts.is_empty() && self.express_ts.capacity() > 256 {
                    self.express_ts = VecDeque::with_capacity(64);
                }
            } else {
                self.express_buf.drain(..take);
            }
        }

        // 3. drain bulk frames only if express_buf is completely empty and budget allows (budget == 0 pauses bulk on backpressure)
        if budget > 0 && self.express_buf.is_empty() && cursor < cap && !self.bulk_q.is_empty() {
            let mut bulk_emitted = 0usize;

            while let Some(front) = self.bulk_q.front() {
                let space = cap - cursor;
                if space == 0 {
                    break;
                }
                // allow at least 1 frame even if > budget so forward progress never deadlocks
                if bulk_emitted > 0 && (bulk_emitted + front.len() > budget || front.len() > space) {
                    break;
                }

                let Some(frame) = self.bulk_q.pop_front() else {
                    break;
                };
                if let Some(t0) = self.bulk_ts.pop_front() {
                    let us = now.saturating_duration_since(t0).as_micros() as u64;
                    self.bulk_hold_sum_us = self.bulk_hold_sum_us.saturating_add(us);
                    if us > self.bulk_hold_max_us {
                        self.bulk_hold_max_us = us;
                    }
                    self.bulk_hold_count += 1;
                }
                self.bulk_bytes_queued = self.bulk_bytes_queued.saturating_sub(frame.len());

                if frame.len() <= space {
                    out[cursor..cursor + frame.len()].copy_from_slice(&frame);
                    cursor += frame.len();
                    bulk_emitted += frame.len();
                    self.recycle_slab(frame);
                } else {
                    out[cursor..cursor + space].copy_from_slice(&frame[..space]);
                    cursor += space;
                    self.partial_frame = Some((frame, space));
                    break;
                }
            }
        }

        if self.bulk_q.is_empty() && self.partial_frame.is_none() {
            if self.bulk_ts.capacity() > 256 {
                self.bulk_ts = VecDeque::with_capacity(32);
            }
            if self.slab_pool.len() > 4 {
                self.slab_pool.truncate(4);
            }
        }

        if cursor > 0 {
            self.codec.encrypt_wire_inplace(&mut out[..cursor]);
            self.bytes_wire += cursor as u64;
        }

        let next_pace = if self.has_pending() { pace_ms } else { 0 };
        (cursor, next_pace)
    }

    fn recycle_slab(&mut self, mut slab: Vec<u8>) {
        if self.slab_pool.len() < 16 && slab.capacity() <= 64 * 1024 {
            slab.clear();
            self.slab_pool.push(slab);
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn decode_all_frames(mut wire: &[u8]) -> Vec<Vec<u8>> {
        let mut out = Vec::new();
        while !wire.is_empty() {
            let mut len = 0usize;
            let mut shift = 0;
            let mut hdr = 0;
            for (i, &b) in wire.iter().enumerate() {
                len |= ((b & 0x7f) as usize) << shift;
                if (b & 0x80) == 0 {
                    hdr = i + 1;
                    break;
                }
                shift += 7;
            }
            out.push(wire[hdr..hdr + len].to_vec());
            wire = &wire[hdr + len..];
        }
        out
    }

    #[test]
    fn express_preempts_400kb_chunk_burst() {
        let mut sched = PlayerScheduler::new(-1, 6);

        // simulate server dumping 20 x 20KB chunk packets (400 KB)
        for i in 0..20u8 {
            let mut chunk = vec![0x55u8; 20 * 1024];
            chunk[0] = i;
            sched.enqueue(&chunk, LANE_BULK).unwrap();
        }

        // keepalive + combat velocity arrive right behind the chunk flood
        let keepalive = b"\x26KEEPALIVE";
        let velocity = b"\x5AVELOCITY!";
        sched.enqueue(keepalive, LANE_EXPRESS).unwrap();
        sched.enqueue(velocity, LANE_EXPRESS).unwrap();

        assert_eq!(sched.preempted_packets, 2);

        let mut wire_buf = vec![0u8; 48 * 1024];
        let (written, pace_ms) = sched.drain_wire(&mut wire_buf, None, 32 * 1024);
        assert!(pace_ms > 0);

        let frames = decode_all_frames(&wire_buf[..written]);
        assert_eq!(frames[0], keepalive);
        assert_eq!(frames[1], velocity);
        assert_eq!(frames[2][0], 0); // first chunk follows express packets
    }

    #[test]
    fn oversized_partial_frame_finishes_before_express_cuts_in() {
        let mut sched = PlayerScheduler::new(-1, 6);
        let big_bulk = vec![0x77u8; 100];
        sched.enqueue(&big_bulk, LANE_BULK).unwrap();

        let mut small_buf = [0u8; 60];
        let (n1, more1) = sched.drain_wire(&mut small_buf, None, 0);
        assert_eq!(n1, 60);
        assert!(more1 > 0);

        // express arrives while big_bulk has 41 bytes left mid-frame on wire
        let ping = b"\x26PING";
        sched.enqueue(ping, LANE_EXPRESS).unwrap();

        let mut rest_buf = [0u8; 128];
        let (n2, more2) = sched.drain_wire(&mut rest_buf, None, 0);
        assert_eq!(more2, 0);

        let mut combined = Vec::new();
        combined.extend_from_slice(&small_buf[..n1]);
        combined.extend_from_slice(&rest_buf[..n2]);

        let frames = decode_all_frames(&combined);
        assert_eq!(frames.len(), 2);
        assert_eq!(frames[0], big_bulk);
        assert_eq!(frames[1], ping);
    }

    #[test]
    fn tcp_info_jitter_throttles_bulk_budget() {
        let mut sched = PlayerScheduler::new(-1, 6);
        let clean = TcpStats { srtt_us: 80_000, rttvar_us: 2_000, unacked: 4, retrans: 0, snd_cwnd: 20 };
        assert_eq!(sched.compute_bulk_budget(Some(clean), 0), MAX_BUDGET);

        let bloated = TcpStats { srtt_us: 135_000, rttvar_us: 15_000, unacked: 18, retrans: 0, snd_cwnd: 20 };
        assert_eq!(sched.compute_bulk_budget(Some(bloated), 0), MIN_BUDGET);
    }

    #[test]
    fn retransmission_spike_cuts_budget_and_widens_pace() {
        let mut sched = PlayerScheduler::new(-1, 6);
        let lan = TcpStats { srtt_us: 5_000, rttvar_us: 500, unacked: 1, retrans: 0, snd_cwnd: 20 };
        assert_eq!(sched.compute_pacing(Some(lan), 0), (MAX_BUDGET, 4));

        // sudden packet loss must throttle even before srtt spikes
        let lossy = TcpStats { srtt_us: 5_000, rttvar_us: 500, unacked: 1, retrans: 1, snd_cwnd: 20 };
        assert_eq!(sched.compute_pacing(Some(lossy), 0), (MIN_BUDGET, 25));
    }

    #[test]
    fn negative_override_drains_express_only_holding_bulk() {
        let mut sched = PlayerScheduler::new(-1, 6);
        sched.enqueue(&vec![0x11u8; 4096], LANE_BULK).unwrap();
        sched.enqueue(b"\x26KEEPALIVE", LANE_EXPRESS).unwrap();

        let mut buf = vec![0u8; 64 * 1024];
        let (n1, pace1) = sched.drain_wire(&mut buf, None, -1);
        assert!(pace1 > 0);
        let f1 = decode_all_frames(&buf[..n1]);
        assert_eq!(f1.len(), 1);
        assert_eq!(f1[0], b"\x26KEEPALIVE");
        assert!(sched.has_bulk_pending());

        let (n2, pace2) = sched.drain_wire(&mut buf, None, 0);
        assert_eq!(pace2, 0);
        let f2 = decode_all_frames(&buf[..n2]);
        assert_eq!(f2.len(), 1);
        assert_eq!(f2[0].len(), 4096);
    }

    #[test]
    fn telemetry_tracks_express_delay_and_bulk_hold() {
        let mut sched = PlayerScheduler::new(64, 6);
        sched.enqueue(&vec![0x33u8; 8192], LANE_BULK).unwrap();
        std::thread::sleep(std::time::Duration::from_millis(2));
        sched.enqueue(b"\x26KA", LANE_EXPRESS).unwrap();

        let mut buf = vec![0u8; 64 * 1024];
        let (n, pace) = sched.drain_wire(&mut buf, None, 0);
        assert!(n > 0);
        assert_eq!(pace, 0);
        assert_eq!(sched.express_delay_count, 1);
        assert_eq!(sched.bulk_hold_count, 1);
        assert!(sched.bulk_hold_max_us >= 1500);
        assert_eq!(sched.express_delay_hist.iter().sum::<u64>(), 1);
        assert_eq!(sched.preempted_packets, 1);
    }

    #[test]
    fn enforces_queue_bounds_and_shrinks_buffers_after_drain() {
        let mut sched = PlayerScheduler::new(-1, 6);
        let huge = vec![0xAAu8; MAX_PACKET_BYTES + 1];
        assert_eq!(sched.enqueue(&huge, LANE_BULK), Err(-6));

        let exp_chunk = vec![0xBBu8; 512 * 1024];
        for _ in 0..3 {
            sched.enqueue(&exp_chunk, LANE_EXPRESS).unwrap();
        }
        assert_eq!(sched.enqueue(&exp_chunk, LANE_EXPRESS), Err(-7));
        assert_eq!(sched.queue_overflows, 1);

        let mut out = vec![0u8; 2 * 1024 * 1024];
        let (_, pace) = sched.drain_wire(&mut out, None, 0);
        assert_eq!(pace, 0);
        assert!(sched.express_buf.capacity() <= SHRINK_CAP_BYTES);

        let bulk_chunk = vec![0xCCu8; 1024 * 1024];
        for _ in 0..5 {
            sched.enqueue(&bulk_chunk, LANE_BULK).unwrap();
        }
        assert_eq!(sched.enqueue(&bulk_chunk, LANE_BULK), Err(-7));
        assert_eq!(sched.queue_overflows, 2);
    }
}
