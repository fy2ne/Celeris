package dev.fy2ne.celeris.bootstrap;

import dev.fy2ne.celeris.core.CelerisEngine;
import dev.fy2ne.celeris.netty.PipelineInjector;
import net.neoforged.fml.common.Mod;

import java.lang.reflect.Method;
import java.util.logging.Logger;

@Mod("celeris")
public final class NeoForgeBootstrap {
    private static final Logger LOG = Logger.getLogger("Celeris");

    public NeoForgeBootstrap() {
        CelerisEngine.boot(LOG);

        Thread hookThread = new Thread(() -> {
            for (int i = 0; i < 80; i++) {
                try {
                    Object server = fetchNeoForgeServer();
                    if (server != null) {
                        int hooked = PipelineInjector.hookServerChannelsFromContainer(server, LOG);
                        if (hooked > 0) {
                            return;
                        }
                    }
                    Thread.sleep(500);
                } catch (InterruptedException ignored) {
                    return;
                } catch (Throwable ignored) {
                }
            }
        }, "celeris-neoforge-hook");
        hookThread.setDaemon(true);
        hookThread.start();
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            PipelineInjector.unhookServerChannels();
            CelerisEngine.shutdown();
        }, "celeris-shutdown"));
    }

    private static Object fetchNeoForgeServer() {
        for (String clsName : new String[]{
            "net.neoforged.neoforge.server.ServerLifecycleHooks",
            "net.minecraftforge.server.ServerLifecycleHooks"
        }) {
            try {
                Class<?> cls = Class.forName(clsName);
                Method m = cls.getMethod("getCurrentServer");
                Object srv = m.invoke(null);
                if (srv != null) return srv;
            } catch (Throwable ignored) {
            }
        }
        return null;
    }
}
