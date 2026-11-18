package com.ljarocki.parallelzip.internal;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;

/**
 * JDK 22+ Multi-Release implementation of {@link MappedFileView} backed by
 * {@link Arena#ofConfined()} and {@link MemorySegment}, avoiding {@code sun.misc.Unsafe}
 * completely.
 */
final class MappedFileView implements AutoCloseable {

    private final Arena arena;
    private final ByteBuffer buffer;

    private MappedFileView(Arena arena, ByteBuffer buffer) {
        this.arena = arena;
        this.buffer = buffer;
    }

    static MappedFileView open(FileChannel ch, long size) throws IOException {
        Arena arena = Arena.ofConfined();
        try {
            MemorySegment segment = ch.map(FileChannel.MapMode.READ_ONLY, 0, size, arena);
            return new MappedFileView(arena, segment.asByteBuffer());
        } catch (Throwable t) {
            arena.close();
            throw t;
        }
    }

    ByteBuffer buffer() {
        return buffer;
    }

    @Override
    public void close() {
        arena.close();
    }
}
