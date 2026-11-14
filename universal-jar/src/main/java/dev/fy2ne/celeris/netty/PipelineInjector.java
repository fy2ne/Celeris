package dev.fy2ne.celeris.netty;

import dev.fy2ne.celeris.core.CelerisEngine;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.ServerChannel;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collection;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;

public final class PipelineInjector {
    private static final String ACCEPTOR_NAME = "celeris-acceptor";
    private static final String PROBE_NAME = "celeris-probe";

    private static final Set<Channel> HOOKED_SERVERS = Collections.newSetFromMap(new IdentityHashMap<>());

    private PipelineInjector() {}

    public static void injectChannel(Channel ch) {
        if (ch == null || !CelerisEngine.isEnabled()) {
            return;
        }
        if (ch.eventLoop().inEventLoop()) {
            doInjectChannel(ch);
        } else {
            ch.eventLoop().execute(() -> doInjectChannel(ch));
        }
    }

    private static void doInjectChannel(Channel ch) {
        if (ch == null || !ch.isOpen() || !ch.isActive()) {
            return;
        }
        ChannelPipeline pipe = ch.pipeline();
        if (pipe.get(CelerisChannelHandler.WIRE_HANDLER) != null) {
            return;
        }
        String enc = findEncoderName(pipe);
        if (enc != null) {
            attachPair(pipe, enc);
            return;
        }
        // during early initChannel, encoder might be added right after our hook runs
        if (pipe.get(PROBE_NAME) == null) {
            pipe.addLast(PROBE_NAME, new ChannelInboundHandlerAdapter() {
                @Override
                public void channelActive(ChannelHandlerContext ctx) throws Exception {
                    tryAttachAndRemoveProbe(ctx);
                    super.channelActive(ctx);
                }

                @Override
                public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
                    tryAttachAndRemoveProbe(ctx);
                    super.channelRead(ctx, msg);
                }

                private void tryAttachAndRemoveProbe(ChannelHandlerContext ctx) {
                    String found = findEncoderName(ctx.pipeline());
                    if (found != null) {
                        attachPair(ctx.pipeline(), found);
                        if (ctx.pipeline().get(PROBE_NAME) != null) {
                            ctx.pipeline().remove(PROBE_NAME);
                        }
                    }
                }
            });
        }
    }

    private static void attachPair(ChannelPipeline pipe, String encoderName) {
        if (pipe.get(CelerisChannelHandler.WIRE_HANDLER) != null) {
            if (pipe.get(PROBE_NAME) != null) {
                pipe.remove(PROBE_NAME);
            }
            return;
        }
        CelerisChannelHandler handler = new CelerisChannelHandler();
        pipe.addBefore(encoderName, CelerisChannelHandler.WIRE_HANDLER, handler);
        pipe.addAfter(encoderName, CelerisChannelHandler.TAG_HANDLER, handler.newTagHandler());
        if (pipe.get(PROBE_NAME) != null) {
            pipe.remove(PROBE_NAME);
        }
    }

    private static String findEncoderName(ChannelPipeline pipe) {
        if (pipe.get("encoder") != null) return "encoder";
        if (pipe.get("minecraft-encoder") != null) return "minecraft-encoder";
        return null;
    }

    public static int hookServerChannelsFromContainer(Object rootObj, Logger log) {
        if (rootObj == null) {
            return 0;
        }
        int hooked = 0;
        Set<Object> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        hooked += scanObjectForServerChannels(rootObj, visited, 0);
        if (hooked > 0 && log != null) {
            log.info("celeris hooked " + hooked + " listener channel(s)");
        }
        return hooked;
    }

    private static int scanObjectForServerChannels(Object obj, Set<Object> visited, int depth) {
        if (obj == null || depth > 3 || !visited.add(obj)) {
            return 0;
        }
        if (obj instanceof ServerChannel sc) {
            return hookListenerChannel(sc) ? 1 : 0;
        }
        if (obj instanceof ChannelFuture cf) {
            return hookListenerChannel(cf.channel()) ? 1 : 0;
        }
        if (obj instanceof Collection<?> col) {
            int count = 0;
            for (Object item : col) {
                if (item instanceof ChannelFuture cf) {
                    if (hookListenerChannel(cf.channel())) {
                        count++;
                    }
                } else if (item instanceof ServerChannel sc) {
                    if (hookListenerChannel(sc)) {
                        count++;
                    }
                } else if (item != null && isNetworkType(item.getClass().getName())) {
                    count += scanObjectForServerChannels(item, visited, depth + 1);
                }
            }
            return count;
        }
        if (obj instanceof Map<?, ?> map) {
            int count = 0;
            for (Object item : map.values()) {
                if (item instanceof ServerChannel sc) {
                    if (hookListenerChannel(sc)) {
                        count++;
                    }
                } else if (item instanceof ChannelFuture cf) {
                    if (hookListenerChannel(cf.channel())) {
                        count++;
                    }
                }
            }
            return count;
        }

        int count = 0;
        for (Class<?> c = obj.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                Class<?> ft = f.getType();
                if (!shouldFollowField(ft, depth)) {
                    continue;
                }
                try {
                    f.setAccessible(true);
                    Object val = f.get(obj);
                    count += scanObjectForServerChannels(val, visited, depth + 1);
                } catch (Throwable ignored) {
                }
            }
            // also probe zero-arg connection getters on paper/fabric MinecraftServer
            for (Method m : c.getDeclaredMethods()) {
                String rn = m.getReturnType().getName();
                if (m.getParameterCount() == 0 && (rn.contains("ServerConnection") || (depth == 0 && rn.startsWith("net.minecraft.class_")))) {
                    try {
                        m.setAccessible(true);
                        Object conn = m.invoke(obj);
                        count += scanObjectForServerChannels(conn, visited, depth + 1);
                    } catch (Throwable ignored) {
                    }
                }
            }
        }
        return count;
    }

    private static boolean isNetworkType(String name) {
        return name.contains("network") || name.contains("Connection") || name.startsWith("net.minecraft.class_");
    }

    private static boolean shouldFollowField(Class<?> ft, int depth) {
        if (ft.isPrimitive() || ft == String.class) {
            return false;
        }
        if (Collection.class.isAssignableFrom(ft)
            || Map.class.isAssignableFrom(ft)
            || ChannelFuture.class.isAssignableFrom(ft)
            || ServerChannel.class.isAssignableFrom(ft)) {
            return true;
        }
        String fn = ft.getName();
        if (fn.contains("Connection") || fn.contains("Network")) {
            return true;
        }
        // fabric intermediary ServerConnectionListener is net.minecraft.class_3242
        return depth == 0 && fn.startsWith("net.minecraft.class_");
    }

    private static synchronized boolean hookListenerChannel(Channel serverCh) {
        HOOKED_SERVERS.removeIf(s -> !s.isOpen());
        if (serverCh == null || !HOOKED_SERVERS.add(serverCh)) {
            return false;
        }
        ChannelPipeline pipe = serverCh.pipeline();
        if (pipe.get(ACCEPTOR_NAME) != null) {
            return false;
        }
        pipe.addFirst(ACCEPTOR_NAME, new ChannelInboundHandlerAdapter() {
            @Override
            public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
                if (msg instanceof Channel child) {
                    // add an initializer so our probe runs right after ServerBootstrapAcceptor finishes
                    child.pipeline().addFirst(new ChannelInitializer<Channel>() {
                        @Override
                        protected void initChannel(Channel ch) {
                            ch.eventLoop().execute(() -> injectChannel(ch));
                        }
                    });
                }
                super.channelRead(ctx, msg);
            }
        });
        return true;
    }

    public static synchronized void unhookServerChannels() {
        for (Channel ch : HOOKED_SERVERS) {
            if (ch.isOpen()) {
                ch.eventLoop().execute(() -> {
                    ChannelHandler h = ch.pipeline().get(ACCEPTOR_NAME);
                    if (h != null) {
                        ch.pipeline().remove(ACCEPTOR_NAME);
                    }
                });
            }
        }
        HOOKED_SERVERS.clear();
    }
}
