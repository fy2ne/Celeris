package dev.fy2ne.celeris;

import dev.fy2ne.celeris.core.CelerisEngine;
import dev.fy2ne.celeris.core.JavaScheduler;
import dev.fy2ne.celeris.nativebridge.NativeBridge;
import dev.fy2ne.celeris.nativebridge.SocketFdExtractor;
import dev.fy2ne.celeris.netty.CelerisChannelHandler;
import dev.fy2ne.celeris.netty.PacketClassifier;
import dev.fy2ne.celeris.netty.PipelineInjector;
import io.netty.bootstrap.Bootstrap;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;
import java.util.zip.Inflater;

import static org.junit.jupiter.api.Assertions.*;

final class CelerisPipelineTest {
    static final class LevelChunkWithLightPacket {
        final byte[] data;
        LevelChunkWithLightPacket(byte[] data) { this.data = data; }
    }

    static final class KeepAlivePacket {
        final byte[] data;
        KeepAlivePacket(byte[] data) { this.data = data; }
    }

    static final class ClientboundCommandsPacket {}

    static final class BundleDelimiterPacket {}

    static final class ClientboundPingPacket {}

    static final class ClientboundPongResponsePacket {}

    static final class ClientboundStatusResponsePacket {}

    static final class WeirdCompressHandler extends ChannelOutboundHandlerAdapter {
        private final int unrelatedFirst = 99999;
        private final int unrelatedSecond = 64;
        final AtomicInteger hits = new AtomicInteger();

