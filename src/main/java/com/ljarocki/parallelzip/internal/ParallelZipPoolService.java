package com.ljarocki.parallelzip.internal;

import org.gradle.api.provider.Property;
import org.gradle.api.services.BuildService;
import org.gradle.api.services.BuildServiceParameters;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Shared Gradle {@link BuildService} providing a bounded thread pool across all
 * parallel archive tasks in a build, preventing CPU oversubscription when {@code --parallel}
 * runs multiple subproject archive tasks concurrently.
 */
public abstract class ParallelZipPoolService
        implements BuildService<ParallelZipPoolService.Params>, AutoCloseable {

    public interface Params extends BuildServiceParameters {
        Property<Integer> getMaxThreads();
    }

    private final ExecutorService executor;

    public ParallelZipPoolService() {
        int threads = Math.max(1, getParameters().getMaxThreads()
                .getOrElse(Runtime.getRuntime().availableProcessors()));
        AtomicInteger id = new AtomicInteger(1);
        this.executor = Executors.newFixedThreadPool(threads, r -> {
            Thread t = new Thread(r, "parallel-zip-worker-" + id.getAndIncrement());
            t.setDaemon(true);
            return t;
        });
    }

    public ExecutorService getExecutor() {
        return executor;
    }

    @Override
    public void close() {
        executor.shutdown();
    }
}
