package dev.fy2ne.celeris.core;

import dev.fy2ne.celeris.nativebridge.NativeBridge;
import dev.fy2ne.celeris.netty.CelerisChannelHandler;
import io.netty.channel.Channel;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;
import java.util.zip.Inflater;

public final class CelerisEngine {
    public static final String VERSION = "0.1.0-beta";

    private static final ConcurrentHashMap<Channel, CelerisChannelHandler> ACTIVE = new ConcurrentHashMap<>();
    private static final Object RETIRED_LOCK = new Object();
    private static long retiredExpress;
    private static long retiredBulk;
    private static long retiredPreempted;
    private static long retiredDeferred;
    private static long retiredRawBytes;
    private static long retiredWireBytes;
    private static long retiredExpressDelayMaxUs;
    private static long retiredExpressDelaySumUs;
    private static long retiredExpressDelayCount;
    private static long retiredBulkHoldMaxUs;
    private static long retiredBulkHoldSumUs;
    private static long retiredBulkHoldCount;
    private static long retiredQueueOverflows;
    private static final long[] retiredHist = new long[NativeBridge.HIST_BUCKETS];

    private static final AtomicLong BYPASSED_PACKETS = new AtomicLong();
    private static final AtomicBoolean BENCH_RUNNING = new AtomicBoolean(false);

    private static volatile boolean enabled;
    private static volatile int lowatBytes = 16384;
    private static volatile int compressionLevel = 6;
    private static volatile int maxBulkPerFlush = 0; // 0 = auto tcp_info bbr pacer
    private static volatile Logger logger = Logger.getLogger("Celeris");

    private CelerisEngine() {}

    public static void boot(Logger log) {
        if (log != null) {
            logger = log;
        }
        enabled = true;
        boolean ok = NativeBridge.ensureLoaded();
        if (ok) {
            logger.info("celeris rust-native engine up (" + System.getProperty("os.name") + "/" + System.getProperty("os.arch") + ")");
        } else {
            logger.info("celeris java-engine up (" + System.getProperty("os.name") + "/" + System.getProperty("os.arch") + "; " + NativeBridge.getLoadError() + ")");
        }
    }

    public static boolean isEnabled() {
        return enabled;
    }

    public static void setEnabled(boolean active) {
        enabled = active;
    }

    public static void recordBypass() {
        BYPASSED_PACKETS.incrementAndGet();
    }

    public static boolean tryAcquireBench() {
        return BENCH_RUNNING.compareAndSet(false, true);
    }

    public static void releaseBench() {
        BENCH_RUNNING.set(false);
    }

    public static String engineMode() {
        return NativeBridge.isLoaded() ? "rust-native" : "java-engine";
    }

    public static void shutdown() {
        enabled = false;
        for (Map.Entry<Channel, CelerisChannelHandler> entry : ACTIVE.entrySet()) {
            Channel ch = entry.getKey();
            if (ch.isOpen()) {
                ch.eventLoop().execute(() -> {
                    var pipe = ch.pipeline();
                    if (pipe.get(CelerisChannelHandler.TAG_HANDLER) != null) {
                        pipe.remove(CelerisChannelHandler.TAG_HANDLER);
                    }
                    if (pipe.get(CelerisChannelHandler.WIRE_HANDLER) != null) {
                        pipe.remove(CelerisChannelHandler.WIRE_HANDLER);
                    }
                });
            }
        }
        ACTIVE.clear();
    }

    public static void registerContext(Channel ch, CelerisChannelHandler handler) {
        ACTIVE.put(ch, handler);
    }

    public static void retireContext(CelerisChannelHandler handler) {
        if (!ACTIVE.values().remove(handler)) {
            return;
        }
        long[] row = new long[16];
        long[] hist = new long[NativeBridge.HIST_BUCKETS];
        boolean hasRow = handler.pollStats(row);
        int hCount = handler.pollDelayHistogram(hist);
        long def = handler.deferredDrains();
        synchronized (RETIRED_LOCK) {
            retiredDeferred += def;
            if (hasRow) {
                retiredExpress += row[0];
                retiredBulk += row[1];
                retiredPreempted += row[2];
                retiredRawBytes += row[3];
                retiredWireBytes += row[4];
                if (row[9] > retiredExpressDelayMaxUs) retiredExpressDelayMaxUs = row[9];
                retiredExpressDelaySumUs += row[10];
                retiredExpressDelayCount += row[11];
                if (row[12] > retiredBulkHoldMaxUs) retiredBulkHoldMaxUs = row[12];
                retiredBulkHoldSumUs += row[13];
                retiredBulkHoldCount += row[14];
                retiredQueueOverflows += row[15];
            }
            for (int i = 0; i < hCount; i++) {
                retiredHist[i] += hist[i];
            }
        }
    }

