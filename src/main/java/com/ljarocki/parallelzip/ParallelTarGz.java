package com.ljarocki.parallelzip;

import org.gradle.api.tasks.CacheableTask;

/**
 * Convenience subclass of {@link ParallelTar} with {@code gzip = true} and
 * {@code archiveExtension = "tar.gz"} by default.
 */
@CacheableTask
public abstract class ParallelTarGz extends ParallelTar {

    public ParallelTarGz() {
        getGzip().convention(true);
        getArchiveExtension().convention("tar.gz");
    }
}
