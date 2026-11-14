package dev.fy2ne.celeris.bootstrap;

import dev.fy2ne.celeris.core.CelerisEngine;
import dev.fy2ne.celeris.netty.PipelineInjector;
import net.fabricmc.api.DedicatedServerModInitializer;
import net.fabricmc.loader.api.FabricLoader;

import java.util.logging.Logger;

public final class FabricBootstrap implements DedicatedServerModInitializer {
    private static final Logger LOG = Logger.getLogger("Celeris");

    @Override
    public void onInitializeServer() {
        CelerisEngine.boot(LOG);

        // avoids hard dependency on fabric-api or versioned minecraft mappings
        Thread hookThread = new Thread(() -> {
            for (int i = 0; i < 60; i++) {
                try {
                    Object server = FabricLoader.getInstance().getGameInstance();
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
        }, "celeris-fabric-hook");
        hookThread.setDaemon(true);
        hookThread.start();

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            PipelineInjector.unhookServerChannels();
            CelerisEngine.shutdown();
        }, "celeris-shutdown"));
    }
}