    public static void unregisterContext(CelerisChannelHandler handler) {
        ACTIVE.values().remove(handler);
    }

    public static void resetStats() {
        synchronized (RETIRED_LOCK) {
            retiredExpress = 0;
            retiredBulk = 0;
            retiredPreempted = 0;
            retiredDeferred = 0;
            retiredRawBytes = 0;
            retiredWireBytes = 0;
            retiredExpressDelayMaxUs = 0;
            retiredExpressDelaySumUs = 0;
            retiredExpressDelayCount = 0;
            retiredBulkHoldMaxUs = 0;
            retiredBulkHoldSumUs = 0;
            retiredBulkHoldCount = 0;
            retiredQueueOverflows = 0;
            Arrays.fill(retiredHist, 0L);
        }
        BYPASSED_PACKETS.set(0L);
    }

    public static int activeCount() {
        return ACTIVE.size();
    }

    public static int lowatBytes() {
        return lowatBytes;
    }

    public static void setLowatBytes(int bytes) {
        lowatBytes = Math.max(4096, bytes);
        for (CelerisChannelHandler h : ACTIVE.values()) {
            h.applyLowat(lowatBytes);
        }
    }

    public static int compressionLevel() {
        return compressionLevel;
    }

    public static void setCompressionLevel(int lvl) {
        compressionLevel = Math.max(0, Math.min(9, lvl));
        for (CelerisChannelHandler h : ACTIVE.values()) {
            h.applyCompressionLevel(compressionLevel);
        }
    }

    public static int maxBulkPerFlush() {
        return maxBulkPerFlush;
    }

    public static void setMaxBulkPerFlush(int maxBytes) {
        maxBulkPerFlush = Math.max(0, maxBytes);
    }

    public static Snapshot snapshotStats() {
        long express;
        long bulk;
        long preempted;
        long deferred;
        long rawBytes;
        long wireBytes;
        long expDelayMaxUs;
        long expDelaySumUs;
        long expDelayCount;
        long bulkHoldMaxUs;
        long bulkHoldSumUs;
        long bulkHoldCount;
        long queueOverflows;
        long[] hist = new long[NativeBridge.HIST_BUCKETS];

        synchronized (RETIRED_LOCK) {
            express = retiredExpress;
            bulk = retiredBulk;
            preempted = retiredPreempted;
            deferred = retiredDeferred;
            rawBytes = retiredRawBytes;
            wireBytes = retiredWireBytes;
            expDelayMaxUs = retiredExpressDelayMaxUs;
            expDelaySumUs = retiredExpressDelaySumUs;
            expDelayCount = retiredExpressDelayCount;
            bulkHoldMaxUs = retiredBulkHoldMaxUs;
            bulkHoldSumUs = retiredBulkHoldSumUs;
            bulkHoldCount = retiredBulkHoldCount;
            queueOverflows = retiredQueueOverflows;
            System.arraycopy(retiredHist, 0, hist, 0, hist.length);
        }

        long srttSum = 0;
        long srttSamples = 0;
        int capsUnion = 0;
        long[] row = new long[16];
        long[] chHist = new long[NativeBridge.HIST_BUCKETS];

        for (CelerisChannelHandler h : ACTIVE.values()) {
            deferred += h.deferredDrains();
            if (h.pollStats(row)) {
                express += row[0];
                bulk += row[1];
                preempted += row[2];
                rawBytes += row[3];
                wireBytes += row[4];
                if (row[5] > 0) {
                    srttSum += row[5];
                    srttSamples++;
                }
                capsUnion |= (int) row[8];
                if (row[9] > expDelayMaxUs) expDelayMaxUs = row[9];
                expDelaySumUs += row[10];
                expDelayCount += row[11];
                if (row[12] > bulkHoldMaxUs) bulkHoldMaxUs = row[12];
                bulkHoldSumUs += row[13];
                bulkHoldCount += row[14];
                queueOverflows += row[15];
            }
            int hc = h.pollDelayHistogram(chHist);
            for (int i = 0; i < hc; i++) {
                hist[i] += chHist[i];
            }
        }

        double avgRttMs = srttSamples > 0 ? (srttSum / (double) srttSamples) / 1000.0 : -1.0;
        long histTotal = 0;
        for (long c : hist) histTotal += c;
        long p50 = percentileFromHist(hist, histTotal, 0.50, expDelayMaxUs);
        long p95 = percentileFromHist(hist, histTotal, 0.95, expDelayMaxUs);
        long p99 = percentileFromHist(hist, histTotal, 0.99, expDelayMaxUs);
        long expAvg = expDelayCount > 0 ? (expDelaySumUs / expDelayCount) : 0L;
        long bulkAvg = bulkHoldCount > 0 ? (bulkHoldSumUs / bulkHoldCount) : 0L;

        return new Snapshot(
            ACTIVE.size(),
            NativeBridge.isLoaded(),
            enabled,
            express,
            bulk,
            preempted,
            deferred,
            BYPASSED_PACKETS.get(),
            rawBytes,
            wireBytes,
            avgRttMs,
            capsUnion,
            p50,
            p95,
            p99,
            expDelayMaxUs,
            expAvg,
            expDelayCount,
            bulkAvg,
            bulkHoldMaxUs,
            bulkHoldCount,
            queueOverflows
        );
    }

