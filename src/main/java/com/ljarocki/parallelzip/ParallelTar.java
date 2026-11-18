package com.ljarocki.parallelzip;

import com.ljarocki.parallelzip.internal.ParallelTarCopyAction;
import com.ljarocki.parallelzip.internal.ParallelTarWriter;
import com.ljarocki.parallelzip.internal.ParallelZipWriter;
import org.gradle.api.internal.file.copy.CopyAction;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.CacheableTask;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.Optional;
import org.gradle.api.tasks.bundling.AbstractArchiveTask;

import java.nio.file.Path;

/**
 * A reproducible, cacheable TAR task with optional pigz-style parallel block gzip compression.
 *
 * <p>When {@link #getGzip()} is {@code false} (the default for {@code ParallelTar}), writes an
 * uncompressed POSIX USTAR/PAX {@code .tar} archive. When {@code true} (the default for
 * {@link ParallelTarGz}), compresses independent {@link #getBlockSizeBytes()} chunks across all
 * cores using {@code libdeflate} and concatenates them into a valid {@code .tar.gz} stream.</p>
 */
@CacheableTask
public abstract class ParallelTar extends AbstractArchiveTask {

    /** When true, compresses the TAR stream in parallel gzip blocks. Default: false. */
    @Input
    @Optional
    public abstract Property<Boolean> getGzip();

    /** Gzip compression level 1..12, or -1 for the default (6). Ignored when {@link #getGzip()} is false. */
    @Input
    @Optional
    public abstract Property<Integer> getLevel();

    /** Block size in bytes for parallel gzip compression. Default: 2 MiB. */
    @Input
    @Optional
    public abstract Property<Integer> getBlockSizeBytes();

    /** Number of compression threads. Marked {@code @Internal} so it never affects up-to-date checks. */
    @Internal
    public abstract Property<Integer> getThreads();

    public ParallelTar() {
        getGzip().convention(false);
        getLevel().convention(-1);
        getBlockSizeBytes().convention(ParallelTarWriter.DEFAULT_BLOCK_SIZE);
        getThreads().convention(Runtime.getRuntime().availableProcessors());
        getArchiveExtension().convention(getGzip().map(gz -> Boolean.TRUE.equals(gz) ? "tar.gz" : "tar"));
        getDestinationDirectory().convention(getProject().getLayout().getBuildDirectory().dir("distributions"));
        getArchiveBaseName().convention(getProject().getName());
    }

    @Override
    protected CopyAction createCopyAction() {
        Path out = getArchiveFile().get().getAsFile().toPath();
        return new ParallelTarCopyAction(
                out,
                Boolean.TRUE.equals(getGzip().get()),
                getLevel().get(),
                Math.max(1, getThreads().get()),
                getBlockSizeBytes().get(),
                isPreserveFileTimestamps(),
                ParallelZipWriter.SPILL_THRESHOLD);
    }
}
