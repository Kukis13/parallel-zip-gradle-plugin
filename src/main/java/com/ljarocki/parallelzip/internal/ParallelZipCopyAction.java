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
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;

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
    private final boolean skipAlreadyCompressed;
    private final int level;
    private final int threads;
    private final boolean preserveTimestamps;
    private final long spillThreshold;
    private final boolean pinManifestFirst;
    private final ExecutorService sharedPool;

    public ParallelZipCopyAction(Path archive, boolean store, boolean skipAlreadyCompressed, int level, int threads,
                                 boolean preserveTimestamps, long spillThreshold) {
        this(archive, store, skipAlreadyCompressed, level, threads, preserveTimestamps, spillThreshold, false, null);
    }

    public ParallelZipCopyAction(Path archive, boolean store, boolean skipAlreadyCompressed, int level, int threads,
                                 boolean preserveTimestamps, long spillThreshold, boolean pinManifestFirst) {
        this(archive, store, skipAlreadyCompressed, level, threads, preserveTimestamps, spillThreshold,
                pinManifestFirst, null);
    }

    public ParallelZipCopyAction(Path archive, boolean store, boolean skipAlreadyCompressed, int level, int threads,
                                 boolean preserveTimestamps, long spillThreshold, boolean pinManifestFirst,
                                 ExecutorService sharedPool) {
        this.archive = archive;
        this.store = store;
        this.skipAlreadyCompressed = skipAlreadyCompressed;
        this.level = level;
        this.threads = threads;
        this.preserveTimestamps = preserveTimestamps;
        this.spillThreshold = spillThreshold;
        this.pinManifestFirst = pinManifestFirst;
        this.sharedPool = sharedPool;
    }

    @Override
    public WorkResult execute(CopyActionProcessingStream stream) {
        final Path spillParent;
        try {
            spillParent = ParallelZipWriter.prepareParent(archive);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        try (ParallelZipWriter.LazySpillDir spillDir = new ParallelZipWriter.LazySpillDir(spillParent)) {
            ParallelZipWriter.Sink sink = new ParallelZipWriter.Sink(
                    archive, store, skipAlreadyCompressed, level, threads, false, spillThreshold, spillDir, sharedPool);
            if (!pinManifestFirst) {
                stream.process(details -> addEntry(sink, toEntrySafe(details, spillDir)));
            } else {
                List<ParallelZipWriter.Entry> deferred = new ArrayList<>();
                final ParallelZipWriter.Entry[] metaInfDir = new ParallelZipWriter.Entry[1];
                final boolean[] manifestFlushed = new boolean[1];
                stream.process(details -> {
                    ParallelZipWriter.Entry entry = toEntrySafe(details, spillDir);
                    if (manifestFlushed[0]) {
                        addEntry(sink, entry);
                        return;
                    }
                    if ("META-INF".equals(entry.name) || "META-INF/".equals(entry.name)) {
                        metaInfDir[0] = entry;
                    } else if ("META-INF/MANIFEST.MF".equals(entry.name)) {
                        if (metaInfDir[0] != null) {
                            addEntry(sink, metaInfDir[0]);
                            metaInfDir[0] = null;
                        }
                        addEntry(sink, entry);
                        for (ParallelZipWriter.Entry d : deferred) {
                            addEntry(sink, d);
                        }
                        deferred.clear();
                        manifestFlushed[0] = true;
                    } else {
                        deferred.add(entry);
                    }
                });
                if (!manifestFlushed[0]) {
                    if (metaInfDir[0] != null) {
                        addEntry(sink, metaInfDir[0]);
                    }
                    for (ParallelZipWriter.Entry d : deferred) {
                        addEntry(sink, d);
                    }
                }
            }
            sink.finish();
            return WorkResults.didWork(true);
        } catch (IOException | InterruptedException ex) {
            if (ex instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new RuntimeException("parallel-zip archive failed", ex);
        }
    }

    private ParallelZipWriter.Entry toEntrySafe(FileCopyDetailsInternal details,
                                                java.util.function.Supplier<Path> spillDir) {
        try {
            return toEntry(details, spillDir);
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    private static void addEntry(ParallelZipWriter.Sink sink, ParallelZipWriter.Entry entry) {
        try {
            sink.add(entry);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(ex);
        }
    }

    private ParallelZipWriter.Entry toEntry(FileCopyDetailsInternal details,
                                            java.util.function.Supplier<Path> spillDir) throws IOException {
        String name = details.getRelativePath().getPathString();
        int dosTime;
        int dosDate;
        if (preserveTimestamps) {
            long packed = ParallelZipWriter.dosDateTimePacked(details.getLastModified());
            dosTime = (int) (packed >>> 32);
            dosDate = (int) packed;
        } else {
            dosTime = ParallelZipWriter.FIXED_DOS_TIME;
            dosDate = ParallelZipWriter.FIXED_DOS_DATE;
        }
        boolean dir = details.isDirectory();
        int extAttr = externalAttr(details, dir);
        if (dir) {
            return ParallelZipWriter.dirEntry(name, dosTime, dosDate, extAttr);
        }
        Path real = rawFileOrNull(details);
        if (real != null) {
            // No filter: defer the read to compress time, on a worker thread, instead of
            // reading it here on Gradle's single-threaded copy walk.
            return ParallelZipWriter.fileEntry(name, real, false, details.getSize(), dosTime, dosDate, extAttr);
        }
        try (InputStream in = details.open()) {
            return materialize(name, in, spillDir, dosTime, dosDate, extAttr);
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
    private ParallelZipWriter.Entry materialize(String name, InputStream in,
                                                java.util.function.Supplier<Path> spillDir,
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
                Path tmp = Files.createTempFile(spillDir.get(), "in", ".raw");
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