    public static long percentileFromHist(long[] hist, long total, double pct, long maxUs) {
        if (total <= 0) return 0L;
        long target = Math.max(1L, (long) Math.ceil(total * pct));
        long cum = 0;
        for (int i = 0; i < hist.length; i++) {
            cum += hist[i];
            if (cum >= target) {
                long est = bucketMidUs(i);
                return maxUs > 0 ? Math.min(est, maxUs) : est;
            }
        }
        return maxUs;
    }

    private static long bucketMidUs(int idx) {
        if (idx < 16) return idx;
        int oct = ((idx - 16) >>> 2) + 4;
        int sub = (idx - 16) & 0x3;
        long base = 1L << oct;
        long step = 1L << (oct - 2);
        return base + sub * step + (step >>> 1);
    }

    public record Snapshot(
        int channels,
        boolean nativeLoaded,
        boolean enabled,
        long expressPackets,
        long bulkPackets,
        long preemptedPackets,
        long deferredDrains,
        long bypassedPackets,
        long rawBytes,
        long wireBytes,
        double avgKernelRttMs,
        int socketCaps,
        long expressP50Us,
        long expressP95Us,
        long expressP99Us,
        long expressMaxUs,
        long expressAvgUs,
        long expressSamples,
        long bulkHoldAvgUs,
        long bulkHoldMaxUs,
        long bulkHoldSamples,
        long queueOverflows
    ) {
        public double compressionSavedPct() {
            if (rawBytes <= 0 || wireBytes > rawBytes) return 0.0;
            return ((rawBytes - wireBytes) * 100.0) / rawBytes;
        }

        public String capsSummary() {
            if (socketCaps == 0) return "user-space-pacer";
            StringBuilder sb = new StringBuilder();
            if ((socketCaps & NativeBridge.CAP_NODELAY) != 0) sb.append("NODELAY ");
            if ((socketCaps & NativeBridge.CAP_LOWAT) != 0) sb.append("LOWAT ");
            if ((socketCaps & NativeBridge.CAP_BBR) != 0) sb.append("BBR ");
            if ((socketCaps & NativeBridge.CAP_QUICKACK) != 0) sb.append("QUICKACK ");
            if ((socketCaps & NativeBridge.CAP_TCP_INFO) != 0) sb.append("TCP_INFO ");
            if ((socketCaps & NativeBridge.CAP_SNDBUF_CLAMP) != 0) sb.append("SNDBUF_CLAMP ");
            return sb.toString().trim();
        }
    }

    public static List<String> inspectPipelines() {
        List<String> lines = new ArrayList<>();
        for (Map.Entry<Channel, CelerisChannelHandler> entry : ACTIVE.entrySet()) {
            Channel ch = entry.getKey();
            CelerisChannelHandler h = entry.getValue();
            List<String> names = ch.pipeline().names();
            int wireIdx = names.indexOf(CelerisChannelHandler.WIRE_HANDLER);
            int viaIdx = names.indexOf("via-encoder");
            String viaStatus = viaIdx < 0 ? "none"
                : (wireIdx >= 0 && wireIdx < viaIdx ? "safe(wire<via)" : "WARN(wire>via)");
            lines.add("ch=" + ch.remoteAddress()
                + " thresh=" + h.syncedThreshold()
                + (h.isCompressionUnsafe() ? " UNSAFE-BYPASS" : "")
                + " via=" + viaStatus
                + " pipe=" + names);
        }
        return lines;
    }

