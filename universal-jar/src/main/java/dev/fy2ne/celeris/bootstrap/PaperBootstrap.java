package dev.fy2ne.celeris.bootstrap;

import dev.fy2ne.celeris.core.CelerisEngine;
import dev.fy2ne.celeris.netty.PipelineInjector;
import io.netty.channel.Channel;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;

public final class PaperBootstrap extends JavaPlugin implements CommandExecutor, TabCompleter, Listener {
    @Override
    public void onEnable() {
        CelerisEngine.boot(getLogger());
        hookBukkitServer();

        var cmd = getCommand("celeris");
        if (cmd != null) {
            cmd.setExecutor(this);
            cmd.setTabCompleter(this);
        }
        Bukkit.getPluginManager().registerEvents(this, this);
    }

    @Override
    public void onDisable() {
        PipelineInjector.unhookServerChannels();
        CelerisEngine.shutdown();
    }

    private void hookBukkitServer() {
        try {
            Method getServer = Bukkit.getServer().getClass().getMethod("getServer");
            Object mcServer = getServer.invoke(Bukkit.getServer());
            PipelineInjector.hookServerChannelsFromContainer(mcServer, getLogger());
        } catch (Throwable t) {
            getLogger().warning("celeris server hook skipped: " + t.getMessage());
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPlayerJoin(PlayerJoinEvent ev) {
        // catches players already connected before a /reload
        Channel ch = extractPlayerChannel(ev.getPlayer());
        if (ch != null) {
            PipelineInjector.injectChannel(ch);
        }
    }

    private static Channel extractPlayerChannel(Player p) {
        try {
            Object handle = p.getClass().getMethod("getHandle").invoke(p);
            if (handle == null) return null;
            for (Field f : handle.getClass().getFields()) {
                if (f.getType().getSimpleName().contains("Listener") || f.getType().getSimpleName().contains("Connection")) {
                    Object conn = f.get(handle);
                    Channel found = findChannelField(conn, 0);
                    if (found != null) return found;
                }
            }
            for (Field f : handle.getClass().getDeclaredFields()) {
                f.setAccessible(true);
                Object sub = f.get(handle);
                if (sub != null && sub.getClass().getName().contains("network")) {
                    Channel found = findChannelField(sub, 0);
                    if (found != null) return found;
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static Channel findChannelField(Object obj, int depth) {
        if (obj == null || depth > 2) return null;
        if (obj instanceof Channel ch) return ch;
        for (Class<?> c = obj.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (Channel.class.isAssignableFrom(f.getType())) {
                    try {
                        f.setAccessible(true);
                        Object v = f.get(obj);
                        if (v instanceof Channel ch) return ch;
                    } catch (Throwable ignored) {
                    }
                } else if (f.getType().getName().contains("Connection")) {
                    try {
                        f.setAccessible(true);
                        Channel nested = findChannelField(f.get(obj), depth + 1);
                        if (nested != null) return nested;
                    } catch (Throwable ignored) {
                    }
                }
            }
        }
        return null;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0 || args[0].equalsIgnoreCase("stats")) {
            var s = CelerisEngine.snapshotStats();
            String rtt = s.avgKernelRttMs() >= 0 ? String.format(Locale.ROOT, "%.1f ms", s.avgKernelRttMs()) : "n/a";
            String saved = String.format(Locale.ROOT, "%.1f%%", s.compressionSavedPct());
            String state = s.enabled() ? "active" : "bypass";
            sender.sendMessage("§bCeleris v" + CelerisEngine.VERSION + " §7| mode=" + CelerisEngine.engineMode() + " state=" + state + " channels=" + s.channels());
            sender.sendMessage("§7express=" + s.expressPackets() + " bulk=" + s.bulkPackets() + " preempted=" + s.preemptedPackets() + " backpressure=" + s.deferredDrains() + " overflows=" + s.queueOverflows() + " bypassed=" + s.bypassedPackets());
            sender.sendMessage("§7expressDelayUs[n=" + s.expressSamples() + " avg=" + s.expressAvgUs() + " p50=" + s.expressP50Us() + " p95=" + s.expressP95Us() + " p99=" + s.expressP99Us() + " max=" + s.expressMaxUs() + "]");
            sender.sendMessage("§7bulkHoldUs[n=" + s.bulkHoldSamples() + " avg=" + s.bulkHoldAvgUs() + " max=" + s.bulkHoldMaxUs() + "]");
            sender.sendMessage("§7raw=" + (s.rawBytes() / 1024) + "KB wire=" + (s.wireBytes() / 1024) + "KB (" + saved + " saved) rtt=" + rtt);
            sender.sendMessage("§7socket=[" + s.capsSummary() + "]");
            return true;
        }
        if (args[0].equalsIgnoreCase("bench")) {
            if (!CelerisEngine.tryAcquireBench()) {
                sender.sendMessage("§eceleris bench already running");
                return true;
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
                    String line1 = "bench[" + b.mode() + "] burst=" + b.burstKb() + "KB iters=" + b.iterations()
                        + " fifoUs[p50=" + b.fifoP50Us() + " p95=" + b.fifoP95Us() + " max=" + b.fifoMaxUs() + "]"
                        + " celerisUs[p50=" + b.celerisP50Us() + " p95=" + b.celerisP95Us() + " p99=" + b.celerisP99Us() + " max=" + b.celerisMaxUs() + "]";
                    String line2 = "benchBulkHoldUs[avg=" + b.bulkHoldAvgUs() + " max=" + b.bulkHoldMaxUs() + "] preempted=" + b.preempted() + " saved=" + saved;
                    sender.sendMessage("§b" + line1);
                    sender.sendMessage("§7" + line2);
                    getLogger().info("[bench] " + line1 + " " + line2);
                } finally {
                    CelerisEngine.releaseBench();
                }
            });
            return true;
        }
        if (args[0].equalsIgnoreCase("toggle")) {
            boolean next = !CelerisEngine.isEnabled();
            CelerisEngine.setEnabled(next);
            sender.sendMessage(next ? "§aceleris active" : "§eceleris bypassed (zero-overhead)");
            getLogger().info("celeris state=" + (next ? "active" : "bypass"));
            return true;
        }
        if (args[0].equalsIgnoreCase("debug")) {
            var lines = CelerisEngine.inspectPipelines();
            sender.sendMessage("§bCeleris Debug §7| mode=" + CelerisEngine.engineMode() + " state=" + (CelerisEngine.isEnabled() ? "active" : "bypass") + " active=" + lines.size());
            if (lines.isEmpty()) {
                sender.sendMessage("§7no active player channels injected yet");
            } else {
                for (String line : lines) {
                    sender.sendMessage("§7" + line);
                    getLogger().info("[debug] " + line);
                }
            }
            return true;
        }
        if (args[0].equalsIgnoreCase("test")) {
            var res = CelerisEngine.runSelfTest();
            String badge = res.ok() ? "§aPASS" : "§cFAIL";
            sender.sendMessage(badge + " §7[" + res.mode() + "] " + res.summary());
            getLogger().info("[selftest] ok=" + res.ok() + " mode=" + res.mode() + " " + res.summary());
            return true;
        }
        if (args[0].equalsIgnoreCase("ping")) {
            Player target = args.length >= 2 ? Bukkit.getPlayerExact(args[1]) : (sender instanceof Player p ? p : null);
            if (target == null) {
                sender.sendMessage("§cspecify an online player: /celeris ping <player>");
                return true;
            }
            var s = CelerisEngine.snapshotStats();
            String kRtt = s.avgKernelRttMs() >= 0 ? String.format(Locale.ROOT, "%.1f ms", s.avgKernelRttMs()) : "n/a";
            sender.sendMessage("§b" + target.getName() + " §7mcPing=" + target.getPing() + "ms kernelRtt=" + kRtt + " expP99=" + s.expressP99Us() + "us preempted=" + s.preemptedPackets() + " mode=" + CelerisEngine.engineMode());
            return true;
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("lowat")) {
            try {
                int v = Integer.parseInt(args[1]);
                CelerisEngine.setLowatBytes(v);
                sender.sendMessage("§aceleris lowat set to " + CelerisEngine.lowatBytes() + " bytes (live + new)");
            } catch (NumberFormatException e) {
                sender.sendMessage("§cinvalid byte count");
            }
            return true;
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("level")) {
            try {
                int v = Integer.parseInt(args[1]);
                CelerisEngine.setCompressionLevel(v);
                sender.sendMessage("§aceleris zlib level set to " + CelerisEngine.compressionLevel());
            } catch (NumberFormatException e) {
                sender.sendMessage("§cinvalid level (0-9)");
            }
            return true;
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("bulk")) {
            try {
                int v = Integer.parseInt(args[1]);
                CelerisEngine.setMaxBulkPerFlush(v);
                String capTxt = CelerisEngine.maxBulkPerFlush() == 0 ? "auto (tcp_info)" : CelerisEngine.maxBulkPerFlush() + " bytes";
                sender.sendMessage("§aceleris bulk flush cap set to " + capTxt);
            } catch (NumberFormatException e) {
                sender.sendMessage("§cinvalid byte count (0 = auto)");
            }
            return true;
        }
        sender.sendMessage("§7usage: /celeris [stats | bench [kb] [iters] | debug | test | toggle | ping [player] | lowat <bytes> | level <0-9> | bulk <bytes|0>]");
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            List<String> opts = new ArrayList<>();
            for (String s : List.of("stats", "bench", "debug", "test", "toggle", "ping", "lowat", "level", "bulk")) {
                if (s.startsWith(args[0].toLowerCase(Locale.ROOT))) opts.add(s);
            }
            return opts;
        }
        return List.of();
    }
}
