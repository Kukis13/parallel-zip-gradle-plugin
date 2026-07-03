package com.ljarocki.parallelzip;

import org.gradle.api.Plugin;
import org.gradle.api.Project;

/**
 * Applying this plugin makes the {@link ParallelZip} task type available to the build
 * script. It intentionally registers no tasks of its own; declare your own:
 *
 * <pre>
 * plugins { id 'com.ljarocki.parallel-zip' }
 *
 * tasks.register('dist', com.ljarocki.parallelzip.ParallelZip) {
 *     from = layout.buildDirectory.dir('staging')
 *     archiveFile = layout.buildDirectory.file('dist.zip')
 * }
 * </pre>
 */
public class ParallelZipPlugin implements Plugin<Project> {
    @Override
    public void apply(Project project) {
        // No-op: the value is the ParallelZip task type on the build classpath.
        // Extension/auto-registration can be added in a future release.
        project.getExtensions().getExtraProperties().set("ParallelZip", ParallelZip.class);
    }
}
