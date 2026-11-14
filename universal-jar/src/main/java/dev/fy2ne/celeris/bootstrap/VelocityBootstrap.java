package dev.fy2ne.celeris.bootstrap;

import com.google.inject.Inject;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.PreLoginEvent;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.proxy.ProxyServer;
import dev.fy2ne.celeris.core.CelerisEngine;
import dev.fy2ne.celeris.netty.PipelineInjector;
import io.netty.channel.Channel;
import net.kyori.adventure.text.Component;

import java.lang.reflect.Field;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;

@Plugin(
    id = "celeris",
    name = "Celeris",
    version = CelerisEngine.VERSION,
    authors = {"fy2ne"}
)
public final class VelocityBootstrap {
    private final ProxyServer proxy;
    private final Logger julLog;

    @Inject
    public VelocityBootstrap(ProxyServer proxy, org.slf4j.Logger slfLog) {
        this.proxy = proxy;
        this.julLog = Logger.getLogger("Celeris");
    }

    @Subscribe
    public void onProxyInit(ProxyInitializeEvent ev) {
        CelerisEngine.boot(julLog);
        PipelineInjector.hookServerChannelsFromContainer(proxy, julLog);

        var cm = proxy.getCommandManager();
        var meta = cm.metaBuilder("celeris").plugin(this).build();
        cm.register(meta, new VelocityStatsCmd());
    }

    @Subscribe
    public void onPreLogin(PreLoginEvent ev) {
        Channel ch = findChannelInObject(ev.getConnection(), 0);
        if (ch != null) {
            PipelineInjector.injectChannel(ch);
        }
    }

    @Subscribe
    public void onProxyShutdown(ProxyShutdownEvent ev) {
        PipelineInjector.unhookServerChannels();
        CelerisEngine.shutdown();
    }

    private static Channel findChannelInObject(Object obj, int depth) {
        if (obj == null || depth > 2) return null;
        if (obj instanceof Channel ch) return ch;
        for (Class<?> c = obj.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (Channel.class.isAssignableFrom(f.getType())) {
                    try {
                        f.setAccessible(true);
                        if (f.get(obj) instanceof Channel ch) return ch;
                    } catch (Throwable ignored) {
                    }
                } else if (f.getType().getName().contains("MinecraftConnection")) {
                    try {
                        f.setAccessible(true);
                        Channel nested = findChannelInObject(f.get(obj), depth + 1);
                        if (nested != null) return nested;
                    } catch (Throwable ignored) {
                    }
                }
            }
        }
        return null;
    }

    private static final class VelocityStatsCmd implements SimpleCommand {
        @Override
        public void execute(Invocation inv) {
            String[] args = inv.arguments();
            if (args.length > 0 && args[0].equalsIgnoreCase("bench")) {
                if (!CelerisEngine.tryAcquireBench()) {
                    inv.source().sendMessage(Component.text("celeris bench already running"));
                    return;
                }
                int kb = 256;
                int iters = 20;
                if (args.length >= 2) {
                    try { kb = Integer.parseInt(args[1]); } catch (NumberFormatException ignored) {}
                }
                if (args.length >= 3) {
                    try { iters = Integer.parseInt(args[2]); } catch (NumberFormatException ignored) {}
                }
                final int fKb = kb;
                final int fIters = iters;
                CompletableFuture.runAsync(() -> {
                    try {
                        var b = CelerisEngine.runBench(fKb, fIters);
                        String saved = String.format(Locale.ROOT, "%.1f%%", b.savedPct());
                        inv.source().sendMessage(Component.text(
                            "bench[" + b.mode() + "] burst=" + b.burstKb() + "KB iters=" + b.iterations()
                                + " fifoUs[p50=" + b.fifoP50Us() + " p95=" + b.fifoP95Us() + " max=" + b.fifoMaxUs() + "]"
                                + " celerisUs[p50=" + b.celerisP50Us() + " p95=" + b.celerisP95Us() + " p99=" + b.celerisP99Us() + " max=" + b.celerisMaxUs() + "]"
                                + " bulkHoldUs[avg=" + b.bulkHoldAvgUs() + " max=" + b.bulkHoldMaxUs() + "] preempted=" + b.preempted() + " saved=" + saved
                        ));
                    } finally {
                        CelerisEngine.releaseBench();
                    }
                });
                return;
            }
            if (args.length > 0 && args[0].equalsIgnoreCase("toggle")) {
                boolean next = !CelerisEngine.isEnabled();
                CelerisEngine.setEnabled(next);
                inv.source().sendMessage(Component.text("celeris state=" + (next ? "active" : "bypass")));
                return;
            }
            if (args.length > 0 && args[0].equalsIgnoreCase("debug")) {
                var lines = CelerisEngine.inspectPipelines();
                inv.source().sendMessage(Component.text("celeris debug mode=" + CelerisEngine.engineMode() + " active=" + lines.size()));
                for (String line : lines) {
                    inv.source().sendMessage(Component.text(line));
                }
                return;
            }
            if (args.length > 0 && args[0].equalsIgnoreCase("test")) {
                var res = CelerisEngine.runSelfTest();
                inv.source().sendMessage(Component.text((res.ok() ? "PASS" : "FAIL") + " [" + res.mode() + "] " + res.summary()));
                return;
            }

            var s = CelerisEngine.snapshotStats();
            String rtt = s.avgKernelRttMs() >= 0 ? String.format(Locale.ROOT, "%.1f ms", s.avgKernelRttMs()) : "n/a";
            String saved = String.format(Locale.ROOT, "%.1f%%", s.compressionSavedPct());
            inv.source().sendMessage(Component.text(
                "Celeris v" + CelerisEngine.VERSION + " | mode=" + CelerisEngine.engineMode()
                    + " state=" + (s.enabled() ? "active" : "bypass")
                    + " channels=" + s.channels()
                    + " preempted=" + s.preemptedPackets()
                    + " deferred=" + s.deferredDrains()
                    + " overflows=" + s.queueOverflows()
                    + " bypassed=" + s.bypassedPackets()
                    + " expUs[p50=" + s.expressP50Us() + " p95=" + s.expressP95Us() + " p99=" + s.expressP99Us() + " max=" + s.expressMaxUs() + "]"
                    + " bulkHoldUs[avg=" + s.bulkHoldAvgUs() + " max=" + s.bulkHoldMaxUs() + "]"
                    + " saved=" + saved
                    + " rtt=" + rtt
                    + " [" + s.capsSummary() + "]"
            ));
        }

        @Override
        public boolean hasPermission(Invocation inv) {
            return inv.source().hasPermission("celeris.admin");
        }
    }
}
