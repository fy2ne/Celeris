package dev.fy2ne.celeris.core;

import dev.fy2ne.celeris.nativebridge.NativeBridge;

import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.zip.Deflater;

public final class JavaScheduler {
    public static final int MAX_PACKET_BYTES = 8 * 1024 * 1024;
    public static final int MAX_EXPRESS_QUEUE_BYTES = 2 * 1024 * 1024;
    public static final int MAX_BULK_QUEUE_BYTES = 6 * 1024 * 1024;
    private static final int SHRINK_CAP_BYTES = 16 * 1024;
    private static final int BASE_BUDGET = 32 * 1024;
    private static final int PACE_MS = 6;

    private int threshold;
    private int level;
    private Deflater deflater;

    private byte[] expressBuf = new byte[8192];
    private int expressHead;
    private int expressTail;
    private long expressStreamHead;
    private long expressStreamTail;

    // parallel primitive rings for enqueue timestamps without boxing
    private long[] expressEndOffsets = new long[64];
    private long[] expressTsNs = new long[64];
    private int expTsHead;
    private int expTsCount;

    private final ArrayDeque<byte[]> bulkQ = new ArrayDeque<>(32);
    private long[] bulkTsNs = new long[32];
    private int bulkTsHead;
    private int bulkTsCount;
    private int bulkBytesQueued;
    private byte[] partialFrame;
    private int partialOffset;

    private byte[] compScratch = new byte[16384];
    private byte[] copyScratch = new byte[4096];

    long expressPackets;
    long bulkPackets;
    long preemptedPackets;
    long rawBytes;
    long wireBytes;

    final long[] expressDelayHist = new long[NativeBridge.HIST_BUCKETS];
    long expressDelayMaxUs;
    long expressDelaySumUs;
    long expressDelayCount;
    long bulkHoldMaxUs;
    long bulkHoldSumUs;
    long bulkHoldCount;
    long queueOverflows;

    public JavaScheduler(int threshold, int level) {
        this.threshold = threshold;
        this.level = clampLevel(level);
        this.deflater = new Deflater(this.level);
    }

    public void setCompression(int threshold, int level) {
        this.threshold = threshold;
        int clamped = clampLevel(level);
        if (clamped != this.level) {
            this.level = clamped;
            if (this.deflater != null) {
                this.deflater.end();
            }
            this.deflater = new Deflater(clamped);
        }
    }

    public int enqueueDirect(ByteBuffer src, int pos, int len, int lane) {
        if (len <= 0) return bulkPendingFlag();
        if (len > MAX_PACKET_BYTES) return -6;
        if (copyScratch.length < len) {
            copyScratch = new byte[Math.max(len, copyScratch.length * 2)];
        }
        ByteBuffer dup = src.duplicate();
        dup.position(pos);
        dup.get(copyScratch, 0, len);
        int rc = enqueueHeap(copyScratch, 0, len, lane);
        if (copyScratch.length > SHRINK_CAP_BYTES) {
            copyScratch = new byte[4096];
        }
        return rc;
    }

    public int enqueueHeap(byte[] raw, int off, int len, int lane) {
        if (len <= 0) return bulkPendingFlag();
        if (len > MAX_PACKET_BYTES) return -6;

        if (lane == 0) {
            if ((expressTail - expressHead) + len + 16 > MAX_EXPRESS_QUEUE_BYTES) {
                queueOverflows++;
                return -7;
            }
            rawBytes += len;
            long now = System.nanoTime();
            expressPackets++;
            if (bulkBytesQueued > 0 || partialFrame != null) {
                preemptedPackets++;
            }
            int before = expressTail;
            frameIntoExpress(raw, off, len);
            int added = expressTail - before;
            expressStreamTail += added;
            pushExpressTs(expressStreamTail, now);
        } else {
            int partialRem = partialFrame != null ? (partialFrame.length - partialOffset) : 0;
            if ((long) bulkBytesQueued + partialRem + len > MAX_BULK_QUEUE_BYTES) {
                queueOverflows++;
                return -7;
            }
            rawBytes += len;
            long now = System.nanoTime();
            bulkPackets++;
            byte[] framed = frameStandalone(raw, off, len);
            bulkBytesQueued += framed.length;
            bulkQ.addLast(framed);
            pushBulkTs(now);
        }
        return bulkPendingFlag();
    }

