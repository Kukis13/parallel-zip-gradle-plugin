package com.ljarocki.parallelzip.internal;

import org.gradle.api.file.DuplicatesStrategy;
import org.gradle.api.internal.file.FileCollectionInternal;
import org.gradle.api.internal.file.FileCollectionStructureVisitor;
import org.gradle.api.internal.file.FileTreeInternal;
import org.gradle.api.internal.file.collections.FileSystemMirroringFileTree;
import org.gradle.api.internal.file.copy.CopySpecInternal;
import org.gradle.api.internal.file.copy.CopySpecResolver;
import org.gradle.api.tasks.bundling.AbstractArchiveTask;
import org.gradle.api.tasks.util.PatternSet;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Fast-path executor for {@link AbstractArchiveTask} instances whose {@code CopySpec} tree
 * consists solely of unfiltered directory trees and plain files (no {@code eachFile},
 * {@code filter}, {@code rename}, {@code include}/{@code exclude}, or custom permission
 * overrides).
 *
 * <p>When eligible, this bypasses Gradle's single-threaded {@code CopyActionProcessingStream}
 * walk and {@code FileCopyDetails} decorator overhead, walking source directories directly
 * (and in parallel across top-level subdirectories when {@code threads > 1}) and feeding
 * {@link ParallelZipWriter.Entry} objects straight into {@link ParallelZipWriter.Sink}.</p>
 */
public final class ParallelZipFastPath {

    private ParallelZipFastPath() { }

    private record DirSource(String prefix, Path baseDir) { }

    private record FileSource(String prefix, Path file) { }

    public static boolean tryExecute(AbstractArchiveTask task,
                                     boolean store,
                                     boolean skipAlreadyCompressed,
                                     int level,
                                     int threads) {
        return tryExecute(task, store, skipAlreadyCompressed, level, threads, null);
    }

    public static boolean tryExecute(AbstractArchiveTask task,
                                     boolean store,
                                     boolean skipAlreadyCompressed,
                                     int level,
                                     int threads,
                                     ExecutorService sharedPool) {
        CopySpecInternal rootSpec = task.getRootSpec();
        List<DirSource> dirSources = new ArrayList<>();
        List<FileSource> fileSources = new ArrayList<>();
        AtomicBoolean eligible = new AtomicBoolean(true);
        AtomicBoolean excludeDuplicates = new AtomicBoolean(false);
        AtomicBoolean seenAnyDuplicateStrategy = new AtomicBoolean(false);

        rootSpec.walk(resolver -> {
            if (!eligible.get()) {
                return;
            }
            if (!isResolverEligible(resolver)) {
                eligible.set(false);
                return;
            }

            DuplicatesStrategy ds = resolver.getDuplicatesStrategy();
            if (ds == DuplicatesStrategy.FAIL || ds == DuplicatesStrategy.WARN) {
                eligible.set(false);
                return;
            }
            boolean isExclude = (ds == DuplicatesStrategy.EXCLUDE);
            if (!seenAnyDuplicateStrategy.getAndSet(true)) {
                excludeDuplicates.set(isExclude);
            } else if (ds != DuplicatesStrategy.INHERIT && excludeDuplicates.get() != isExclude) {
                // Mixed INCLUDE/EXCLUDE across child specs: fall back to Gradle's exact resolver.
                eligible.set(false);
                return;
            }

            String prefix = normalizePrefix(resolver.getDestPath().getPathString());
            FileCollectionInternal source = (FileCollectionInternal) resolver.getSource();
            source.visitStructure(new FileCollectionStructureVisitor() {
                @Override
                public void visitCollection(FileCollectionInternal.Source src, Iterable<File> contents) {
                    if (!eligible.get()) return;
                    for (File f : contents) {
                        if (!f.exists()) continue;
                        if (f.isFile()) {
                            fileSources.add(new FileSource(prefix, f.toPath()));
                        } else if (f.isDirectory()) {
                            dirSources.add(new DirSource(prefix, f.toPath()));
                        } else {
                            eligible.set(false);
                            return;
                        }
                    }
                }

                @Override
                public void visitFileTree(File root, PatternSet patterns, FileTreeInternal fileTree) {
                    if (!eligible.get()) return;
                    if (!patterns.isEmpty()) {
                        eligible.set(false);
                        return;
                    }
                    if (root.exists()) {
                        if (root.isDirectory()) {
                            dirSources.add(new DirSource(prefix, root.toPath()));
                        } else if (root.isFile()) {
                            fileSources.add(new FileSource(prefix, root.toPath()));
                        } else {
                            eligible.set(false);
                        }
                    }
                }

                @Override
                public void visitFileTreeBackedByFile(File file,
                                                      FileTreeInternal fileTree,
                                                      FileSystemMirroringFileTree sourceTree) {
                    eligible.set(false);
                }
            });
        });

        if (!eligible.get()) {
            return false;
        }

        boolean preserveTimestamps = task.isPreserveFileTimestamps();
        boolean reproducibleOrder = task.isReproducibleFileOrder();
        Path archive = task.getArchiveFile().get().getAsFile().toPath();

        try {
            List<ParallelZipWriter.Entry> entries = collectEntries(
                    dirSources, fileSources, preserveTimestamps, reproducibleOrder,
                    excludeDuplicates.get(), threads, sharedPool);
            Path spillParent = ParallelZipWriter.prepareParent(archive);
            try (ParallelZipWriter.LazySpillDir spillDir = new ParallelZipWriter.LazySpillDir(spillParent)) {
                ParallelZipWriter.Sink sink = new ParallelZipWriter.Sink(
                        archive, store, skipAlreadyCompressed, level, threads,
                        false, ParallelZipWriter.SPILL_THRESHOLD, spillDir, sharedPool);
                for (ParallelZipWriter.Entry entry : entries) {
                    sink.add(entry);
                }
                sink.finish();
            }
            return true;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("parallel-zip fast path interrupted", e);
        }
    }