    public record BenchResult(
        String mode,
        int burstKb,
        int iterations,
        long fifoP50Us,
        long fifoP95Us,
        long fifoMaxUs,
        long celerisP50Us,
        long celerisP95Us,
        long celerisP99Us,
        long celerisMaxUs,
        long bulkHoldAvgUs,
        long bulkHoldMaxUs,
        long preempted,
        double savedPct
    ) {}

    public static BenchResult runBench(int burstKb, int iterations) {
        int kb = Math.max(32, Math.min(2048, burstKb));
        int iters = Math.max(5, Math.min(200, iterations));
        int chunksPerBurst = Math.max(1, kb / 16);

        byte[] chunk = new byte[16 * 1024];
        for (int i = 0; i < chunk.length; i++) chunk[i] = (byte) ((i * 7) ^ 0x5A);
        chunk[0] = 0x27;
        byte[] keepAlive = new byte[] { 0x26, 1, 2, 3, 4, 5, 6, 7, 8 };
        byte[] pos = new byte[54];
        pos[0] = 0x41;

        ByteBuffer out = ByteBuffer.allocateDirect(64 * 1024);
        long[] fifoSamples = new long[iters];

        // baseline FIFO: express queued behind chunks in a single lane, draining 32KB slices
        for (int it = 0; it < iters; it++) {
            JavaScheduler fifo = new JavaScheduler(256, compressionLevel);
            try {
                for (int c = 0; c < chunksPerBurst; c++) {
                    fifo.enqueueHeap(chunk, 0, chunk.length, 1);
                }
                long t0 = System.nanoTime();
                fifo.enqueueHeap(keepAlive, 0, keepAlive.length, 1);
                while (fifo.hasPending()) {
                    out.clear();
                    fifo.drainWire(out, 32 * 1024, 32 * 1024);
                }
                fifoSamples[it] = Math.max(1L, (System.nanoTime() - t0) / 1000L);
            } finally {
                fifo.close();
            }
        }
        Arrays.sort(fifoSamples);
        long fifoP50 = fifoSamples[(int) (iters * 0.50)];
        long fifoP95 = fifoSamples[Math.min(iters - 1, (int) (iters * 0.95))];
        long fifoMax = fifoSamples[iters - 1];

        long[] row = new long[15];
        long[] hist = new long[NativeBridge.HIST_BUCKETS];
        long preempted = 0;
        long rawBytes = 0;
        long wireBytes = 0;
        long celMax = 0;
        long bulkSum = 0;
        long bulkMax = 0;
        long bulkCnt = 0;

        if (NativeBridge.isLoaded()) {
            long ptr = NativeBridge.createContext(256, compressionLevel, -1L, lowatBytes);
            try {
                for (int it = 0; it < iters; it++) {
                    for (int c = 0; c < chunksPerBurst; c++) {
                        NativeBridge.enqueueHeapPacket(ptr, chunk, 0, chunk.length, 1);
                    }
                    NativeBridge.enqueueHeapPacket(ptr, keepAlive, 0, keepAlive.length, 0);
                    NativeBridge.enqueueHeapPacket(ptr, pos, 0, pos.length, 0);
                    out.clear();
                    long packed = NativeBridge.drainWire(ptr, out, 32 * 1024, 32 * 1024);
                    while ((packed & 0xFFL) > 0) {
                        out.clear();
                        packed = NativeBridge.drainWire(ptr, out, 32 * 1024, 32 * 1024);
                    }
                }
                NativeBridge.pollStats(ptr, row);
                NativeBridge.pollDelayHistogram(ptr, hist);
            } finally {
                NativeBridge.destroyContext(ptr);
            }
        } else {
            JavaScheduler js = new JavaScheduler(256, compressionLevel);
            try {
                for (int it = 0; it < iters; it++) {
                    for (int c = 0; c < chunksPerBurst; c++) {
                        js.enqueueHeap(chunk, 0, chunk.length, 1);
                    }
                    js.enqueueHeap(keepAlive, 0, keepAlive.length, 0);
                    js.enqueueHeap(pos, 0, pos.length, 0);
                    out.clear();
                    long packed = js.drainWire(out, 32 * 1024, 32 * 1024);
                    while ((packed & 0xFFL) > 0) {
                        out.clear();
                        packed = js.drainWire(out, 32 * 1024, 32 * 1024);
                    }
                }
                js.pollStats(row);
                js.pollDelayHistogram(hist);
            } finally {
                js.close();
            }
        }

        preempted = row[2];
        rawBytes = row[3];
        wireBytes = row[4];
        celMax = row[9];
        bulkMax = row[12];
        bulkSum = row[13];
        bulkCnt = row[14];

        long histTotal = 0;
        for (long v : hist) histTotal += v;
        long celP50 = percentileFromHist(hist, histTotal, 0.50, celMax);
        long celP95 = percentileFromHist(hist, histTotal, 0.95, celMax);
        long celP99 = percentileFromHist(hist, histTotal, 0.99, celMax);
        long bulkAvg = bulkCnt > 0 ? bulkSum / bulkCnt : 0L;
        double savedPct = rawBytes > 0 && wireBytes <= rawBytes ? ((rawBytes - wireBytes) * 100.0) / rawBytes : 0.0;

        return new BenchResult(
            engineMode(),
            kb,
            iters,
            fifoP50,
            fifoP95,
            fifoMax,
            celP50,
            celP95,
            celP99,
            celMax,
            bulkAvg,
            bulkMax,
            preempted,
            savedPct
        );
    }