    public long drainWire(ByteBuffer outDirect, int cap, int maxBulkOverride) {
        if (cap <= 0) {
            return hasPending() ? PACE_MS : 0L;
        }
        int budget = maxBulkOverride < 0 ? 0 : (maxBulkOverride > 0 ? maxBulkOverride : BASE_BUDGET);
        long now = System.nanoTime();
        int cursor = 0;

        // 1. finish any mid-stream partial bulk frame before cutting express in
        if (partialFrame != null) {
            int rem = partialFrame.length - partialOffset;
            int take = Math.min(rem, cap - cursor);
            putBytes(outDirect, cursor, partialFrame, partialOffset, take);
            cursor += take;
            partialOffset += take;
            if (partialOffset < partialFrame.length) {
                wireBytes += cursor;
                return (((long) cursor) << 8) | PACE_MS;
            }
            partialFrame = null;
            partialOffset = 0;
        }

        // 2. express lane always drains completely ahead of bulk
        int expressAvail = expressTail - expressHead;
        if (expressAvail > 0 && cursor < cap) {
            int take = Math.min(expressAvail, cap - cursor);
            putBytes(outDirect, cursor, expressBuf, expressHead, take);
            cursor += take;
            expressHead += take;
            expressStreamHead += take;
            while (expTsCount > 0 && expressEndOffsets[expTsHead] <= expressStreamHead) {
                long t0 = expressTsNs[expTsHead];
                expTsHead = (expTsHead + 1) & (expressEndOffsets.length - 1);
                expTsCount--;
                long us = Math.max(0L, (now - t0) / 1000L);
                expressDelayHist[bucketForUs(us)]++;
                expressDelaySumUs += us;
                if (us > expressDelayMaxUs) expressDelayMaxUs = us;
                expressDelayCount++;
            }
            if (expressHead == expressTail) {
                expressHead = 0;
                expressTail = 0;
            } else if (expressHead > 4096 && expressHead * 2 >= expressBuf.length) {
                int left = expressTail - expressHead;
                System.arraycopy(expressBuf, expressHead, expressBuf, 0, left);
                expressHead = 0;
                expressTail = left;
            }
        }

        // 3. bulk drains only when express is empty and channel is not backpressured (budget > 0)
        if (budget > 0 && expressHead == expressTail && cursor < cap && !bulkQ.isEmpty()) {
            int bulkEmitted = 0;
            while (!bulkQ.isEmpty()) {
                byte[] front = bulkQ.peekFirst();
                int space = cap - cursor;
                if (space == 0) break;
                if (bulkEmitted > 0 && (bulkEmitted + front.length > budget || front.length > space)) {
                    break;
                }
                bulkQ.pollFirst();
                if (bulkTsCount > 0) {
                    long t0 = bulkTsNs[bulkTsHead];
                    bulkTsHead = (bulkTsHead + 1) & (bulkTsNs.length - 1);
                    bulkTsCount--;
                    long us = Math.max(0L, (now - t0) / 1000L);
                    bulkHoldSumUs += us;
                    if (us > bulkHoldMaxUs) bulkHoldMaxUs = us;
                    bulkHoldCount++;
                }
                bulkBytesQueued = Math.max(0, bulkBytesQueued - front.length);

                if (front.length <= space) {
                    putBytes(outDirect, cursor, front, 0, front.length);
                    cursor += front.length;
                    bulkEmitted += front.length;
                } else {
                    putBytes(outDirect, cursor, front, 0, space);
                    cursor += space;
                    partialFrame = front;
                    partialOffset = space;
                    break;
                }
            }
        }

        if (cursor > 0) {
            wireBytes += cursor;
        }
        if (expressHead == expressTail) {
            if (expressBuf.length > SHRINK_CAP_BYTES) {
                expressBuf = new byte[8192];
            }
            if (expTsCount == 0 && expressEndOffsets.length > 256) {
                expressEndOffsets = new long[64];
                expressTsNs = new long[64];
                expTsHead = 0;
            }
        }
        if (bulkQ.isEmpty() && partialFrame == null) {
            if (compScratch.length > SHRINK_CAP_BYTES) {
                compScratch = new byte[16384];
            }
            if (bulkTsCount == 0 && bulkTsNs.length > 256) {
                bulkTsNs = new long[32];
                bulkTsHead = 0;
            }
        }
        long nextPace = hasPending() ? PACE_MS : 0L;
        return (((long) cursor) << 8) | nextPace;
    }