    private static boolean isResolverEligible(CopySpecResolver resolver) {
        if (!resolver.getAllCopyActions().isEmpty()) return false;
        if (!resolver.getAllIncludes().isEmpty()) return false;
        if (!resolver.getAllExcludes().isEmpty()) return false;
        if (!resolver.getAllIncludeSpecs().isEmpty()) return false;
        if (!resolver.getAllExcludeSpecs().isEmpty()) return false;
        if (!resolver.getIncludeEmptyDirs()) return false;
        if (hasLegacyModeOverride(resolver)) return false;
        if (resolver.getImmutableFilePermissions().isPresent()) return false;
        if (resolver.getImmutableDirPermissions().isPresent()) return false;
        return true;
    }

    private static final java.lang.reflect.Method LEGACY_GET_FILE_MODE = findMethod("getFileMode");
    private static final java.lang.reflect.Method LEGACY_GET_DIR_MODE = findMethod("getDirMode");

    private static java.lang.reflect.Method findMethod(String name) {
        try {
            return CopySpecResolver.class.getMethod(name);
        } catch (NoSuchMethodException e) {
            return null; // Removed in Gradle 9+
        }
    }

    private static boolean hasLegacyModeOverride(CopySpecResolver resolver) {
        try {
            if (LEGACY_GET_FILE_MODE != null && LEGACY_GET_FILE_MODE.invoke(resolver) != null) return true;
            if (LEGACY_GET_DIR_MODE != null && LEGACY_GET_DIR_MODE.invoke(resolver) != null) return true;
        } catch (ReflectiveOperationException ignored) { }
        return false;
    }

