package com.ljarocki.parallelzip;

import com.ljarocki.parallelzip.internal.ParallelZipCopyAction;
import com.ljarocki.parallelzip.internal.ParallelZipFastPath;
import com.ljarocki.parallelzip.internal.ParallelZipPoolService;
import com.ljarocki.parallelzip.internal.ParallelZipWriter;
import org.gradle.api.Action;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.Task;
import org.gradle.api.internal.file.copy.CopyActionExecuter;
import org.gradle.api.logging.Logger;
import org.gradle.api.logging.Logging;
import org.gradle.api.provider.Provider;
import org.gradle.api.specs.Spec;
import org.gradle.api.tasks.AbstractCopyTask;
import org.gradle.api.tasks.WorkResult;
import org.gradle.api.tasks.bundling.Jar;
import org.gradle.api.tasks.bundling.Zip;
import org.gradle.api.tasks.bundling.ZipEntryCompression;

import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.List;

/**
 * Applies the {@code com.ljarocki.parallel-zip} plugin:
 * <ul>
 *   <li>Registers the {@code parallelZip} extension ({@link ParallelZipExtension}).</li>
 *   <li>Exposes {@code ParallelZip} as an extra property on the project for explicit {@code type: ParallelZip} tasks.</li>
 *   <li>When {@code parallelZip.accelerateAllZipTasks} is {@code true} (the default), intercepts
 *       non-{@link Jar} {@link Zip} tasks and replaces their copy action with {@link ParallelZipCopyAction}.</li>
 * </ul>
 */
public class ParallelZipPlugin implements Plugin<Project> {

    private static final Logger LOGGER = Logging.getLogger(ParallelZipPlugin.class);

    @Override
    public void apply(Project project) {
        project.getExtensions().getExtraProperties().set("ParallelZip", ParallelZip.class);
        project.getExtensions().getExtraProperties().set("ParallelJar", ParallelJar.class);
        project.getExtensions().getExtraProperties().set("ParallelTar", ParallelTar.class);
        project.getExtensions().getExtraProperties().set("ParallelTarGz", ParallelTarGz.class);
        project.getExtensions().getExtraProperties().set("VerifyZip", VerifyZip.class);
        ParallelZipExtension extension = project.getExtensions().create("parallelZip", ParallelZipExtension.class);

        Provider<Boolean> skip = extension.getSkipAlreadyCompressed();
        Provider<Integer> level = extension.getLevel();
        Provider<Integer> threads = extension.getThreads();

        Provider<ParallelZipPoolService> poolService = project.getGradle().getSharedServices()
                .registerIfAbsent("parallelZipPool", ParallelZipPoolService.class,
                        spec -> spec.getParameters().getMaxThreads().set(threads));

        project.getTasks().withType(ParallelZip.class).configureEach(task -> {
            task.getSkipAlreadyCompressed().convention(skip);
            task.getLevel().convention(level);
            task.getThreads().convention(threads);
            task.getPoolService().convention(poolService);
            task.usesService(poolService);
        });
        project.getTasks().withType(ParallelJar.class).configureEach(task -> {
            task.getSkipAlreadyCompressed().convention(skip);
            task.getLevel().convention(level);
            task.getThreads().convention(threads);
            task.getPoolService().convention(poolService);
            task.usesService(poolService);
        });
        project.getTasks().withType(ParallelTar.class).configureEach(task -> {
            task.getLevel().convention(level);
            task.getThreads().convention(threads);
            task.getPoolService().convention(poolService);
            task.usesService(poolService);
        });

        project.afterEvaluate(p -> {
            if (!Boolean.TRUE.equals(extension.getAccelerateAllZipTasks().getOrElse(true))) {
                return;
            }
            Spec<? super Zip> filter = extension.getTaskFilter();
            p.getTasks().withType(Zip.class).configureEach(zip -> {
                if (zip instanceof Jar || !filter.isSatisfiedBy(zip)) {
                    return;
                }
                accelerateZipTask(zip, skip, level, threads, poolService);
            });
        });
    }

    private static void accelerateZipTask(Zip zip,
                                          Provider<Boolean> skipAlreadyCompressed,
                                          Provider<Integer> level,
                                          Provider<Integer> threads,
                                          Provider<ParallelZipPoolService> poolService) {
        List<Action<? super Task>> actions = zip.getActions();
        int copyActionIndex = -1;
        for (int i = 0; i < actions.size(); i++) {
            String className = actions.get(i).getClass().getName();
            if (className.contains("StandardTaskAction")) {
                copyActionIndex = i;
                break;
            }
        }
        if (copyActionIndex < 0) {
            LOGGER.warn("parallel-zip: could not locate copy action on task {}; leaving default Zip implementation",
                    zip.getPath());
            return;
        }

        // Register inputs that affect archive bytes so incremental builds stay sound.
        // Threads is intentionally excluded since it does not change output bytes.
        zip.getInputs().property("parallelZip.skipAlreadyCompressed", skipAlreadyCompressed);
        zip.getInputs().property("parallelZip.level", level);
        zip.usesService(poolService);

        actions.set(copyActionIndex, new AcceleratedZipAction(skipAlreadyCompressed, level, threads, poolService));
    }

    /**
     * Configuration-cache-safe task action that executes a standard {@link Zip} task using
     * {@link ParallelZipCopyAction} while honoring the task's own {@code entryCompression},
     * {@code preserveFileTimestamps}, {@code reproducibleFileOrder}, and {@code CopySpec}.
     */
    private static final class AcceleratedZipAction implements Action<Task> {
        private final Provider<Boolean> skipAlreadyCompressed;
        private final Provider<Integer> level;
        private final Provider<Integer> threads;
        private final Provider<ParallelZipPoolService> poolService;

        AcceleratedZipAction(Provider<Boolean> skipAlreadyCompressed,
                             Provider<Integer> level,
                             Provider<Integer> threads,
                             Provider<ParallelZipPoolService> poolService) {
            this.skipAlreadyCompressed = skipAlreadyCompressed;
            this.level = level;
            this.threads = threads;
            this.poolService = poolService;
        }

        @Override
        public void execute(Task task) {
            Zip zip = (Zip) task;
            Path out = zip.getArchiveFile().get().getAsFile().toPath();
            boolean store = zip.getEntryCompression() == ZipEntryCompression.STORED;
            boolean skip = Boolean.TRUE.equals(skipAlreadyCompressed.get());
            int lvl = level.get();
            int threadCount = Math.max(1, threads.get());
            boolean preserveTimestamps = zip.isPreserveFileTimestamps();
            var pool = poolService.get().getExecutor();

            if (ParallelZipFastPath.tryExecute(zip, store, skip, lvl, threadCount, pool)) {
                zip.setDidWork(true);
                return;
            }

            ParallelZipCopyAction copyAction = new ParallelZipCopyAction(
                    out,
                    store,
                    skip,
                    lvl,
                    threadCount,
                    preserveTimestamps,
                    ParallelZipWriter.SPILL_THRESHOLD,
                    false,
                    pool);

            CopyActionExecuter executer = createCopyActionExecuter(zip);
            WorkResult result = executer.execute(zip.getRootSpec(), copyAction);
            zip.setDidWork(result.getDidWork());
        }

        private static CopyActionExecuter createCopyActionExecuter(AbstractCopyTask task) {
            try {
                Method m = AbstractCopyTask.class.getDeclaredMethod("createCopyActionExecuter");
                m.setAccessible(true);
                return (CopyActionExecuter) m.invoke(task);
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException("Failed to create CopyActionExecuter for " + task.getPath(), e);
            }
        }
    }
}
