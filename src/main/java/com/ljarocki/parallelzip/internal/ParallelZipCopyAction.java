package com.ljarocki.parallelzip.internal;

import org.gradle.api.internal.file.copy.CopyAction;
import org.gradle.api.internal.file.copy.CopyActionProcessingStream;
import org.gradle.api.internal.file.copy.FileCopyDetailsInternal;
import org.gradle.api.tasks.WorkResult;
import org.gradle.api.tasks.WorkResults;

import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Bridges Gradle's copy pipeline to {@link ParallelZipWriter}. Gradle resolves the whole
 * {@code CopySpec} (all {@code from}/{@code into}/{@code include}/{@code exclude}/
 * {@code rename}/{@code filter}, plus duplicate handling and reproducible ordering via its
 * decorators) and streams the final files here; we hand each off to the parallel writer, so
 * the archive is built across all cores.
 *
 * <p>An entry with no content filter/transform configured is handed off as a reference to its
 * real source file, read lazily on a worker thread at compress time -- same as the streamed/
 * spilled path already does. This is safe because Gradle's own {@code FileCopyDetails} only
 * exposes the real file when no filter is present: {@code getFile()} and {@code open()} share
 * the identical guard, so whenever {@code getFile()} doesn't throw, it returns exactly the
 * bytes {@code open()} would have. A filtered entry's content is a transformed stream, not a
 * real file, so it's still consumed on this (single-threaded) stream iteration -- Gradle's
 * filter chain has to run through its own API. Small entries are buffered in memory; entries
 * over the spill threshold are copied to a temp file. Backpressure from the
 * {@link ParallelZipWriter.Sink} bounds how many entries are materialized/queued at once.</p>
 */
public final class ParallelZipCopyAction implements CopyAction {

    private final Path archive;
    private final boolean store;
    private final int level;
    private final int threads;
    private final boolean preserveTimestamps;
    private final long spillThreshold;

    public ParallelZipCopyAction(Path archive, boolean store, int level, int threads,
                                 boolean preserveTimestamps, long spillThreshold) {
        this.archive = archive;
        this.store = store;
        this.level = level;
        this.threads = threads;
        this.preserveTimestamps = preserveTimestamps;
        this.spillThreshold = spillThreshold;
    }

    @Override
    public WorkResult execute(CopyActionProcessingStream stream) {
        final Path spillDir;
        try {
            spillDir = Files.createTempDirectory(ParallelZipWriter.prepareParent(archive), ".pzip-");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        try {
            ParallelZipWriter.Sink sink =
                    new ParallelZipWriter.Sink(archive, store, level, threads, false, spillThreshold, spillDir);
            stream.process(details -> {
                try {
                    sink.add(toEntry(details, spillDir));
                } catch (IOException ex) {
                    throw new UncheckedIOException(ex);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException(ex);
                }
            });
            sink.finish();
            return WorkResults.didWork(true);
        } catch (IOException | InterruptedException ex) {
            if (ex instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new RuntimeException("parallel-zip archive failed", ex);
        } finally {
            ParallelZipWriter.deleteRecursively(spillDir);
        }
    }

    private ParallelZipWriter.Entry toEntry(FileCopyDetailsInternal details, Path spillDir) throws IOException {
        String name = details.getRelativePath().getPathString();
        int[] dt = preserveTimestamps
                ? ParallelZipWriter.dosDateTime(details.getLastModified())
                : new int[]{ParallelZipWriter.FIXED_DOS_TIME, ParallelZipWriter.FIXED_DOS_DATE};
        boolean dir = details.isDirectory();
        int extAttr = externalAttr(details, dir);
        if (dir) {
            return ParallelZipWriter.dirEntry(name, dt[0], dt[1], extAttr);
        }
        Path real = rawFileOrNull(details);
        if (real != null) {
            // No filter: defer the read to compress time, on a worker thread, instead of
            // reading it here on Gradle's single-threaded copy walk.
            return ParallelZipWriter.fileEntry(name, real, false, Files.size(real), dt[0], dt[1], extAttr);
        }
        try (InputStream in = details.open()) {
            return materialize(name, in, spillDir, dt[0], dt[1], extAttr);
        }
    }

    /** Null means a content filter is configured for this entry, so {@code open()} must be used. */
    private static Path rawFileOrNull(FileCopyDetailsInternal details) {
        try {
            return details.getFile().toPath();
        } catch (UnsupportedOperationException filtered) {
            return null;
        }
    }

    /** Reads content up to the spill threshold into memory; overflows the rest to a temp file. */
    private ParallelZipWriter.Entry materialize(String name, InputStream in, Path spillDir,
                                                int dosTime, int dosDate, int extAttr) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        byte[] chunk = new byte[64 * 1024];
        long total = 0;
        int n;
        while ((n = in.read(chunk)) >= 0) {
            if (n == 0) continue;
            if (total + n <= spillThreshold) {
                buf.write(chunk, 0, n);
                total += n;
            } else {
                Path tmp = Files.createTempFile(spillDir, "in", ".raw");
                try (OutputStream os = new BufferedOutputStream(Files.newOutputStream(tmp), 1 << 20)) {
                    buf.writeTo(os);
                    os.write(chunk, 0, n);
                    total += n;
                    byte[] more = new byte[1 << 16];
                    int m;
                    while ((m = in.read(more)) >= 0) {
                        os.write(more, 0, m);
                        total += m;
                    }
                }
                return ParallelZipWriter.fileEntry(name, tmp, true, total, dosTime, dosDate, extAttr);
            }
        }
        return ParallelZipWriter.inlineEntry(name, buf.toByteArray(), dosTime, dosDate, extAttr);
    }

    private static int externalAttr(FileCopyDetailsInternal details, boolean dir) {
        int mode;
        try {
            mode = details.getPermissions().toUnixNumeric();
        } catch (Throwable t) {
            mode = dir ? 0755 : 0644;
        }
        return (mode << 16) | (dir ? 0x10 : 0);
    }
}
