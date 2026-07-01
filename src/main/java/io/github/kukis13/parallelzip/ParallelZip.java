package io.github.kukis13.parallelzip;

import io.github.kukis13.parallelzip.internal.ParallelZipWriter;
import org.gradle.api.DefaultTask;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputDirectory;
import org.gradle.api.tasks.Optional;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;

import java.util.List;

/**
 * Builds a ZIP archive from a source directory using parallel compression.
 *
 * <pre>
 * tasks.register('dist', ParallelZip) {
 *     from = layout.buildDirectory.dir('staging')
 *     archiveFile = layout.buildDirectory.file('dist.zip')
 *     store = false              // true = STORE everything (fastest, ~7% larger)
 *     level = 6                  // DEFLATE level 0..9 (ignored when store = true)
 *     threads = 12               // worker threads (default: available processors)
 *     preserveTimestamps = false // false = reproducible, byte-stable archive
 * }
 * </pre>
 *
 * Output is deterministic: entries are written in a fixed, name-sorted order regardless
 * of thread count, so the archive's bytes never depend on scheduling.
 */
public abstract class ParallelZip extends DefaultTask {

    /** The directory whose contents are archived. */
    @InputDirectory
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract DirectoryProperty getFrom();

    /** Optional path prefix placed in front of every entry (like Gradle's {@code into}). */
    @Input
    @Optional
    public abstract Property<String> getInto();

    /** The archive to produce. */
    @OutputFile
    public abstract RegularFileProperty getArchiveFile();

    /** When true, every entry is STORED (no compression). Default: false. */
    @Input
    @Optional
    public abstract Property<Boolean> getStore();

    /** DEFLATE level 0..9, or -1 for the zlib default (6). Ignored when {@link #getStore()} is true. */
    @Input
    @Optional
    public abstract Property<Integer> getLevel();

    /** Number of compression threads. Default: number of available processors. */
    @Input
    @Optional
    public abstract Property<Integer> getThreads();

    /**
     * When true (default), each entry keeps its source file's modification time.
     * Set false for a reproducible, byte-for-byte stable archive.
     */
    @Input
    @Optional
    public abstract Property<Boolean> getPreserveTimestamps();

    public ParallelZip() {
        getStore().convention(false);
        getLevel().convention(-1);
        getThreads().convention(Runtime.getRuntime().availableProcessors());
        getPreserveTimestamps().convention(true);
        getInto().convention("");
    }

    @TaskAction
    public void archive() throws Exception {
        var base = getFrom().get().getAsFile().toPath();
        var out = getArchiveFile().get().getAsFile().toPath();
        boolean store = getStore().get();
        int level = getLevel().get();
        int threads = Math.max(1, getThreads().get());
        boolean preserve = getPreserveTimestamps().get();
        String into = getInto().getOrElse("");

        var sources = List.of(new ParallelZipWriter.Source(base, into));
        ParallelZipWriter.Result r = ParallelZipWriter.write(sources, out, store, level, threads, preserve);

        getLogger().lifecycle(String.format(
                "ParallelZip -> %s (%d entries, %.1f MiB, %s, %d threads, %.2fx%s) in %d ms",
                out.getFileName(), r.entryCount(), r.archiveSize() / 1048576.0,
                store ? "STORE" : "DEFLATE l" + (level < 0 ? 6 : level), threads,
                r.rawBytes() == 0 ? 1.0 : (double) r.storedBytes() / r.rawBytes(),
                r.zip64() ? ", ZIP64" : "",
                r.enumerateMs() + r.compressWriteMs()));
    }
}