    public boolean pollStats(long[] out) {
        if (out == null || out.length < 9) return false;
        out[0] = expressPackets;
        out[1] = bulkPackets;
        out[2] = preemptedPackets;
        out[3] = rawBytes;
        out[4] = wireBytes;
        out[5] = 0;
        out[6] = 0;
        out[7] = 0;
        out[8] = 0;
        if (out.length >= 15) {
            out[9] = expressDelayMaxUs;
            out[10] = expressDelaySumUs;
            out[11] = expressDelayCount;
            out[12] = bulkHoldMaxUs;
            out[13] = bulkHoldSumUs;
            out[14] = bulkHoldCount;
        }
        if (out.length >= 16) {
            out[15] = queueOverflows;
        }
        return true;
    }

    public int pollDelayHistogram(long[] outBuckets) {
        if (outBuckets == null) return 0;
        int n = Math.min(outBuckets.length, expressDelayHist.length);
        System.arraycopy(expressDelayHist, 0, outBuckets, 0, n);
        return n;
    }

    public static int bucketForUs(long us) {
        if (us <= 0) return 0;
        if (us < 16) return (int) us;
        int clamped = (int) Math.min(us, Integer.MAX_VALUE);
        int bit = 31 - Integer.numberOfLeadingZeros(clamped);
        int sub = (clamped >>> (bit - 2)) & 0x3;
        return Math.min(NativeBridge.HIST_BUCKETS - 1, 16 + ((bit - 4) << 2) + sub);
    }

    private void pushExpressTs(long endOffset, long nowNs) {
        if (expTsCount == expressEndOffsets.length) {
            int cap = expressEndOffsets.length;
            long[] nextOff = new long[cap * 2];
            long[] nextTs = new long[cap * 2];
            int tailLen = cap - expTsHead;
            System.arraycopy(expressEndOffsets, expTsHead, nextOff, 0, tailLen);
            System.arraycopy(expressEndOffsets, 0, nextOff, tailLen, expTsHead);
            System.arraycopy(expressTsNs, expTsHead, nextTs, 0, tailLen);
            System.arraycopy(expressTsNs, 0, nextTs, tailLen, expTsHead);
            expressEndOffsets = nextOff;
            expressTsNs = nextTs;
            expTsHead = 0;
        }
        int idx = (expTsHead + expTsCount) & (expressEndOffsets.length - 1);
        expressEndOffsets[idx] = endOffset;
        expressTsNs[idx] = nowNs;
        expTsCount++;
    }

    private void pushBulkTs(long nowNs) {
        if (bulkTsCount == bulkTsNs.length) {
            int cap = bulkTsNs.length;
            long[] nextTs = new long[cap * 2];
            int tailLen = cap - bulkTsHead;
            System.arraycopy(bulkTsNs, bulkTsHead, nextTs, 0, tailLen);
            System.arraycopy(bulkTsNs, 0, nextTs, tailLen, bulkTsHead);
            bulkTsNs = nextTs;
            bulkTsHead = 0;
        }
        int idx = (bulkTsHead + bulkTsCount) & (bulkTsNs.length - 1);
        bulkTsNs[idx] = nowNs;
        bulkTsCount++;
    }

    public void close() {
        if (deflater != null) {
            deflater.end();
            deflater = null;
        }
        bulkQ.clear();
        partialFrame = null;
    }

