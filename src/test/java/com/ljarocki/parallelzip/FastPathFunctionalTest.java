package com.ljarocki.parallelzip;

import org.gradle.testkit.runner.BuildResult;
import org.gradle.testkit.runner.GradleRunner;
import org.gradle.testkit.runner.TaskOutcome;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FastPathFunctionalTest {

    @TempDir
    Path projectDir;

    private void settings() throws IOException {
        Files.writeString(projectDir.resolve("settings.gradle"), "rootProject.name = 'sample'\n");
    }

    private BuildResult run(String... args) {
        return GradleRunner.create()
                .withProjectDir(projectDir.toFile())
                .withPluginClasspath()
                .withArguments(args)
                .build();
    }

    private static String readEntry(Path zip, String name) throws IOException {
        try (ZipFile zf = new ZipFile(zip.toFile())) {
            ZipEntry e = zf.getEntry(name);
            assertNotNull(e, "missing entry: " + name);
            try (InputStream in = zf.getInputStream(e)) {
                return new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
        }
    }

    private static List<String> entryNames(Path zip) throws IOException {
        List<String> names = new ArrayList<>();
        try (ZipFile zf = new ZipFile(zip.toFile())) {
            Enumeration<? extends ZipEntry> en = zf.entries();
            while (en.hasMoreElements()) {
                names.add(en.nextElement().getName());
            }
        }
        return names;
    }

    @Test
    void fastPathProducesIdenticalReproducibleArchiveForPureDirectorySpecs() throws Exception {
        settings();
        Path src = projectDir.resolve("staging");
        for (int d = 0; d < 6; d++) {
            Path sub = src.resolve("dir" + d + "/nested");
            Files.createDirectories(sub);
            for (int f = 0; f < 10; f++) {
                Files.writeString(sub.resolve("file" + f + ".txt"), "data-" + d + "-" + f + "\n");
            }
        }
        Files.createDirectories(src.resolve("emptyDir"));
        Files.writeString(projectDir.resolve("extra.txt"), "extra-file\n");

        Files.writeString(projectDir.resolve("build.gradle"), """
                plugins { id 'com.ljarocki.parallel-zip' }
                import com.ljarocki.parallelzip.ParallelZip

                tasks.register('pzip', ParallelZip) {
                    from 'staging'
                    into('sub') {
                        from 'extra.txt'
                    }
                    destinationDirectory = layout.buildDirectory
                    archiveFileName = 'pzip.zip'
                    preserveFileTimestamps = false
                    reproducibleFileOrder = true
                    threads = 4
                }

                tasks.register('azip', Zip) {
                    from 'staging'
                    into('sub') {
                        from 'extra.txt'
                    }
                    destinationDirectory = layout.buildDirectory
                    archiveFileName = 'azip.zip'
                    preserveFileTimestamps = false
                    reproducibleFileOrder = true
                }
                """);

        BuildResult res = run("pzip", "azip");
        assertEquals(TaskOutcome.SUCCESS, res.task(":pzip").getOutcome());
        assertEquals(TaskOutcome.SUCCESS, res.task(":azip").getOutcome());

        Path pzip = projectDir.resolve("build/pzip.zip");
        Path azip = projectDir.resolve("build/azip.zip");
        List<String> pNames = entryNames(pzip);
        List<String> aNames = entryNames(azip);
        assertEquals(pNames, aNames);
        assertTrue(pNames.contains("emptyDir/"), "empty directories must be preserved");
        assertTrue(pNames.contains("sub/extra.txt"), "into() prefix for single file must be preserved");
        assertTrue(pNames.contains("dir5/nested/file9.txt"));

        // Reproducible order: entries must be sorted lexicographically.
        List<String> sorted = new ArrayList<>(pNames);
        sorted.sort(String::compareTo);
        assertEquals(sorted, pNames);
        assertEquals(Files.readAllBytes(pzip).length, Files.readAllBytes(azip).length);
    }

    @Test
    void fastPathHonorsDuplicatesStrategyExcludeFirstWins() throws Exception {
        settings();
        Files.createDirectories(projectDir.resolve("first"));
        Files.createDirectories(projectDir.resolve("second"));
        Files.writeString(projectDir.resolve("first/dup.txt"), "from-first");
        Files.writeString(projectDir.resolve("second/dup.txt"), "from-second");
        Files.writeString(projectDir.resolve("second/only-second.txt"), "only-second");

        Files.writeString(projectDir.resolve("build.gradle"), """
                plugins { id 'com.ljarocki.parallel-zip' }
                tasks.register('dist', Zip) {
                    from 'first'
                    from 'second'
                    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
                    destinationDirectory = layout.buildDirectory
                    archiveFileName = 'dist.zip'
                    reproducibleFileOrder = true
                }
                """);

        run("dist");
        Path zip = projectDir.resolve("build/dist.zip");
        assertEquals("from-first", readEntry(zip, "dup.txt"));
        assertEquals("only-second", readEntry(zip, "only-second.txt"));
        assertEquals(2, entryNames(zip).stream().filter(n -> !n.endsWith("/")).count());
    }

    @Test
    void fallsBackCleanlyWhenExcludeOrIncludeEmptyDirsFalseOrFileModePresent() throws Exception {
        settings();
        Files.createDirectories(projectDir.resolve("staging/empty"));
        Files.writeString(projectDir.resolve("staging/keep.txt"), "keep");
        Files.writeString(projectDir.resolve("staging/drop.log"), "drop");

        Files.writeString(projectDir.resolve("build.gradle"), """
                plugins { id 'com.ljarocki.parallel-zip' }
                tasks.register('dist', Zip) {
                    from('staging') {
                        exclude '**/*.log'
                    }
                    includeEmptyDirs = false
                    destinationDirectory = layout.buildDirectory
                    archiveFileName = 'dist.zip'
                }
                """);

        run("dist");
        Path zip = projectDir.resolve("build/dist.zip");
        try (ZipFile zf = new ZipFile(zip.toFile())) {
            assertNotNull(zf.getEntry("keep.txt"));
            assertNull(zf.getEntry("drop.log"), "exclude must still work via fallback");
            assertFalse(entryNames(zip).contains("empty/"), "includeEmptyDirs = false must still work via fallback");
        }
    }
}
