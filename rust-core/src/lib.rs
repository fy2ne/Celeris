pub mod codec;
pub mod scheduler;
pub mod socket;

use jni::objects::{JByteArray, JByteBuffer, JClass, JLongArray};
use jni::sys::{jint, jlong};
use jni::JNIEnv;
use scheduler::PlayerScheduler;
use socket::{poll_tcp_stats, refresh_quickack, tune_socket, update_lowat, CAP_QUICKACK};

pub struct NativeCtx {
    pub fd: i64,
    pub caps: u32,
    pub drain_ticks: u32,
    pub sched: PlayerScheduler,
    pub heap_scratch: Vec<u8>,
}

impl NativeCtx {
    pub fn new(threshold: i32, level: u32, fd: i64, lowat_bytes: u32) -> Self {
        let caps = tune_socket(fd, lowat_bytes);
        Self {
            fd,
            caps,
            drain_ticks: 0,
            sched: PlayerScheduler::new(threshold, level),
            heap_scratch: Vec::with_capacity(4096),
        }
    }
}

#[inline]
unsafe fn borrow_ctx<'a>(ptr: jlong) -> Option<&'a mut NativeCtx> {
    if ptr == 0 {
        None
    } else {
        Some(&mut *(ptr as *mut NativeCtx))
    }
}

#[no_mangle]
pub extern "system" fn Java_dev_fy2ne_celeris_nativebridge_NativeBridge_createContext(
    _env: JNIEnv,
    _cls: JClass,
    compression_threshold: jint,
    compression_level: jint,
    socket_fd: jlong,
    lowat_bytes: jint,
) -> jlong {
    let lowat = if lowat_bytes > 0 { lowat_bytes as u32 } else { 16384 };
    let level = compression_level.clamp(0, 9) as u32;
    let ctx = Box::new(NativeCtx::new(compression_threshold, level, socket_fd, lowat));
    Box::into_raw(ctx) as jlong
}