    private static List<ParallelZipWriter.Entry> collectEntries(List<DirSource> dirSources,
                                                                List<FileSource> fileSources,
                                                                boolean preserveTimestamps,
                                                                boolean reproducibleOrder,
                                                                boolean excludeDuplicates,
                                                                int threads,
                                                                ExecutorService sharedPool)
            throws IOException, InterruptedException {
        List<ParallelZipWriter.Entry> all = new ArrayList<>();

        for (FileSource fs : fileSources) {
            BasicFileAttributes attrs = Files.readAttributes(fs.file(), BasicFileAttributes.class);
            all.add(toEntry(fs.prefix() + fs.file().getFileName().toString(), fs.file(), false, attrs, preserveTimestamps));
        }

        if (!dirSources.isEmpty()) {
            // Fan out top-level subdirectories across worker threads when threads > 1.
            List<Callable<List<ParallelZipWriter.Entry>>> walkTasks = new ArrayList<>();
            for (DirSource ds : dirSources) {
                Path base = ds.baseDir();
                if (!Files.exists(base)) continue;
                try (var stream = Files.list(base)) {
                    for (Path child : stream.toList()) {
                        BasicFileAttributes attrs = Files.readAttributes(child, BasicFileAttributes.class);
                        if (attrs.isDirectory()) {
                            all.add(toEntry(entryName(ds.prefix(), base, child, true), child, true, attrs, preserveTimestamps));
                            walkTasks.add(() -> walkSubtree(ds.prefix(), base, child, preserveTimestamps));
                        } else {
                            all.add(toEntry(entryName(ds.prefix(), base, child, false), child, false, attrs, preserveTimestamps));
                        }
                    }
                }
            }

            if (threads > 1 && walkTasks.size() > 1) {
                boolean ownsPool = (sharedPool == null);
                ExecutorService pool = ownsPool
                        ? Executors.newFixedThreadPool(Math.min(threads, Math.min(walkTasks.size(), 8)))
                        : sharedPool;
                try {
                    List<Future<List<ParallelZipWriter.Entry>>> futures = pool.invokeAll(walkTasks);
                    for (Future<List<ParallelZipWriter.Entry>> f : futures) {
                        all.addAll(f.get());
                    }
                } catch (ExecutionException e) {
                    Throwable cause = e.getCause();
                    if (cause instanceof IOException ioe) throw ioe;
                    throw new RuntimeException(cause);
                } finally {
                    if (ownsPool) {
                        pool.shutdown();
                    }
                }
            } else {
                for (Callable<List<ParallelZipWriter.Entry>> task : walkTasks) {
                    try {
                        all.addAll(task.call());
                    } catch (IOException e) {
                        throw e;
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                }
            }
        }

        if (reproducibleOrder) {
            all.sort(Comparator.comparing(e -> e.name));
        }

        if (excludeDuplicates && all.size() > 1) {
            Set<String> seen = new HashSet<>(all.size() * 2);
            List<ParallelZipWriter.Entry> deduped = new ArrayList<>(all.size());
            for (ParallelZipWriter.Entry entry : all) {
                if (seen.add(entry.name)) {
                    deduped.add(entry);
                }
            }
            return deduped;
        }
        return all;
    }

    private static List<ParallelZipWriter.Entry> walkSubtree(String prefix,
                                                             Path base,
                                                             Path subtreeRoot,
                                                             boolean preserveTimestamps) throws IOException {
        List<ParallelZipWriter.Entry> list = new ArrayList<>();
        Files.walkFileTree(subtreeRoot, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                if (!dir.equals(subtreeRoot)) {
                    list.add(toEntry(entryName(prefix, base, dir, true), dir, true, attrs, preserveTimestamps));
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                list.add(toEntry(entryName(prefix, base, file, false), file, false, attrs, preserveTimestamps));
                return FileVisitResult.CONTINUE;
            }
        });
        return list;
    }

    private static String entryName(String prefix, Path base, Path p, boolean dir) {
        String rel = base.relativize(p).toString().replace('\\', '/');
        return dir ? (prefix + rel + "/") : (prefix + rel);
    }

    private static ParallelZipWriter.Entry toEntry(String name,
                                                   Path p,
                                                   boolean dir,
                                                   BasicFileAttributes attrs,
                                                   boolean preserveTimestamps) {
        int dosTime;
        int dosDate;
        if (preserveTimestamps) {
            long packed = ParallelZipWriter.dosDateTimePacked(attrs.lastModifiedTime().toMillis());
            dosTime = (int) (packed >>> 32);
            dosDate = (int) packed;
        } else {
            dosTime = ParallelZipWriter.FIXED_DOS_TIME;
            dosDate = ParallelZipWriter.FIXED_DOS_DATE;
        }
        int extAttr = (unixMode(p, dir) << 16) | (dir ? 0x10 : 0);
        return dir
                ? ParallelZipWriter.dirEntry(name, dosTime, dosDate, extAttr)
                : ParallelZipWriter.fileEntry(name, p, false, attrs.size(), dosTime, dosDate, extAttr);
    }

    private static String normalizePrefix(String prefix) {
        if (prefix == null || prefix.isEmpty()) return "";
        String p = prefix.replace('\\', '/');
        while (p.startsWith("/")) p = p.substring(1);
        return p.isEmpty() || p.endsWith("/") ? p : p + "/";
    }

    private static int unixMode(Path p, boolean dir) {
        try {
            int mode = 0;
            for (PosixFilePermission perm : Files.getPosixFilePermissions(p)) {
                mode |= switch (perm) {
                    case OWNER_READ -> 0400;
                    case OWNER_WRITE -> 0200;
                    case OWNER_EXECUTE -> 0100;
                    case GROUP_READ -> 0040;
                    case GROUP_WRITE -> 0020;
                    case GROUP_EXECUTE -> 0010;
                    case OTHERS_READ -> 0004;
                    case OTHERS_WRITE -> 0002;
                    case OTHERS_EXECUTE -> 0001;
                };
            }
            return mode;
        } catch (UnsupportedOperationException | IOException e) {
            return dir ? ParallelZipWriter.DEFAULT_DIR_MODE : ParallelZipWriter.DEFAULT_FILE_MODE;
        }
    }
}