    public record SelfTestResult(boolean ok, String mode, long elapsedUs, String summary) {}

    public static SelfTestResult runSelfTest() {
        long t0 = System.nanoTime();
        byte[] chunk = new byte[16 * 1024];
        for (int i = 0; i < chunk.length; i++) chunk[i] = (byte) (0x20 + (i % 11));
        chunk[0] = 0x27;
        byte[] keepAlive = new byte[] { 0x26, 1, 2, 3, 4, 5, 6, 7, 8 };

        ByteBuffer out = ByteBuffer.allocateDirect(64 * 1024);
        long writtenTotal;
        byte[] firstWire;

        if (NativeBridge.isLoaded()) {
            long ptr = NativeBridge.createContext(64, compressionLevel, -1L, lowatBytes);
            try {
                for (int i = 0; i < 4; i++) {
                    NativeBridge.enqueueHeapPacket(ptr, chunk, 0, chunk.length, 1);
                }
                NativeBridge.enqueueHeapPacket(ptr, keepAlive, 0, keepAlive.length, 0);
                long packed = NativeBridge.drainWire(ptr, out, out.capacity(), 0);
                int written = (int) (packed >>> 8);
                firstWire = new byte[written];
                out.duplicate().get(0, firstWire, 0, written);
                writtenTotal = written;
            } finally {
                NativeBridge.destroyContext(ptr);
            }
        } else {
            JavaScheduler js = new JavaScheduler(64, compressionLevel);
            try {
                for (int i = 0; i < 4; i++) {
                    js.enqueueHeap(chunk, 0, chunk.length, 1);
                }
                js.enqueueHeap(keepAlive, 0, keepAlive.length, 0);
                long packed = js.drainWire(out, out.capacity(), 0);
                int written = (int) (packed >>> 8);
                firstWire = new byte[written];
                out.duplicate().get(0, firstWire, 0, written);
                writtenTotal = written;
            } finally {
                js.close();
            }
        }

        long elapsedUs = Math.max(1L, (System.nanoTime() - t0) / 1000L);
        try {
            byte[] firstPkt = decodeFirstFrame(firstWire);
            boolean preemptOk = Arrays.equals(firstPkt, keepAlive);
            String msg = preemptOk
                ? "preempted 64KB chunk burst in " + elapsedUs + "us (wire=" + writtenTotal + "B)"
                : "first frame mismatch (len=" + firstPkt.length + ")";
            return new SelfTestResult(preemptOk, engineMode(), elapsedUs, msg);
        } catch (Throwable t) {
            return new SelfTestResult(false, engineMode(), elapsedUs, "decode err: " + t.getMessage());
        }
    }

    private static byte[] decodeFirstFrame(byte[] wire) throws Exception {
        int[] f = readVarInt(wire, 0);
        int frameLen = f[0];
        int p = f[1];
        int frameEnd = p + frameLen;
        int[] u = readVarInt(wire, p);
        int uncompLen = u[0];
        int dataStart = u[1];
        int dataLen = frameEnd - dataStart;
        if (uncompLen == 0) {
            byte[] raw = new byte[dataLen];
            System.arraycopy(wire, dataStart, raw, 0, dataLen);
            return raw;
        }
        Inflater inf = new Inflater();
        inf.setInput(wire, dataStart, dataLen);
        byte[] dec = new byte[uncompLen];
        inf.inflate(dec);
        inf.end();
        return dec;
    }

    private static int[] readVarInt(byte[] buf, int off) {
        int val = 0;
        int shift = 0;
        int p = off;
        while (true) {
            byte b = buf[p++];
            val |= (b & 0x7f) << shift;
            if ((b & 0x80) == 0) return new int[] { val, p };
            shift += 7;
        }
    }
}
