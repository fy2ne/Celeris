package dev.fy2ne.celeris.nativebridge;

import io.netty.channel.Channel;
import io.netty.channel.socket.nio.NioSocketChannel;

import java.io.FileDescriptor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.channels.SelectableChannel;

public final class SocketFdExtractor {
    private static volatile boolean nioReflectionFailed;
    private static Field nioFdField;
    private static Field fdIntField;
    private static Field fdHandleField;

    private SocketFdExtractor() {}

    public static long extract(Channel ch) {
        if (ch == null) {
            return -1L;
        }
        // epoll, io_uring, and kqueue unix channels expose public fd() without module opens
        try {
            Method fdMethod = ch.getClass().getMethod("fd");
            Object fdObj = fdMethod.invoke(ch);
            if (fdObj != null) {
                Method intVal = fdObj.getClass().getMethod("intValue");
                Object raw = intVal.invoke(fdObj);
                if (raw instanceof Integer i) {
                    return i.longValue();
                }
            }
        } catch (ReflectiveOperationException ignored) {
        }

        if (ch instanceof NioSocketChannel nio && !nioReflectionFailed) {
            return extractNio(nio);
        }
        return -1L;
    }

    private static long extractNio(NioSocketChannel nio) {
        try {
            Method javaChMethod = NioSocketChannel.class.getDeclaredMethod("javaChannel");
            javaChMethod.setAccessible(true);
            SelectableChannel sc = (SelectableChannel) javaChMethod.invoke(nio);
            if (sc == null) {
                return -1L;
            }
            if (nioFdField == null) {
                nioFdField = sc.getClass().getDeclaredField("fd");
                nioFdField.setAccessible(true);
                fdIntField = FileDescriptor.class.getDeclaredField("fd");
                fdIntField.setAccessible(true);
                try {
                    fdHandleField = FileDescriptor.class.getDeclaredField("handle");
                    fdHandleField.setAccessible(true);
                } catch (NoSuchFieldException ignored) {
                }
            }
            FileDescriptor fd = (FileDescriptor) nioFdField.get(sc);
            if (fd == null) {
                return -1L;
            }
            if (fdHandleField != null) {
                long handle = fdHandleField.getLong(fd);
                if (handle != -1L && handle != 0L) {
                    return handle;
                }
            }
            int unixFd = fdIntField.getInt(fd);
            return unixFd >= 0 ? unixFd : -1L;
        } catch (Throwable t) {
            // java 17+ strong encapsulation blocks sun.nio.ch unless --add-opens is passed; fall back gracefully
            nioReflectionFailed = true;
            return -1L;
        }
    }
}
