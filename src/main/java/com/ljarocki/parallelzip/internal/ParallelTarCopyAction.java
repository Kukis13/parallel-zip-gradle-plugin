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
import java.util.concurrent.ExecutorService;

/**
 * Bridges Gradle's {@code CopyActionProcessingStream} to {@link ParallelTarWriter},
 * supporting both uncompressed {@code .tar} and pigz-style parallel block {@code .tar.gz}.
 */
public final class ParallelTarCopyAction implements CopyAction {

    private final Path archive;
    private final boolean gzip;
    private final int level;
    private final int threads;
    private final int blockSizeBytes;
    private final boolean preserveTimestamps;
    private final long spillThreshold;
    private final ExecutorService sharedPool;

    public ParallelTarCopyAction(Path archive, boolean gzip, int level, int threads,
                                 int blockSizeBytes, boolean preserveTimestamps, long spillThreshold) {
        this(archive, gzip, level, threads, blockSizeBytes, preserveTimestamps, spillThreshold, null);
    }

    public ParallelTarCopyAction(Path archive, boolean gzip, int level, int threads,
                                 int blockSizeBytes, boolean preserveTimestamps, long spillThreshold,
                                 ExecutorService sharedPool) {
        this.archive = archive;
        this.gzip = gzip;
        this.level = level;
        this.threads = threads;
        this.blockSizeBytes = blockSizeBytes;
        this.preserveTimestamps = preserveTimestamps;
        this.spillThreshold = spillThreshold;
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
        try (ParallelZipWriter.LazySpillDir spillDir = new ParallelZipWriter.LazySpillDir(spillParent);
             ParallelTarWriter.Sink sink = new ParallelTarWriter.Sink(
                     archive, gzip, level, threads, blockSizeBytes, sharedPool)) {
            stream.process(details -> {
                try {
                    sink.add(toEntry(details, spillDir));
                } catch (IOException ex) {
                    throw new UncheckedIOException(ex);
                }
            });
            sink.finish();
            return WorkResults.didWork(true);
        } catch (IOException ex) {
            throw new UncheckedIOException("parallel-tar archive failed", ex);
        }
    }

    private ParallelTarWriter.Entry toEntry(FileCopyDetailsInternal details,
                                            ParallelZipWriter.LazySpillDir spillDir) throws IOException {
        String name = details.getRelativePath().getPathString();
        long mtimeSeconds = preserveTimestamps ? Math.max(0L, details.getLastModified() / 1000L) : 0L;
        boolean dir = details.isDirectory();
        int mode = unixMode(details, dir);
        if (dir) {
            return ParallelTarWriter.Entry.dirEntry(name, mtimeSeconds, mode);
        }
        Path real = rawFileOrNull(details);
        if (real != null) {
            return ParallelTarWriter.Entry.fileEntry(name, real, false, details.getSize(), mtimeSeconds, mode);
        }
        try (InputStream in = details.open()) {
            return materialize(name, in, spillDir, mtimeSeconds, mode);
        }
    }

    private ParallelTarWriter.Entry materialize(String name, InputStream in,
                                                ParallelZipWriter.LazySpillDir spillDir,
                                                long mtimeSeconds, int mode) throws IOException {
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
                Path tmp = Files.createTempFile(spillDir.get(), "tar-in", ".raw");
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
                return ParallelTarWriter.Entry.fileEntry(name, tmp, true, total, mtimeSeconds, mode);
            }
        }
        return ParallelTarWriter.Entry.inlineEntry(name, buf.toByteArray(), mtimeSeconds, mode);
    }

    private static Path rawFileOrNull(FileCopyDetailsInternal details) {
        try {
            return details.getFile().toPath();
        } catch (UnsupportedOperationException filtered) {
            return null;
        }
    }

    private static int unixMode(FileCopyDetailsInternal details, boolean dir) {
        try {
            return details.getPermissions().toUnixNumeric();
        } catch (Throwable t) {
            return dir ? ParallelZipWriter.DEFAULT_DIR_MODE : ParallelZipWriter.DEFAULT_FILE_MODE;
        }
    }
}