        @Override
        public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
            hits.incrementAndGet();
            super.write(ctx, msg, promise);
        }
    }

    static final class ServerboundAcceptTeleportationPacket {}

    static final class ClientboundChunkBatchStartPacket {}

    static final class ClientboundChunkBatchFinishedPacket {}

    static final class ClientboundFinishConfigurationPacket {}

    static final class MojangCompressionEncoder extends ChannelOutboundHandlerAdapter {
        @SuppressWarnings("unused")
        public static final int MAXIMUM_COMPRESSED_LENGTH = 8388608;
        @SuppressWarnings("unused")
        public static final int MAXIMUM_UNCOMPRESSED_LENGTH = 8388608;
        private final int threshold;
        final AtomicInteger hits = new AtomicInteger();

        MojangCompressionEncoder(int threshold) {
            this.threshold = threshold;
        }

        @Override
        public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
            hits.incrementAndGet();
            super.write(ctx, msg, promise);
        }
    }

    static final class FakePacketType {
        private final String s;
        FakePacketType(String s) { this.s = s; }
        @Override public String toString() { return s; }
    }

    static final class class_2670 {
        public FakePacketType method_55846() { return new FakePacketType("clientbound/minecraft:keep_alive"); }
    }

    static final class class_8739 {
        public FakePacketType method_55846() { return new FakePacketType("clientbound/minecraft:chunk_batch_finished"); }
    }

    static final class DummyEncoder extends ChannelOutboundHandlerAdapter {
        @Override
        public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
            if (msg instanceof LevelChunkWithLightPacket c) {
                super.write(ctx, Unpooled.wrappedBuffer(c.data), promise);
            } else if (msg instanceof KeepAlivePacket k) {
                super.write(ctx, Unpooled.wrappedBuffer(k.data), promise);
            } else if (msg instanceof BundleDelimiterPacket) {
                super.write(ctx, Unpooled.EMPTY_BUFFER, promise);
            } else if (msg instanceof ClientboundStatusResponsePacket) {
                super.write(ctx, Unpooled.wrappedBuffer(new byte[] { 0x00, 0x02, '{', '}' }), promise);
            } else if (msg instanceof ClientboundPongResponsePacket) {
                super.write(ctx, Unpooled.wrappedBuffer(new byte[] { 0x01, 1, 2, 3, 4, 5, 6, 7, 8 }), promise);
            } else {
                super.write(ctx, msg, promise);
            }
        }
    }

    static final class CountingCompress extends ChannelOutboundHandlerAdapter {
        final int threshold;
        final AtomicInteger hits = new AtomicInteger();

        CountingCompress(int threshold) {
            this.threshold = threshold;
        }

        @Override
        public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
            hits.incrementAndGet();
            super.write(ctx, msg, promise);
        }
    }

    @Test
    void classifierDetectsPacketsAndSelfHealsLeakedBundle() {
        PacketClassifier pc = new PacketClassifier();
        assertEquals(PacketClassifier.LANE_BULK, pc.resolveLane(pc.classifyJavaPacket(new ClientboundCommandsPacket()), 128));
        assertEquals(PacketClassifier.LANE_EXPRESS, pc.resolveLane(pc.classifyJavaPacket(new KeepAlivePacket(new byte[9])), 9));

        pc.resolveLane(pc.classifyJavaPacket(new BundleDelimiterPacket()), 1);
        assertTrue(pc.isInBundle());
        for (int i = 0; i < 512; i++) {
            assertEquals(PacketClassifier.LANE_EXPRESS, pc.resolveLane(pc.classifyJavaPacket(new LevelChunkWithLightPacket(new byte[4096])), 4096));
        }
        // next packet after cap should recover bulk classification rather than staying stuck
        assertEquals(PacketClassifier.LANE_BULK, pc.resolveLane(pc.classifyJavaPacket(new LevelChunkWithLightPacket(new byte[4096])), 4096));
        assertFalse(pc.isInBundle());
    }

    @Test
    void embeddedPipelineBypassesCompressAndPreemptsBulk() throws Exception {
        CelerisEngine.boot(Logger.getLogger("CelerisTest"));
        assertTrue(NativeBridge.isLoaded(), "native must load: " + NativeBridge.getLoadError());

        CountingCompress compress = new CountingCompress(64);
        EmbeddedChannel ch = new EmbeddedChannel();
        ch.pipeline().addLast("prepender", new ChannelOutboundHandlerAdapter());
        ch.pipeline().addLast("compress", compress);
        ch.pipeline().addLast("encoder", new DummyEncoder());

        PipelineInjector.injectChannel(ch);
        ch.runPendingTasks();

        byte[] chunk = new byte[5120];
        for (int i = 0; i < chunk.length; i++) chunk[i] = (byte) (0x20 + (i % 9));
        chunk[0] = 0x27;

        byte[] keepAlive = new byte[] { 0x26, 1, 2, 3, 4, 5, 6, 7, 8 };

        ch.write(new LevelChunkWithLightPacket(chunk));
        ch.write(new KeepAlivePacket(keepAlive));
        ch.write(new BundleDelimiterPacket());
        ch.write(new LevelChunkWithLightPacket(chunk));
        ch.write(new KeepAlivePacket(keepAlive));
        ch.write(new BundleDelimiterPacket());
        ch.flush();
        ch.runPendingTasks();

        assertEquals(0, compress.hits.get(), "stock compress must be bypassed once celeris-wire relocates");

        List<byte[]> wirePackets = decodeCompressedFrames(drainAllOutbound(ch));
        assertEquals(4, wirePackets.size());
        assertArrayEquals(keepAlive, wirePackets.get(0), "standalone KeepAlive must preempt unbundled chunk");
        assertArrayEquals(chunk, wirePackets.get(1), "bundled chunk stays in bundle order ahead of bundled KeepAlive");
        assertArrayEquals(keepAlive, wirePackets.get(2), "bundled KeepAlive follows bundled chunk atomically");
        assertArrayEquals(chunk, wirePackets.get(3), "unbundled bulk chunk drains after express lane");

        ch.close().sync();
        assertEquals(0, CelerisEngine.activeCount());
    }

    @Test
    void backpressureHoldsBulkInRustWhileAllowingExpressThrough() throws Exception {
        CelerisEngine.boot(Logger.getLogger("CelerisTest"));
        assertTrue(NativeBridge.isLoaded());

        final boolean[] writableFlag = { true };
        EmbeddedChannel ch = new EmbeddedChannel(
            new ChannelOutboundHandlerAdapter(),
            new CountingCompress(64),
            new DummyEncoder()
        ) {
            @Override
            public boolean isWritable() {
                return writableFlag[0];
            }
        };
        var names = ch.pipeline().names();
        ch.pipeline().replace(names.get(0), "prepender", new ChannelOutboundHandlerAdapter());
        ch.pipeline().replace(names.get(1), "compress", new CountingCompress(64));
        ch.pipeline().replace(names.get(2), "encoder", new DummyEncoder());

        PipelineInjector.injectChannel(ch);
        ch.runPendingTasks();

        byte[] chunk = new byte[4096];
        chunk[0] = 0x27;
        byte[] ping = new byte[] { 0x26, 9, 8, 7, 6, 5, 4, 3, 2 };

        // simulate congested socket high-water mark before flushing
        writableFlag[0] = false;
        ch.write(new LevelChunkWithLightPacket(chunk));
        ch.write(new KeepAlivePacket(ping));
        ch.flush();

        List<byte[]> duringBackpressure = decodeCompressedFrames(drainAllOutbound(ch));
        assertEquals(1, duringBackpressure.size(), "only Express may drain while channel is unwritable");
        assertArrayEquals(ping, duringBackpressure.get(0));
        assertTrue(CelerisEngine.snapshotStats().deferredDrains() >= 1);

        // once socket becomes writable again, channelWritabilityChanged must drain held chunk
        writableFlag[0] = true;
        ch.pipeline().fireChannelWritabilityChanged();

        List<byte[]> resumed = decodeCompressedFrames(drainAllOutbound(ch));
        assertEquals(1, resumed.size());
        assertArrayEquals(chunk, resumed.get(0));

        ch.close().sync();
        assertEquals(0, CelerisEngine.activeCount());
    }

    @Test
    void tcpLoopbackSocketTuningAndLiveRuntimeUpdatesWork() throws Exception {
        CelerisEngine.boot(Logger.getLogger("CelerisTest"));
        assertTrue(NativeBridge.isLoaded());

        EventLoopGroup boss = new NioEventLoopGroup(1);
        EventLoopGroup workers = new NioEventLoopGroup(2);
        CountDownLatch accepted = new CountDownLatch(1);
        CountDownLatch received = new CountDownLatch(1);
        AtomicReference<Channel> serverChild = new AtomicReference<>();
        ByteArrayOutputStream rxBytes = new ByteArrayOutputStream();
        byte[] payload = new byte[2048];
        for (int i = 0; i < payload.length; i++) payload[i] = (byte) ('A' + (i % 13));

        try {
            ChannelFuture bind = new ServerBootstrap()
                .group(boss, workers)
                .channel(NioServerSocketChannel.class)
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        ch.pipeline().addLast("prepender", new ChannelOutboundHandlerAdapter());
                        ch.pipeline().addLast("compress", new CountingCompress(64));
                        ch.pipeline().addLast("encoder", new DummyEncoder());
                        PipelineInjector.injectChannel(ch);
                        serverChild.set(ch);
                        accepted.countDown();
                    }
                })
                .bind("127.0.0.1", 0)
                .sync();

            int port = ((InetSocketAddress) bind.channel().localAddress()).getPort();
            Channel client = new Bootstrap()
                .group(workers)
                .channel(NioSocketChannel.class)
                .handler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        ch.pipeline().addLast(new ChannelInboundHandlerAdapter() {
                            @Override
                            public void channelRead(ChannelHandlerContext ctx, Object msg) {
                                ByteBuf b = (ByteBuf) msg;
                                try {
                                    byte[] arr = new byte[b.readableBytes()];
                                    b.readBytes(arr);
                                    synchronized (rxBytes) {
                                        rxBytes.writeBytes(arr);
                                        if (rxBytes.size() >= 10) {
                                            try {
                                                List<byte[]> decoded = decodeCompressedFrames(rxBytes.toByteArray());
                                                if (!decoded.isEmpty()) received.countDown();
                                            } catch (Exception ignored) {
                                            }
                                        }
                                    }
                                } finally {
                                    b.release();
                                }
                            }
                        });
                    }
                })
                .connect("127.0.0.1", port)
                .sync()
                .channel();

            assertTrue(accepted.await(3, TimeUnit.SECONDS));
            Channel srv = serverChild.get();
            assertNotNull(srv);

            // trigger compression sync + live runtime tuning over real NIO socket fd
            srv.eventLoop().submit(() -> {
                assertTrue(SocketFdExtractor.extract(srv) >= 0 || System.getProperty("java.vm.name") != null);
                CelerisEngine.setLowatBytes(32768);
                CelerisEngine.setCompressionLevel(4);
                srv.write(new LevelChunkWithLightPacket(payload));
                srv.flush();
            }).sync();

            assertTrue(received.await(3, TimeUnit.SECONDS), "expected compressed wire frame over real TCP loopback");
            List<byte[]> frames;
            synchronized (rxBytes) {
                frames = decodeCompressedFrames(rxBytes.toByteArray());
            }
            assertEquals(1, frames.size());
            assertArrayEquals(payload, frames.get(0));

            client.close().sync();
            srv.close().sync();
            bind.channel().close().sync();
        } finally {
            boss.shutdownGracefully().sync();
            workers.shutdownGracefully().sync();
        }
    }

    @Test
    void viaVersionPipelineOrderStaysSafeBehindViaEncoder() throws Exception {
        CelerisEngine.boot(Logger.getLogger("CelerisTest"));

        CountingCompress compress = new CountingCompress(64);
        AtomicInteger viaHits = new AtomicInteger();
        ChannelOutboundHandlerAdapter viaEncoder = new ChannelOutboundHandlerAdapter() {
            @Override
            public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
                viaHits.incrementAndGet();
                super.write(ctx, msg, promise);
            }
        };

        EmbeddedChannel ch = new EmbeddedChannel();
        ch.pipeline().addLast("prepender", new ChannelOutboundHandlerAdapter());
        ch.pipeline().addLast("compress", compress);
        ch.pipeline().addLast("via-encoder", viaEncoder);
        ch.pipeline().addLast("encoder", new DummyEncoder());

        PipelineInjector.injectChannel(ch);
        ch.runPendingTasks();

        byte[] chunk = new byte[4096];
        chunk[0] = 0x27;
        byte[] keepAlive = new byte[] { 0x26, 4, 3, 2, 1 };

        ch.write(new LevelChunkWithLightPacket(chunk));
        ch.write(new KeepAlivePacket(keepAlive));
        ch.flush();
        ch.runPendingTasks();

        assertEquals(2, viaHits.get(), "via-encoder must still process both packets before celeris-wire");
        assertEquals(0, compress.hits.get(), "stock compress must stay bypassed");
        List<String> debug = CelerisEngine.inspectPipelines();
        assertTrue(debug.stream().anyMatch(s -> s.contains("via=safe(wire<via)")));

        List<byte[]> wire = decodeCompressedFrames(drainAllOutbound(ch));
        assertEquals(2, wire.size());
        assertArrayEquals(keepAlive, wire.get(0));
        assertArrayEquals(chunk, wire.get(1));

        ch.close().sync();
        assertEquals(0, CelerisEngine.activeCount());
    }

    @Test
    void pureJavaFallbackAndSelfTestWorkEndToEnd() throws Exception {
        CelerisEngine.boot(Logger.getLogger("CelerisTest"));
        var res = CelerisEngine.runSelfTest();
        assertTrue(res.ok(), res.summary());

        JavaScheduler js = new JavaScheduler(64, 6);
        try {
            byte[] chunk = new byte[4096];
            for (int i = 0; i < chunk.length; i++) chunk[i] = (byte) (i & 0x3f);
            byte[] ka = new byte[] { 0x26, 7, 7, 7 };

            assertEquals(1, js.enqueueHeap(chunk, 0, chunk.length, PacketClassifier.LANE_BULK));
            assertEquals(1, js.enqueueHeap(ka, 0, ka.length, PacketClassifier.LANE_EXPRESS));

            ByteBuffer out = ByteBuffer.allocateDirect(64 * 1024);
            long packed = js.drainWire(out, out.capacity(), -1);
            int written = (int) (packed >>> 8);
            assertTrue((packed & 0xffL) > 0);
            byte[] wire1 = new byte[written];
            out.duplicate().get(0, wire1, 0, written);
            List<byte[]> f1 = decodeCompressedFrames(wire1);
            assertEquals(1, f1.size());
            assertArrayEquals(ka, f1.get(0));

            out.clear();
            long packed2 = js.drainWire(out, out.capacity(), 0);
            int written2 = (int) (packed2 >>> 8);
            assertEquals(0L, packed2 & 0xffL);
            byte[] wire2 = new byte[written2];
            out.duplicate().get(0, wire2, 0, written2);
            List<byte[]> f2 = decodeCompressedFrames(wire2);
            assertEquals(1, f2.size());
            assertArrayEquals(chunk, f2.get(0));
        } finally {
            js.close();
        }
    }

    @Test
    void staticConstantsInCompressionEncoderDoNotCorruptThresholdSync() throws Exception {
        CelerisEngine.boot(Logger.getLogger("CelerisTest"));
        MojangCompressionEncoder compress = new MojangCompressionEncoder(64);
        EmbeddedChannel ch = new EmbeddedChannel();
        ch.pipeline().addLast("prepender", new ChannelOutboundHandlerAdapter());
        ch.pipeline().addLast("compress", compress);
        ch.pipeline().addLast("encoder", new DummyEncoder());

        PipelineInjector.injectChannel(ch);
        ch.runPendingTasks();

        byte[] payload = new byte[2048];
        for (int i = 0; i < payload.length; i++) payload[i] = (byte) (i & 0x0F);
        ch.write(new LevelChunkWithLightPacket(payload));
        ch.flush();
        ch.runPendingTasks();

        assertEquals(0, compress.hits.get());
        byte[] wire = drainAllOutbound(ch);
        // must actually compress (< 2048B) instead of reading 8388608 static constant
        assertTrue(wire.length < 512, "expected compressed wire < 512B, got " + wire.length);
        List<byte[]> decoded = decodeCompressedFrames(wire);
        assertEquals(1, decoded.size());
        assertArrayEquals(payload, decoded.get(0));
        ch.close().sync();
    }

    @Test
    void controlBarriersFabricIntermediaryAndViaDropsStayConsistent() throws Exception {
        PacketClassifier pc = new PacketClassifier();
        assertEquals(PacketClassifier.LANE_EXPRESS, pc.resolveLane(pc.classifyJavaPacket(new ClientboundPingPacket()), 9, true));
        assertEquals(PacketClassifier.LANE_EXPRESS, pc.resolveLane(pc.classifyJavaPacket(new ClientboundPongResponsePacket()), 9, true));
        assertEquals(PacketClassifier.LANE_EXPRESS, pc.resolveLane(pc.classifyJavaPacket(new ServerboundAcceptTeleportationPacket()), 5, true));
        assertEquals(PacketClassifier.LANE_EXPRESS, pc.resolveLane(pc.classifyJavaPacket(new class_2670()), 4096, true));

        // barriers stay behind bulk when bulk is pending, otherwise express
        assertEquals(PacketClassifier.LANE_BULK, pc.resolveLane(pc.classifyJavaPacket(new ClientboundChunkBatchStartPacket()), 1, true));
        assertEquals(PacketClassifier.LANE_BULK, pc.resolveLane(pc.classifyJavaPacket(new ClientboundChunkBatchFinishedPacket()), 3, true));
        assertEquals(PacketClassifier.LANE_BULK, pc.resolveLane(pc.classifyJavaPacket(new ClientboundFinishConfigurationPacket()), 1, true));
        assertEquals(PacketClassifier.LANE_BULK, pc.resolveLane(pc.classifyJavaPacket(new class_8739()), 3, true));
        assertEquals(PacketClassifier.LANE_EXPRESS, pc.resolveLane(pc.classifyJavaPacket(new ClientboundFinishConfigurationPacket()), 1, false));

        // via-encoder dropping a BundleDelimiter or Chunk must not leak pendingTaggedKind or inBundle
        CelerisEngine.boot(Logger.getLogger("CelerisTest"));
        ChannelOutboundHandlerAdapter droppingVia = new ChannelOutboundHandlerAdapter() {
            @Override
            public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
                ByteBuf b = (ByteBuf) msg;
                if (b.readableBytes() == 0) {
                    // simulates ViaVersion cancelling BundleDelimiter for legacy client
                    b.release();
                    promise.trySuccess();
                    return;
                }
                super.write(ctx, msg, promise);
            }
        };

        EmbeddedChannel ch = new EmbeddedChannel();
        ch.pipeline().addLast("prepender", new ChannelOutboundHandlerAdapter());
        ch.pipeline().addLast("compress", new CountingCompress(64));
        ch.pipeline().addLast("via-encoder", droppingVia);
        ch.pipeline().addLast("encoder", new DummyEncoder());

        PipelineInjector.injectChannel(ch);
        ch.runPendingTasks();

        byte[] chunk = new byte[4096];
        chunk[0] = 0x27;
        byte[] ka = new byte[] { 0x26, 1, 2, 3, 4 };

        ch.write(new BundleDelimiterPacket()); // dropped by via-encoder
        ch.write(new LevelChunkWithLightPacket(chunk));
        ch.write(new KeepAlivePacket(ka));
        ch.flush();
        ch.runPendingTasks();

        // KeepAlive still preempts Chunk because dropped BundleDelimiter did not stick inBundle=true
        List<byte[]> frames = decodeCompressedFrames(drainAllOutbound(ch));
        assertEquals(2, frames.size());
        assertArrayEquals(ka, frames.get(0));
        assertArrayEquals(chunk, frames.get(1));
        ch.close().sync();
    }

    @Test
    void inactiveBypassAndBenchTelemetryWork() throws Exception {
        CelerisEngine.boot(Logger.getLogger("CelerisTest"));
        CelerisEngine.resetStats();
        try {
            CountingCompress compress = new CountingCompress(64);
            EmbeddedChannel ch = new EmbeddedChannel();
            ch.pipeline().addLast("prepender", new ChannelOutboundHandlerAdapter());
            ch.pipeline().addLast("compress", compress);
            ch.pipeline().addLast("encoder", new DummyEncoder());

            PipelineInjector.injectChannel(ch);
            ch.runPendingTasks();

            CelerisEngine.setEnabled(false);
            ch.writeAndFlush(new KeepAlivePacket(new byte[] { 0x26, 1, 2 }));
            ch.runPendingTasks();
            assertEquals(1, compress.hits.get(), "inactive engine must bypass to stock pipeline");
            assertTrue(CelerisEngine.snapshotStats().bypassedPackets() >= 1);
            ch.close().sync();
        } finally {
            CelerisEngine.setEnabled(true);
        }

        var bench = CelerisEngine.runBench(128, 10);
        assertTrue(bench.preempted() >= 10);
        assertTrue(bench.celerisP99Us() <= bench.celerisMaxUs());
        assertTrue(bench.savedPct() > 50.0);
    }

    @Test
    void hardeningLazyInitSlpStrictProbeSlowlorisAndBenchLock() throws Exception {
        CelerisEngine.boot(Logger.getLogger("CelerisTest"));
        CelerisEngine.resetStats();

        // 1. lazy backend init + SLP status/pong zero-alloc bypass
        CountingCompress slpCompress = new CountingCompress(64);
        EmbeddedChannel slpCh = new EmbeddedChannel();
        slpCh.pipeline().addLast("prepender", new ChannelOutboundHandlerAdapter());
        slpCh.pipeline().addLast("compress", slpCompress);
        slpCh.pipeline().addLast("encoder", new DummyEncoder());
        PipelineInjector.injectChannel(slpCh);
        slpCh.runPendingTasks();
        assertEquals(0, CelerisEngine.activeCount(), "handlerAdded must not eagerly allocate backend ctx");

        slpCh.write(new ClientboundStatusResponsePacket());
        slpCh.writeAndFlush(new ClientboundPongResponsePacket());
        slpCh.runPendingTasks();
        assertEquals(0, CelerisEngine.activeCount(), "SLP packets must bypass backend ctx creation");
        assertEquals(2, slpCompress.hits.get());
        slpCh.close().sync();

        // 2. strict compression probe bypasses ambiguous multi-int custom compress handlers
        WeirdCompressHandler weird = new WeirdCompressHandler();
        EmbeddedChannel weirdCh = new EmbeddedChannel();
        weirdCh.pipeline().addLast("prepender", new ChannelOutboundHandlerAdapter());
        weirdCh.pipeline().addLast("compress", weird);
        weirdCh.pipeline().addLast("encoder", new DummyEncoder());
        PipelineInjector.injectChannel(weirdCh);
        weirdCh.runPendingTasks();
        weirdCh.writeAndFlush(new KeepAlivePacket(new byte[] { 0x26, 1, 2, 3 }));
        weirdCh.runPendingTasks();
        CelerisChannelHandler weirdWire = (CelerisChannelHandler) weirdCh.pipeline().get(CelerisChannelHandler.WIRE_HANDLER);
        assertTrue(weirdWire.isCompressionUnsafe());
        assertEquals(1, weird.hits.get());
        weirdCh.close().sync();

        // 3. slowloris !isWritable() >6MB bulk flood fails promise, closes socket, counts overflow
        EmbeddedChannel slowCh = new EmbeddedChannel(
            new ChannelOutboundHandlerAdapter(),
            new CountingCompress(256),
            new DummyEncoder()
        ) {
            @Override
            public boolean isWritable() {
                return false;
            }
        };
        var sNames = slowCh.pipeline().names();
        slowCh.pipeline().replace(sNames.get(0), "prepender", new ChannelOutboundHandlerAdapter());
        slowCh.pipeline().replace(sNames.get(1), "compress", new CountingCompress(256));
        slowCh.pipeline().replace(sNames.get(2), "encoder", new DummyEncoder());
        PipelineInjector.injectChannel(slowCh);
        slowCh.runPendingTasks();

        byte[] incompressible = new byte[512 * 1024];
        new java.util.Random(42L).nextBytes(incompressible);
        ChannelFuture lastFuture = null;
        for (int i = 0; i < 16 && slowCh.isOpen(); i++) {
            lastFuture = slowCh.write(new LevelChunkWithLightPacket(incompressible));
        }
        assertNotNull(lastFuture);
        assertFalse(slowCh.isOpen(), "stalled slowloris channel must close on >6MB queue overflow");
        assertFalse(lastFuture.isSuccess());
        assertTrue(CelerisEngine.snapshotStats().queueOverflows() >= 1);

        // 4. JavaScheduler bounds check (>8MB single packet -6, >2MB express -7)
        JavaScheduler js = new JavaScheduler(-1, 6);
        try {
            assertEquals(-6, js.enqueueHeap(new byte[8 * 1024 * 1024 + 1], 0, 8 * 1024 * 1024 + 1, 0));
            byte[] chunk64k = new byte[64 * 1024];
            int overflowRc = 0;
            for (int i = 0; i < 40; i++) {
                overflowRc = js.enqueueHeap(chunk64k, 0, chunk64k.length, 0);
                if (overflowRc < 0) break;
            }
            assertEquals(-7, overflowRc);
            long[] st = new long[16];
            assertTrue(js.pollStats(st));
            assertEquals(1L, st[15]);
        } finally {
            js.close();
        }

        // 5. concurrent /celeris bench lock guard
        assertTrue(CelerisEngine.tryAcquireBench());
        assertFalse(CelerisEngine.tryAcquireBench());
        CelerisEngine.releaseBench();
        assertTrue(CelerisEngine.tryAcquireBench());
        CelerisEngine.releaseBench();
    }

    private static byte[] drainAllOutbound(EmbeddedChannel ch) {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        Object msg;
        while ((msg = ch.readOutbound()) != null) {
            ByteBuf b = (ByteBuf) msg;
            byte[] arr = new byte[b.readableBytes()];
            b.readBytes(arr);
            b.release();
            baos.writeBytes(arr);
        }
        return baos.toByteArray();
    }

    private static List<byte[]> decodeCompressedFrames(byte[] wire) throws Exception {
        List<byte[]> out = new ArrayList<>();
        int idx = 0;
        while (idx < wire.length) {
            int[] f = readVarInt(wire, idx);
            int frameLen = f[0];
            idx = f[1];
            int frameEnd = idx + frameLen;
            if (frameEnd > wire.length) {
                throw new IllegalStateException("incomplete frame");
            }
            int[] u = readVarInt(wire, idx);
            int uncompLen = u[0];
            int payloadStart = u[1];
            int payloadLen = frameEnd - payloadStart;
            if (uncompLen == 0) {
                byte[] plain = new byte[payloadLen];
                System.arraycopy(wire, payloadStart, plain, 0, payloadLen);
                out.add(plain);
            } else {
                Inflater inf = new Inflater();
                inf.setInput(wire, payloadStart, payloadLen);
                byte[] dec = new byte[uncompLen];
                int n = inf.inflate(dec);
                inf.end();
                assertEquals(uncompLen, n);
                out.add(dec);
            }
            idx = frameEnd;
        }
        return out;
    }

    private static int[] readVarInt(byte[] buf, int off) {
        int val = 0;
        int shift = 0;
        int p = off;
        while (true) {
            byte b = buf[p++];
            val |= (b & 0x7f) << shift;
            if ((b & 0x80) == 0) {
                return new int[] { val, p };
            }
            shift += 7;
        }
    }
}
