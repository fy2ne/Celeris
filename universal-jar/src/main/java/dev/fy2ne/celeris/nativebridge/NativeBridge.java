package dev.fy2ne.celeris.nativebridge;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;

public final class NativeBridge {
    public static final int CAP_NODELAY = 1 << 0;
    public static final int CAP_LOWAT = 1 << 1;
    public static final int CAP_BBR = 1 << 2;
    public static final int CAP_QUICKACK = 1 << 3;
    public static final int CAP_TCP_INFO = 1 << 4;
    public static final int CAP_SNDBUF_CLAMP = 1 << 5;
    public static final int HIST_BUCKETS = 80;

    private static volatile boolean initialized;
    private static volatile boolean loaded;
    private static volatile String loadErr;

    private NativeBridge() {}

    public static synchronized boolean ensureLoaded() {
        if (initialized) {
            return loaded;
        }
        initialized = true;

        String override = System.getProperty("celeris.native.path");
        if (override != null && !override.isBlank()) {
            try {
                System.load(Path.of(override).toAbsolutePath().toString());
                loaded = true;
                return true;
            } catch (Throwable t) {
                loadErr = "override load failed: " + t.getMessage();
            }
        }

        String target = detectPlatformDir();
        String file = libFileName();
        String resPath = "/natives/" + target + "/" + file;

        try (InputStream in = NativeBridge.class.getResourceAsStream(resPath)) {
            if (in == null) {
                loadErr = "missing bundled native: " + resPath;
                return false;
            }
            Path tmp = Files.createTempFile("celeris-native-", "-" + file);
            tmp.toFile().deleteOnExit();
            Files.copy(in, tmp, StandardCopyOption.REPLACE_EXISTING);
            System.load(tmp.toAbsolutePath().toString());
            loaded = true;
            return true;
        } catch (IOException | UnsatisfiedLinkError e) {
            loadErr = e.getClass().getSimpleName() + ": " + e.getMessage();
            return false;
        }
    }

    public static boolean isLoaded() {
        return loaded;
    }

    public static String getLoadError() {
        return loadErr;
    }

    private static String detectPlatformDir() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);

        String normOs = os.contains("win") ? "windows"
            : (os.contains("mac") || os.contains("darwin")) ? "macos"
            : "linux";

        String normArch = (arch.equals("aarch64") || arch.equals("arm64")) ? "aarch64" : "x86_64";
        return normOs + "-" + normArch;
    }

    private static String libFileName() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("win")) {
            return "celeris_core.dll";
        }
        if (os.contains("mac") || os.contains("darwin")) {
            return "libceleris_core.dylib";
        }
        return "libceleris_core.so";
    }

    public static native long createContext(int compressionThreshold, int compressionLevel, long socketFd, int lowatBytes);

    public static native void destroyContext(long ctxPtr);

    public static native void setCompression(long ctxPtr, int threshold, int level);

    public static native int enableEncryption(long ctxPtr, byte[] secretKey);

    public static native int enqueueDirectPacket(long ctxPtr, ByteBuffer directBuf, int offset, int len, int lane);

    public static native int enqueueHeapPacket(long ctxPtr, byte[] bytes, int offset, int len, int lane);

    public static native long drainWire(long ctxPtr, ByteBuffer outDirectBuf, int capacity, int maxBulkOverride);

    public static native int updateLowat(long ctxPtr, int lowatBytes);

    public static native int getSocketCaps(long ctxPtr);

    public static native int pollStats(long ctxPtr, long[] outLongs);

    public static native int pollDelayHistogram(long ctxPtr, long[] outBuckets);
}
