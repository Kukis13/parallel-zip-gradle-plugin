package com.ljarocki.parallelzip;

import org.gradle.api.Action;
import org.gradle.api.provider.Property;
import org.gradle.api.specs.Spec;
import org.gradle.api.tasks.bundling.Zip;

/**
 * Configuration extension for {@link ParallelZipPlugin}, registered as {@code parallelZip}.
 *
 * <pre>
 * plugins {
 *     id 'com.ljarocki.parallel-zip'
 * }
 *
 * parallelZip {
 *     accelerateAllZipTasks = true
 *     skipAlreadyCompressed = true
 *     level = -1
 *     threads = Runtime.runtime.availableProcessors()
 *     matching { it.name != 'unacceleratedZip' }
 * }
 * </pre>
 */
public abstract class ParallelZipExtension {

    private Spec<? super Zip> taskFilter = task -> true;

    public ParallelZipExtension() {
        getAccelerateAllZipTasks().convention(true);
        getSkipAlreadyCompressed().convention(true);
        getLevel().convention(-1);
        getThreads().convention(Runtime.getRuntime().availableProcessors());
    }

    /**
     * When {@code true} (the default), all {@link Zip} tasks in the project (excluding
     * {@link org.gradle.api.tasks.bundling.Jar} subclasses and {@link ParallelZip} itself)
     * are automatically accelerated using {@code ParallelZipCopyAction}.
     */
    public abstract Property<Boolean> getAccelerateAllZipTasks();

    /**
     * When {@code true} (the default), entries recognized by file signature as already
     * compressed (jars, gzip, images, etc.) are STORED without attempting DEFLATE.
     */
    public abstract Property<Boolean> getSkipAlreadyCompressed();

    /**
     * DEFLATE level {@code 0..12} ({@code 10..12} use {@code libdeflate}), or {@code -1} for the
     * default (6). Ignored when the target {@link Zip} task has
     * {@code entryCompression = ZipEntryCompression.STORED}.
     */
    public abstract Property<Integer> getLevel();

    /**
     * Number of compression threads. Defaults to {@code Runtime.getRuntime().availableProcessors()}.
     */
    public abstract Property<Integer> getThreads();

    /**
     * Restricts auto-acceleration to {@link Zip} tasks matching the given predicate.
     * Multiple calls are combined with logical AND.
     */
    public void matching(Spec<? super Zip> filter) {
        Spec<? super Zip> previous = this.taskFilter;
        this.taskFilter = task -> previous.isSatisfiedBy(task) && filter.isSatisfiedBy(task);
    }

    /**
     * Configures the extension via an {@link Action}.
     */
    public void configure(Action<? super ParallelZipExtension> action) {
        action.execute(this);
    }

    Spec<? super Zip> getTaskFilter() {
        return taskFilter;
    }
}
