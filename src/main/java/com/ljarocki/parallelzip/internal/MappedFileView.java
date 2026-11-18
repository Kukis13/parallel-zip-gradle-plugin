package com.ljarocki.parallelzip.internal;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;

/**
 * Direct {@link ByteBuffer} view over a memory-mapped {@link FileChannel} with explicit
 * deterministic unmap on {@link #close()}.
 *
 * <p>This base (JDK 17–21) implementation maps via {@link FileChannel#map} and releases the
 * mapping via {@code sun.misc.Unsafe#invokeCleaner}. On JDK 22+, the multi-release class in
 * {@code META-INF/versions/22} replaces this with {@code java.lang.foreign.Arena} and
 * {@code MemorySegment}.</p>
 */
final class MappedFileView implements AutoCloseable {

    private final MappedByteBuffer buffer;

    private MappedFileView(MappedByteBuffer buffer) {
        this.buffer = buffer;
    }

    static MappedFileView open(FileChannel ch, long size) throws IOException {
        return new MappedFileView(ch.map(FileChannel.MapMode.READ_ONLY, 0, size));
    }

    ByteBuffer buffer() {
        return buffer;
    }

    @Override
    public void close() {
        unmap(buffer);
    }

    private static void unmap(MappedByteBuffer buffer) {
        if (buffer == null) return;
        try {
            Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
            Field theUnsafe = unsafeClass.getDeclaredField("theUnsafe");
            theUnsafe.setAccessible(true);
            Object unsafe = theUnsafe.get(null);
            Method invokeCleaner = unsafeClass.getMethod("invokeCleaner", ByteBuffer.class);
            invokeCleaner.invoke(unsafe, buffer);
        } catch (Throwable ignored) {
            // Fall back silently: the mapping is released on GC's schedule.
        }
    }
}