#[no_mangle]
pub extern "system" fn Java_dev_fy2ne_celeris_nativebridge_NativeBridge_destroyContext(
    _env: JNIEnv,
    _cls: JClass,
    ctx_ptr: jlong,
) {
    if ctx_ptr != 0 {
        unsafe {
            drop(Box::from_raw(ctx_ptr as *mut NativeCtx));
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_dev_fy2ne_celeris_nativebridge_NativeBridge_setCompression(
    _env: JNIEnv,
    _cls: JClass,
    ctx_ptr: jlong,
    threshold: jint,
    level: jint,
) {
    if let Some(ctx) = unsafe { borrow_ctx(ctx_ptr) } {
        ctx.sched.codec.set_compression(threshold, level.clamp(0, 9) as u32);
    }
}

#[no_mangle]
pub extern "system" fn Java_dev_fy2ne_celeris_nativebridge_NativeBridge_enableEncryption(
    env: JNIEnv,
    _cls: JClass,
    ctx_ptr: jlong,
    secret_key: JByteArray,
) -> jint {
    let Some(ctx) = (unsafe { borrow_ctx(ctx_ptr) }) else {
        return -1;
    };
    let mut key_buf = [0i8; 16];
    let len = env.get_array_length(&secret_key).unwrap_or(0);
    if len != 16 {
        return -2;
    }
    if env.get_byte_array_region(&secret_key, 0, &mut key_buf).is_err() {
        return -3;
    }
    let raw_key: [u8; 16] = key_buf.map(|b| b as u8);
    if ctx.sched.codec.enable_encryption(&raw_key).is_err() {
        return -4;
    }
    0
}

#[no_mangle]
pub extern "system" fn Java_dev_fy2ne_celeris_nativebridge_NativeBridge_enqueueDirectPacket(
    env: JNIEnv,
    _cls: JClass,
    ctx_ptr: jlong,
    direct_buf: JByteBuffer,
    offset: jint,
    len: jint,
    lane: jint,
) -> jint {
    let Some(ctx) = (unsafe { borrow_ctx(ctx_ptr) }) else {
        return -1;
    };
    if offset < 0 || len <= 0 {
        return -2;
    }
    let pkt_len = len as usize;
    if pkt_len > scheduler::MAX_PACKET_BYTES {
        return -6;
    }
    let Ok(base) = env.get_direct_buffer_address(&direct_buf) else {
        return -3;
    };
    let Ok(cap) = env.get_direct_buffer_capacity(&direct_buf) else {
        return -4;
    };
    let off = offset as usize;
    if off.saturating_add(pkt_len) > cap {
        return -5;
    }
    let slice = unsafe { std::slice::from_raw_parts(base.add(off), pkt_len) };
    match ctx.sched.enqueue(slice, lane) {
        Ok(true) => 1,
        Ok(false) => 0,
        Err(code) => code,
    }
}

#[no_mangle]
pub extern "system" fn Java_dev_fy2ne_celeris_nativebridge_NativeBridge_enqueueHeapPacket(
    env: JNIEnv,
    _cls: JClass,
    ctx_ptr: jlong,
    bytes: JByteArray,
    offset: jint,
    len: jint,
    lane: jint,
) -> jint {
    let Some(ctx) = (unsafe { borrow_ctx(ctx_ptr) }) else {
        return -1;
    };
    if offset < 0 || len <= 0 {
        return -2;
    }
    let pkt_len = len as usize;
    if pkt_len > scheduler::MAX_PACKET_BYTES {
        return -6;
    }
    ctx.heap_scratch.clear();
    if ctx.heap_scratch.try_reserve(pkt_len).is_err() {
        return -8;
    }
    ctx.heap_scratch.resize(pkt_len, 0);
    let dst_i8 = unsafe {
        std::slice::from_raw_parts_mut(ctx.heap_scratch.as_mut_ptr().cast::<i8>(), pkt_len)
    };
    if env.get_byte_array_region(&bytes, offset, dst_i8).is_err() {
        return -3;
    }
    let res = ctx.sched.enqueue(&ctx.heap_scratch[..pkt_len], lane);
    if ctx.heap_scratch.capacity() > scheduler::SHRINK_CAP_BYTES {
        ctx.heap_scratch = Vec::with_capacity(scheduler::SHRINK_CAP_BYTES);
    }
    match res {
        Ok(true) => 1,
        Ok(false) => 0,
        Err(code) => code,
    }
}

#[no_mangle]
pub extern "system" fn Java_dev_fy2ne_celeris_nativebridge_NativeBridge_drainWire(
    env: JNIEnv,
    _cls: JClass,
    ctx_ptr: jlong,
    out_direct_buf: JByteBuffer,
    capacity: jint,
    max_bulk_override: jint,
) -> jlong {
    let Some(ctx) = (unsafe { borrow_ctx(ctx_ptr) }) else {
        return -1;
    };
    if capacity <= 0 {
        return 0;
    }
    let Ok(base) = env.get_direct_buffer_address(&out_direct_buf) else {
        return -2;
    };
    let Ok(cap) = env.get_direct_buffer_capacity(&out_direct_buf) else {
        return -3;
    };
    let usable = (capacity as usize).min(cap);
    let out_slice = unsafe { std::slice::from_raw_parts_mut(base, usable) };

    ctx.drain_ticks = ctx.drain_ticks.wrapping_add(1);
    if (ctx.caps & CAP_QUICKACK) != 0 && (ctx.drain_ticks & 7) == 0 {
        refresh_quickack(ctx.fd);
    }

    let tcp = poll_tcp_stats(ctx.fd);
    let (written, pace_ms) = ctx.sched.drain_wire(out_slice, tcp, max_bulk_override);
    ((written as i64) << 8) | (pace_ms as i64)
}

#[no_mangle]
pub extern "system" fn Java_dev_fy2ne_celeris_nativebridge_NativeBridge_updateLowat(
    _env: JNIEnv,
    _cls: JClass,
    ctx_ptr: jlong,
    lowat_bytes: jint,
) -> jint {
    let Some(ctx) = (unsafe { borrow_ctx(ctx_ptr) }) else {
        return 0;
    };
    let clamped = if lowat_bytes > 0 { lowat_bytes as u32 } else { 16384 };
    if update_lowat(ctx.fd, clamped) { 1 } else { 0 }
}

#[no_mangle]
pub extern "system" fn Java_dev_fy2ne_celeris_nativebridge_NativeBridge_getSocketCaps(
    _env: JNIEnv,
    _cls: JClass,
    ctx_ptr: jlong,
) -> jint {
    match unsafe { borrow_ctx(ctx_ptr) } {
        Some(ctx) => ctx.caps as jint,
        None => 0,
    }
}

#[no_mangle]
pub extern "system" fn Java_dev_fy2ne_celeris_nativebridge_NativeBridge_pollStats(
    env: JNIEnv,
    _cls: JClass,
    ctx_ptr: jlong,
    out_longs: JLongArray,
) -> jint {
    let Some(ctx) = (unsafe { borrow_ctx(ctx_ptr) }) else {
        return -1;
    };
    let tcp = poll_tcp_stats(ctx.fd).unwrap_or_default();
    let stats = [
        ctx.sched.express_packets as jlong,
        ctx.sched.bulk_packets as jlong,
        ctx.sched.preempted_packets as jlong,
        ctx.sched.bytes_raw as jlong,
        ctx.sched.bytes_wire as jlong,
        tcp.srtt_us as jlong,
        tcp.rttvar_us as jlong,
        tcp.unacked as jlong,
        ctx.caps as jlong,
        ctx.sched.express_delay_max_us as jlong,
        ctx.sched.express_delay_sum_us as jlong,
        ctx.sched.express_delay_count as jlong,
        ctx.sched.bulk_hold_max_us as jlong,
        ctx.sched.bulk_hold_sum_us as jlong,
        ctx.sched.bulk_hold_count as jlong,
        ctx.sched.queue_overflows as jlong,
    ];
    let arr_len = env.get_array_length(&out_longs).unwrap_or(0) as usize;
    let count = arr_len.min(stats.len());
    if count > 0 && env.set_long_array_region(&out_longs, 0, &stats[..count]).is_err() {
        return -2;
    }
    count as jint
}

#[no_mangle]
pub extern "system" fn Java_dev_fy2ne_celeris_nativebridge_NativeBridge_pollDelayHistogram(
    env: JNIEnv,
    _cls: JClass,
    ctx_ptr: jlong,
    out_buckets: JLongArray,
) -> jint {
    let Some(ctx) = (unsafe { borrow_ctx(ctx_ptr) }) else {
        return -1;
    };
    let arr_len = env.get_array_length(&out_buckets).unwrap_or(0) as usize;
    let count = arr_len.min(scheduler::HIST_BUCKETS);
    let mut tmp = [0 as jlong; scheduler::HIST_BUCKETS];
    for (dst, &src) in tmp.iter_mut().zip(ctx.sched.express_delay_hist.iter()).take(count) {
        *dst = src as jlong;
    }
    if count > 0 && env.set_long_array_region(&out_buckets, 0, &tmp[..count]).is_err() {
        return -2;
    }
    count as jint
}
