package dev.fy2ne.celeris.netty;

import dev.fy2ne.celeris.core.CelerisEngine;
import dev.fy2ne.celeris.core.JavaScheduler;
import dev.fy2ne.celeris.nativebridge.NativeBridge;
import dev.fy2ne.celeris.nativebridge.SocketFdExtractor;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.ChannelPromise;
import io.netty.util.concurrent.ScheduledFuture;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.ByteBuffer;
import java.util.concurrent.TimeUnit;

public final class CelerisChannelHandler extends ChannelDuplexHandler {
    public static final String TAG_HANDLER = "celeris-tag";
    public static final String WIRE_HANDLER = "celeris-wire";

    private static final int DRAIN_CHUNK_BUF = 256 * 1024;

    private static final class ChannelState {
        final PacketClassifier classifier = new PacketClassifier();
        int pendingTaggedKind = PacketClassifier.KIND_AUTO;
        long rustPtr;
        JavaScheduler javaSched;
        int syncedThreshold = Integer.MIN_VALUE;
        boolean compressionHandled;
        boolean compressionUnsafe;
        boolean relocating;
        boolean drainScheduled;
        boolean backpressurePaused;
        boolean bulkPending;
        boolean hasQueued;
        long nextBulkDrainNs;
        long deferredDrains;
        int queuedEstimateBytes;
        boolean cleaned;
        ScheduledFuture<?> scheduledFuture;
        ChannelHandlerContext wireCtx;

        ChannelHandler lastCompHandler;
        ChannelHandler lastViaHandler;
        ChannelHandler lastPrepHandler;
        boolean wirePositioned;

        boolean ready() {
            return rustPtr != 0L || javaSched != null;
        }
    }

    private final ChannelState state;

    public CelerisChannelHandler() {
        this(new ChannelState());
    }

    private CelerisChannelHandler(ChannelState state) {
        this.state = state;
    }

    @FunctionalInterface
    private interface ThresholdReader {
        int read(Object target) throws Exception;
    }

    private static final ThresholdReader NO_READER = target -> Integer.MIN_VALUE;

    private static final ClassValue<ThresholdReader> THRESHOLD_READER_CACHE = new ClassValue<>() {
        @Override
        protected ThresholdReader computeValue(Class<?> type) {
            for (Class<?> cur = type; cur != null && cur != Object.class; cur = cur.getSuperclass()) {
                for (Method m : cur.getDeclaredMethods()) {
                    if (m.getParameterCount() == 0 && m.getReturnType() == int.class && !Modifier.isStatic(m.getModifiers())) {
                        String n = m.getName().toLowerCase();
                        if (n.equals("getthreshold") || n.equals("threshold")) {
                            try {
                                m.setAccessible(true);
                                return target -> (int) m.invoke(target);
                            } catch (Throwable ignored) {
                            }
                        }
                    }
                }
            }

            Field fallback = null;
            int nonStaticInts = 0;
            for (Class<?> cur = type; cur != null && cur != Object.class; cur = cur.getSuperclass()) {
                for (Field f : cur.getDeclaredFields()) {
                    if (f.getType() != int.class || Modifier.isStatic(f.getModifiers())) {
                        continue;
                    }
                    nonStaticInts++;
                    String n = f.getName().toLowerCase();
                    if (n.contains("threshold")) {
                        try {
                            f.setAccessible(true);
                            return f::getInt;
                        } catch (Throwable ignored) {
                        }
                    }
                    if (fallback == null) {
                        fallback = f;
                    }
                }
            }
            // single non-static int covers obfuscated fabric CompressionEncoder (class_2532)
            if (fallback != null && nonStaticInts == 1) {
                try {
                    fallback.setAccessible(true);
                    Field f = fallback;
                    return f::getInt;
                } catch (Throwable ignored) {
                }
            }
            return NO_READER;
        }
    };

    public PacketTagHandler newTagHandler() {
        return new PacketTagHandler(state);
    }

    public static final class PacketTagHandler extends ChannelOutboundHandlerAdapter {
        private final ChannelState state;

        private PacketTagHandler(ChannelState state) {
            this.state = state;
        }

