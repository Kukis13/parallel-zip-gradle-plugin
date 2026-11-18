package com.ljarocki.parallelzip;

import com.ljarocki.parallelzip.internal.ParallelZipCopyAction;
import com.ljarocki.parallelzip.internal.ParallelZipPoolService;
import com.ljarocki.parallelzip.internal.ParallelZipWriter;
import org.gradle.api.internal.file.copy.CopyAction;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.CacheableTask;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.Optional;
import org.gradle.api.tasks.bundling.Jar;
import org.gradle.api.tasks.bundling.ZipEntryCompression;

import java.nio.file.Path;

/**
 * A multithreaded, reproducible drop-in replacement for Gradle's {@link Jar} task.
 *
 * <p>Extends {@link Jar} so it inherits {@code manifest}, {@code metaInf}, and all
 * {@code CopySpec} DSL features, while compressing entries in parallel via
 * {@link ParallelZipCopyAction} with {@code META-INF/MANIFEST.MF} pinned at the start
 * of the archive so {@link java.util.jar.JarInputStream} and standard tooling always
 * locate it as entry #0.</p>
 */
@CacheableTask
public abstract class ParallelJar extends Jar {

    /** When true, every entry is STORED (no compression). Default: false. */
    @Input
    @Optional
    public abstract Property<Boolean> getStore();

    /** DEFLATE level 0..12 (10..12 use libdeflate), or -1 for the default (6). Ignored when {@link #getStore()} is true. */
    @Input
    @Optional
    public abstract Property<Integer> getLevel();

    /**
     * When true (default), entries recognized by file signature as already compressed
     * (nested jars in fat-jars, images, etc.) are STORED without attempting DEFLATE.
     */
    @Input
    @Optional
    public abstract Property<Boolean> getSkipAlreadyCompressed();

    /** Number of compression threads. Marked {@code @Internal} so it never affects up-to-date checks. */
    @Internal
    public abstract Property<Integer> getThreads();

    /** Shared build-wide worker pool service (wired by {@link ParallelZipPlugin}). */
    @Internal
    public abstract Property<ParallelZipPoolService> getPoolService();

    public ParallelJar() {
        getStore().convention(false);
        getSkipAlreadyCompressed().convention(true);
        getLevel().convention(-1);
        getThreads().convention(Runtime.getRuntime().availableProcessors());
    }

    @Override
    protected CopyAction createCopyAction() {
        Path out = getArchiveFile().get().getAsFile().toPath();
        boolean store = Boolean.TRUE.equals(getStore().get())
                || getEntryCompression() == ZipEntryCompression.STORED;
        var pool = getPoolService().isPresent() ? getPoolService().get().getExecutor() : null;
        return new ParallelZipCopyAction(
                out,
                store,
                Boolean.TRUE.equals(getSkipAlreadyCompressed().get()),
                getLevel().get(),
                Math.max(1, getThreads().get()),
                isPreserveFileTimestamps(),
                ParallelZipWriter.SPILL_THRESHOLD,
                true,
                pool);
    }
}
