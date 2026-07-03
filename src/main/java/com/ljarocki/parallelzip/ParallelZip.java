package com.ljarocki.parallelzip;

import com.ljarocki.parallelzip.internal.ParallelZipCopyAction;
import com.ljarocki.parallelzip.internal.ParallelZipWriter;
import org.gradle.api.internal.file.copy.CopyAction;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.Optional;
import org.gradle.api.tasks.bundling.AbstractArchiveTask;

import java.nio.file.Path;

/**
 * A drop-in replacement for Gradle's {@code Zip} task that compresses entries in parallel.
 *
 * <p>Because it extends {@link AbstractArchiveTask} it accepts the full {@code CopySpec}
 * DSL — {@code from} / {@code into} / {@code include} / {@code exclude} / {@code rename} /
 * {@code filter} / {@code duplicatesStrategy}, nesting, and the {@code archiveFileName} /
 * {@code destinationDirectory} conventions — exactly like {@code Zip}. Swapping
 * {@code type: Zip} for {@code type: ParallelZip} needs no other changes.</p>
 *
 * <pre>
 * tasks.register('dist', ParallelZip) {
 *     from 'build/staging'
 *     into 'myapp-1.0'
 *     archiveFileName = 'dist.zip'
 *     destinationDirectory = layout.buildDirectory
 *     store = false               // true = STORE everything (fastest, ~7% larger)
 *     level = 6                   // DEFLATE level 0..9 (ignored when store = true)
 *     threads = 12                // default: available processors
 *     preserveFileTimestamps = false   // inherited; false = reproducible archive
 * }
 * </pre>
 */
public abstract class ParallelZip extends AbstractArchiveTask {

    /** When true, every entry is STORED (no compression). Default: false. */
    @Input
    @Optional
    public abstract Property<Boolean> getStore();

    /** DEFLATE level 0..9, or -1 for the zlib default (6). Ignored when {@link #getStore()} is true. */
    @Input
    @Optional
    public abstract Property<Integer> getLevel();

    /**
     * Number of compression threads. Default: available processors. Marked {@code @Internal}
     * because it does not affect the archive bytes (output is identical for any thread count),
     * so changing it must not invalidate the task's up-to-date state.
     */
    @Internal
    public abstract Property<Integer> getThreads();

    public ParallelZip() {
        getStore().convention(false);
        getLevel().convention(-1);
        getThreads().convention(Runtime.getRuntime().availableProcessors());
        getArchiveExtension().convention("zip");
        getDestinationDirectory().convention(getProject().getLayout().getBuildDirectory().dir("distributions"));
        getArchiveBaseName().convention(getProject().getName());
    }

    @Override
    protected CopyAction createCopyAction() {
        Path out = getArchiveFile().get().getAsFile().toPath();
        return new ParallelZipCopyAction(
                out,
                getStore().get(),
                getLevel().get(),
                Math.max(1, getThreads().get()),
                isPreserveFileTimestamps(),
                ParallelZipWriter.SPILL_THRESHOLD);
    }
}