        @Override
        public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
            if (msg == null) {
                super.write(ctx, null, promise);
                return;
            }
            if ((!CelerisEngine.isEnabled() && !state.hasQueued) || state.compressionUnsafe) {
                super.write(ctx, msg, promise);
                return;
            }
            ensureWirePosition(ctx.pipeline(), state);
            if (!(msg instanceof ByteBuf)) {
                state.pendingTaggedKind = state.classifier.classifyJavaPacket(msg);
                try {
                    super.write(ctx, msg, promise);
                } finally {
                    state.pendingTaggedKind = PacketClassifier.KIND_AUTO;
                }
                return;
            }
            super.write(ctx, msg, promise);
        }
    }

    private static String firstExisting(ChannelPipeline pipe, String... names) {
        for (String n : names) {
            if (pipe.get(n) != null) return n;
        }
        return null;
    }

    private static ChannelHandler firstExistingHandler(ChannelPipeline pipe, String... names) {
        for (String n : names) {
            ChannelHandler h = pipe.get(n);
            if (h != null) return h;
        }
        return null;
    }

    private static void ensureWirePosition(ChannelPipeline pipe, ChannelState state) {
        ChannelHandler curComp = firstExistingHandler(pipe, "compress", "compression-encoder", "via-compress");
        ChannelHandler curVia = pipe.get("via-encoder");
        ChannelHandler curPrep = firstExistingHandler(pipe, "prepender", "frame-encoder");
        if (state.wirePositioned
            && curComp == state.lastCompHandler
            && curVia == state.lastViaHandler
            && curPrep == state.lastPrepHandler) {
            return;
        }

        String compressName = firstExisting(pipe, "compress", "compression-encoder", "via-compress");
        String viaEncoder = pipe.get("via-encoder") != null ? "via-encoder" : null;
        String encoderName = firstExisting(pipe, "encoder", "minecraft-encoder");

        var names = pipe.names();
        int wireIdx = names.indexOf(WIRE_HANDLER);
        if (wireIdx < 0) {
            return;
        }
        int compIdx = compressName != null ? names.indexOf(compressName) : -1;
        int viaIdx = viaEncoder != null ? names.indexOf(viaEncoder) : -1;
        int encIdx = encoderName != null ? names.indexOf(encoderName) : -1;

        // stay head-ward of via-encoder/encoder and tail-ward of compress/prepender without oscillating
        boolean moveAfterCompress = compIdx >= 0 && wireIdx < compIdx;
        boolean moveBeforeVia = !moveAfterCompress && viaIdx >= 0 && wireIdx > viaIdx && (compIdx < 0 || viaIdx > compIdx);
        boolean moveBeforeEnc = !moveAfterCompress && !moveBeforeVia && encIdx >= 0 && wireIdx > encIdx && (compIdx < 0 || encIdx > compIdx);

        state.lastCompHandler = curComp;
        state.lastViaHandler = curVia;
        state.lastPrepHandler = curPrep;
        state.wirePositioned = true;

        if (!moveAfterCompress && !moveBeforeVia && !moveBeforeEnc) {
            return;
        }

        state.relocating = true;
        try {
            pipe.remove(WIRE_HANDLER);
            CelerisChannelHandler replacement = new CelerisChannelHandler(state);
            if (moveAfterCompress) {
                pipe.addAfter(compressName, WIRE_HANDLER, replacement);
            } else if (moveBeforeVia) {
                pipe.addBefore(viaEncoder, WIRE_HANDLER, replacement);
            } else {
                pipe.addBefore(encoderName, WIRE_HANDLER, replacement);
            }
        } finally {
            state.relocating = false;
        }
    }

    @Override
    public void handlerAdded(ChannelHandlerContext ctx) {
        state.wireCtx = ctx;
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        cleanup();
        super.channelInactive(ctx);
    }

    @Override
    public void handlerRemoved(ChannelHandlerContext ctx) {
        if (state.wireCtx == ctx) {
            state.wireCtx = null;
        }
        if (!state.relocating) {
            cleanup();
        }
    }

    @Override
    public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
        if (!(msg instanceof ByteBuf buf) || state.compressionUnsafe) {
            if (state.compressionUnsafe && msg instanceof ByteBuf) {
                CelerisEngine.recordBypass();
            }
            super.write(ctx, msg, promise);
            return;
        }

        if (!CelerisEngine.isEnabled()) {
            if (state.hasQueued && state.ready()) {
                drainToWire(ctx, false, false);
            }
            if (!state.hasQueued) {
                CelerisEngine.recordBypass();
                super.write(ctx, buf, promise);
                return;
            }
        }

        int kind = state.pendingTaggedKind;
        if (!state.ready()) {
            if (kind == PacketClassifier.KIND_STATUS) {
                CelerisEngine.recordBypass();
                super.write(ctx, buf, promise);
                return;
            }
            ensureBackendCtx(ctx.channel());
            if (!state.ready()) {
                CelerisEngine.recordBypass();
                super.write(ctx, buf, promise);
                return;
            }
        }

        syncCompressionState(ctx.pipeline());
        if (state.compressionUnsafe) {
            if (state.hasQueued) {
                drainToWire(ctx, false, false);
            }
            CelerisEngine.recordBypass();
            super.write(ctx, buf, promise);
            return;
        }

        int readable = buf.readableBytes();
        int lane = state.classifier.resolveLane(kind, readable, state.bulkPending);
        if (readable <= 0) {
            buf.release();
            promise.trySuccess();
            return;
        }

        int rc = enqueuePacket(buf, readable, lane);
        if (rc == -7) {
            if (ctx.channel().isWritable()) {
                drainToWire(ctx, true, false);
                rc = enqueuePacket(buf, readable, lane);
            }
            if (rc == -7) {
                buf.release();
                promise.tryFailure(new IllegalStateException("celeris queue overflow"));
                ctx.close();
                return;
            }
        }
        if (rc == -6) {
            if (state.hasQueued) {
                drainToWire(ctx, true, false);
            }
            CelerisEngine.recordBypass();
            super.write(ctx, buf, promise);
            return;
        }
        if (rc < 0) {
            super.write(ctx, buf, promise);
            return;
        }
        state.queuedEstimateBytes += readable;
        state.bulkPending = rc > 0;
        state.hasQueued = true;

        buf.release();
        promise.trySuccess();
    }

    @Override
    public void flush(ChannelHandlerContext ctx) throws Exception {
        if (!state.ready() || state.compressionUnsafe) {
            super.flush(ctx);
            return;
        }
        if (!CelerisEngine.isEnabled() && !state.hasQueued) {
            super.flush(ctx);
            return;
        }
        drainToWire(ctx, true, false);
    }

    private void ensureBackendCtx(Channel ch) {
        if (!state.ready()) {
            if (NativeBridge.isLoaded()) {
                long fd = SocketFdExtractor.extract(ch);
                state.rustPtr = NativeBridge.createContext(-1, CelerisEngine.compressionLevel(), fd, CelerisEngine.lowatBytes());
            } else {
                state.javaSched = new JavaScheduler(-1, CelerisEngine.compressionLevel());
            }
        }
        if (state.ready()) {
            CelerisEngine.registerContext(ch, this);
        }
    }

    private void syncCompressionState(ChannelPipeline pipe) {
        ChannelHandler compress = firstExistingHandler(pipe, "compress", "compression-encoder", "via-compress");
        if (compress != null) {
            int thresh = readThreshold(compress);
            if (thresh == Integer.MIN_VALUE) {
                state.compressionUnsafe = true;
                return;
            }
            state.compressionUnsafe = false;
            if (thresh != state.syncedThreshold) {
                state.syncedThreshold = thresh;
                state.compressionHandled = true;
                pushCompression(thresh, CelerisEngine.compressionLevel());
            }
            return;
        }

        state.compressionUnsafe = false;
        if (state.syncedThreshold != -1) {
            if (firstExisting(pipe, "prepender", "frame-encoder") != null) {
                state.syncedThreshold = -1;
                state.compressionHandled = true;
                pushCompression(-1, CelerisEngine.compressionLevel());
            }
        }
    }

    private void pushCompression(int thresh, int level) {
        if (state.rustPtr != 0L) {
            NativeBridge.setCompression(state.rustPtr, thresh, level);
        } else if (state.javaSched != null) {
            state.javaSched.setCompression(thresh, level);
        }
    }

    private static int readThreshold(ChannelHandler compressHandler) {
        ThresholdReader reader = THRESHOLD_READER_CACHE.get(compressHandler.getClass());
        if (reader == NO_READER) {
            return Integer.MIN_VALUE;
        }
        try {
            int thresh = reader.read(compressHandler);
            return (thresh < -1 || thresh > JavaScheduler.MAX_PACKET_BYTES) ? Integer.MIN_VALUE : thresh;
        } catch (Throwable e) {
            return Integer.MIN_VALUE;
        }
    }

    private int enqueuePacket(ByteBuf buf, int readable, int lane) {
        if (buf.nioBufferCount() == 1) {
            ByteBuffer nio = buf.nioBuffer();
            if (nio.isDirect()) {
                if (state.rustPtr != 0L) {
                    return NativeBridge.enqueueDirectPacket(state.rustPtr, nio, nio.position(), readable, lane);
                }
                return state.javaSched.enqueueDirect(nio, nio.position(), readable, lane);
            }
            if (nio.hasArray()) {
                byte[] arr = nio.array();
                int off = nio.arrayOffset() + nio.position();
                if (state.rustPtr != 0L) {
                    return NativeBridge.enqueueHeapPacket(state.rustPtr, arr, off, readable, lane);
                }
                return state.javaSched.enqueueHeap(arr, off, readable, lane);
            }
        }

        byte[] copy = new byte[readable];
        buf.getBytes(buf.readerIndex(), copy);
        if (state.rustPtr != 0L) {
            return NativeBridge.enqueueHeapPacket(state.rustPtr, copy, 0, readable, lane);
        }
        return state.javaSched.enqueueHeap(copy, 0, readable, lane);
    }

    @Override
    public void channelWritabilityChanged(ChannelHandlerContext ctx) throws Exception {
        if (state.backpressurePaused && ctx.channel().isWritable() && state.ready()) {
            state.backpressurePaused = false;
            drainToWire(ctx, true, true);
        }
        super.channelWritabilityChanged(ctx);
    }

    private void drainToWire(ChannelHandlerContext ctx, boolean doFlush, boolean fromTimer) {
        if (!state.ready() || !ctx.channel().isActive()) {
            return;
        }

        boolean writable = ctx.channel().isWritable();
        boolean inBundle = state.classifier.isInBundle();
        long nowNs = System.nanoTime();
        boolean pacingHold = !fromTimer && state.drainScheduled && nowNs < state.nextBulkDrainNs;
        // express drains immediately; only bulk yields to backpressure, open bundles, or pacing cooldown
        int bulkOverride = (!writable || inBundle || pacingHold) ? -1 : CelerisEngine.maxBulkPerFlush();
        if (!writable) {
            state.backpressurePaused = true;
            state.deferredDrains++;
        } else {
            state.backpressurePaused = false;
        }

        int cap = state.bulkPending
            ? DRAIN_CHUNK_BUF
            : Math.min(DRAIN_CHUNK_BUF, Math.max(16 * 1024, state.queuedEstimateBytes + 4096));
        ByteBuf outBuf = ctx.alloc().directBuffer(cap);

        ByteBuffer nio = outBuf.nioBuffer(0, cap);
        long packed = state.rustPtr != 0L
            ? NativeBridge.drainWire(state.rustPtr, nio, cap, bulkOverride)
            : state.javaSched.drainWire(nio, cap, bulkOverride);
        if (packed < 0L) {
            outBuf.release();
            return;
        }

        int written = (int) (packed >>> 8);
        int paceMs = (int) (packed & 0xFFL);
        state.hasQueued = paceMs > 0;
        if (paceMs == 0) {
            state.bulkPending = false;
            state.queuedEstimateBytes = 0;
        } else {
            state.queuedEstimateBytes = Math.max(0, state.queuedEstimateBytes - written);
        }

        if (written > 0) {
            outBuf.writerIndex(written);
            ChannelHandlerContext target = resolveEgressContext(ctx);
            if (doFlush) {
                target.writeAndFlush(outBuf);
            } else {
                target.write(outBuf);
            }
        } else {
            outBuf.release();
            if (doFlush) {
                resolveEgressContext(ctx).flush();
            }
        }

        if (writable && paceMs > 0 && !inBundle && !state.drainScheduled && ctx.channel().isActive()) {
            state.nextBulkDrainNs = nowNs + paceMs * 1_000_000L;
            state.drainScheduled = true;
            state.scheduledFuture = ctx.executor().schedule(() -> {
                state.drainScheduled = false;
                ChannelHandlerContext cur = state.wireCtx;
                if (cur != null && state.ready() && cur.channel().isActive()) {
                    drainToWire(cur, true, true);
                }
            }, paceMs, TimeUnit.MILLISECONDS);
        }
    }

    public void applyLowat(int lowatBytes) {
        ChannelHandlerContext cur = state.wireCtx;
        if (cur == null || state.rustPtr == 0L) {
            return;
        }
        Runnable r = () -> {
            if (state.rustPtr != 0L) {
                NativeBridge.updateLowat(state.rustPtr, lowatBytes);
            }
        };
        if (cur.executor().inEventLoop()) {
            r.run();
        } else {
            cur.executor().execute(r);
        }
    }

    public void applyCompressionLevel(int level) {
        ChannelHandlerContext cur = state.wireCtx;
        if (cur == null || !state.ready()) {
            return;
        }
        Runnable r = () -> {
            if (state.ready() && state.syncedThreshold != Integer.MIN_VALUE) {
                pushCompression(state.syncedThreshold, level);
            }
        };
        if (cur.executor().inEventLoop()) {
            r.run();
        } else {
            cur.executor().execute(r);
        }
    }

    public long deferredDrains() {
        return state.deferredDrains;
    }

    public int syncedThreshold() {
        return state.syncedThreshold;
    }

    public boolean isCompressionUnsafe() {
        return state.compressionUnsafe;
    }

    private ChannelHandlerContext resolveEgressContext(ChannelHandlerContext self) {
        if (!state.compressionHandled) {
            return self;
        }
        ChannelPipeline pipe = self.pipeline();
        var names = pipe.names();
        int wireIdx = names.indexOf(WIRE_HANDLER);
        // pick whichever framing/compress handler sits closest to socket head (ahead of wire) so encrypt still runs
        for (int i = 0; i < names.size(); i++) {
            if (wireIdx >= 0 && i >= wireIdx) {
                break;
            }
            String name = names.get(i);
            if ("compress".equals(name)
                || "prepender".equals(name)
                || "compression-encoder".equals(name)
                || "frame-encoder".equals(name)
                || "via-compress".equals(name)) {
                ChannelHandlerContext ctx = pipe.context(name);
                if (ctx != null) {
                    return ctx;
                }
            }
        }
        return self;
    }

    public boolean pollStats(long[] out) {
        if (state.rustPtr != 0L) {
            return NativeBridge.pollStats(state.rustPtr, out) > 0;
        }
        if (state.javaSched != null) {
            return state.javaSched.pollStats(out);
        }
        return false;
    }

    public int pollDelayHistogram(long[] outBuckets) {
        if (state.rustPtr != 0L) {
            return NativeBridge.pollDelayHistogram(state.rustPtr, outBuckets);
        }
        if (state.javaSched != null) {
            return state.javaSched.pollDelayHistogram(outBuckets);
        }
        return 0;
    }

    private void cleanup() {
        if (state.cleaned) {
            return;
        }
        state.cleaned = true;
        CelerisEngine.retireContext(this);
        if (state.scheduledFuture != null) {
            state.scheduledFuture.cancel(false);
            state.scheduledFuture = null;
        }
        if (state.rustPtr != 0L) {
            long ptr = state.rustPtr;
            state.rustPtr = 0L;
            NativeBridge.destroyContext(ptr);
        }
        if (state.javaSched != null) {
            state.javaSched.close();
            state.javaSched = null;
        }
        CelerisEngine.unregisterContext(this);
    }
}