    private void frameIntoExpress(byte[] raw, int off, int len) {
        if (threshold < 0) {
            int hdr = varIntSize(len);
            ensureExpressSpace(hdr + len);
            writeVarIntExpress(len);
            System.arraycopy(raw, off, expressBuf, expressTail, len);
            expressTail += len;
            return;
        }
        if (len < threshold) {
            int inner = 1 + len;
            int hdr = varIntSize(inner);
            ensureExpressSpace(hdr + inner);
            writeVarIntExpress(inner);
            expressBuf[expressTail++] = 0x00;
            System.arraycopy(raw, off, expressBuf, expressTail, len);
            expressTail += len;
            return;
        }
        int compLen = compressIntoScratch(raw, off, len);
        int inner = varIntSize(len) + compLen;
        int hdr = varIntSize(inner);
        ensureExpressSpace(hdr + inner);
        writeVarIntExpress(inner);
        writeVarIntExpress(len);
        System.arraycopy(compScratch, 0, expressBuf, expressTail, compLen);
        expressTail += compLen;
    }

    private byte[] frameStandalone(byte[] raw, int off, int len) {
        if (threshold < 0) {
            int hdr = varIntSize(len);
            byte[] out = new byte[hdr + len];
            int p = putVarInt(out, 0, len);
            System.arraycopy(raw, off, out, p, len);
            return out;
        }
        if (len < threshold) {
            int inner = 1 + len;
            int hdr = varIntSize(inner);
            byte[] out = new byte[hdr + inner];
            int p = putVarInt(out, 0, inner);
            out[p++] = 0x00;
            System.arraycopy(raw, off, out, p, len);
            return out;
        }
        int compLen = compressIntoScratch(raw, off, len);
        int inner = varIntSize(len) + compLen;
        int hdr = varIntSize(inner);
        byte[] out = new byte[hdr + inner];
        int p = putVarInt(out, 0, inner);
        p = putVarInt(out, p, len);
        System.arraycopy(compScratch, 0, out, p, compLen);
        return out;
    }

    private int compressIntoScratch(byte[] raw, int off, int len) {
        deflater.reset();
        deflater.setInput(raw, off, len);
        deflater.finish();
        int total = 0;
        while (!deflater.finished()) {
            if (total == compScratch.length) {
                compScratch = Arrays.copyOf(compScratch, compScratch.length * 2);
            }
            int n = deflater.deflate(compScratch, total, compScratch.length - total);
            if (n == 0 && deflater.needsInput()) {
                break;
            }
            total += n;
        }
        return total;
    }

    private void ensureExpressSpace(int needed) {
        if (expressTail + needed <= expressBuf.length) {
            return;
        }
        int avail = expressTail - expressHead;
        if (expressHead > 0 && avail + needed <= expressBuf.length) {
            System.arraycopy(expressBuf, expressHead, expressBuf, 0, avail);
            expressHead = 0;
            expressTail = avail;
            return;
        }
        int newCap = Math.max(expressBuf.length * 2, avail + needed + 1024);
        byte[] grown = new byte[newCap];
        System.arraycopy(expressBuf, expressHead, grown, 0, avail);
        expressBuf = grown;
        expressHead = 0;
        expressTail = avail;
    }

    private void writeVarIntExpress(int val) {
        expressTail = putVarInt(expressBuf, expressTail, val);
    }

    private static int putVarInt(byte[] dst, int pos, int val) {
        int v = val;
        while ((v & ~0x7f) != 0) {
            dst[pos++] = (byte) ((v & 0x7f) | 0x80);
            v >>>= 7;
        }
        dst[pos++] = (byte) v;
        return pos;
    }

    private static int varIntSize(int val) {
        int v = val;
        int n = 1;
        while ((v & ~0x7f) != 0) {
            v >>>= 7;
            n++;
        }
        return n;
    }

    private static void putBytes(ByteBuffer dst, int dstPos, byte[] src, int srcOff, int len) {
        ByteBuffer slice = dst.duplicate();
        slice.position(dstPos);
        slice.put(src, srcOff, len);
    }

    public boolean hasPending() {
        return expressTail > expressHead || !bulkQ.isEmpty() || partialFrame != null;
    }

    private int bulkPendingFlag() {
        return (!bulkQ.isEmpty() || partialFrame != null) ? 1 : 0;
    }

    private static int clampLevel(int lvl) {
        return Math.max(0, Math.min(9, lvl));
    }
}
