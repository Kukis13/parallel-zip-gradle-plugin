package com.ljarocki.parallelzip;

import org.gradle.testkit.runner.BuildResult;
import org.gradle.testkit.runner.GradleRunner;
import org.gradle.testkit.runner.TaskOutcome;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VerifyZipFunctionalTest {

    @TempDir
    Path projectDir;

    private void settings() throws IOException {
        Files.writeString(projectDir.resolve("settings.gradle"), "rootProject.name = 'sample'\n");
    }

    private GradleRunner runner(String... args) {
        return GradleRunner.create()
                .withProjectDir(projectDir.toFile())
                .withPluginClasspath()
                .withArguments(args);
    }

    @Test
    void verifyZipSucceedsForIdenticalReproducibleArchivesAndSupportsConfigCache() throws Exception {
        settings();
        Files.createDirectories(projectDir.resolve("staging"));
        Files.writeString(projectDir.resolve("staging/a.txt"), "hello world\n".repeat(50));

        Files.writeString(projectDir.resolve("build.gradle"), """
                plugins { id 'com.ljarocki.parallel-zip' }
                import com.ljarocki.parallelzip.ParallelZip
                import com.ljarocki.parallelzip.VerifyZip

                def zipA = tasks.register('zipA', ParallelZip) {
                    from 'staging'
                    destinationDirectory = layout.buildDirectory
                    archiveFileName = 'a.zip'
                    preserveFileTimestamps = false
                    reproducibleFileOrder = true
                }
                def zipB = tasks.register('zipB', ParallelZip) {
                    from 'staging'
                    destinationDirectory = layout.buildDirectory
                    archiveFileName = 'b.zip'
                    preserveFileTimestamps = false
                    reproducibleFileOrder = true
                }
                tasks.register('verifyRepro', VerifyZip) {
                    archiveA = zipA.flatMap { it.archiveFile }
                    archiveB = zipB.flatMap { it.archiveFile }
                }
                """);

        BuildResult first = runner("verifyRepro", "--configuration-cache").build();
        assertEquals(TaskOutcome.SUCCESS, first.task(":verifyRepro").getOutcome());
        assertTrue(first.getOutput().contains("bit-for-bit reproducible"));
        assertTrue(first.getOutput().contains("Configuration cache entry stored."));

        BuildResult second = runner("verifyRepro", "--configuration-cache", "--rerun-tasks").build();
        assertEquals(TaskOutcome.SUCCESS, second.task(":verifyRepro").getOutcome());
        assertTrue(second.getOutput().contains("Configuration cache entry reused."));
    }

    @Test
    void verifyZipReportsExactEntryAndFieldMismatches() throws Exception {
        settings();
        Path zipA = projectDir.resolve("a.zip");
        Path zipB = projectDir.resolve("b.zip");

        try (var zos = new ZipOutputStream(Files.newOutputStream(zipA))) {
            ZipEntry e1 = new ZipEntry("same-name.txt");
            e1.setLastModifiedTime(FileTime.from(Instant.parse("2020-01-01T00:00:00Z")));
            zos.putNextEntry(e1);
            zos.write("hello".getBytes());
            zos.closeEntry();

            ZipEntry e2 = new ZipEntry("only-in-a.txt");
            zos.putNextEntry(e2);
            zos.write("a".getBytes());
            zos.closeEntry();
        }

        try (var zos = new ZipOutputStream(Files.newOutputStream(zipB))) {
            ZipEntry e1 = new ZipEntry("same-name.txt");
            e1.setLastModifiedTime(FileTime.from(Instant.parse("2024-06-15T12:30:00Z")));
            zos.putNextEntry(e1);
            zos.write("world".getBytes());
            zos.closeEntry();
        }

        Files.writeString(projectDir.resolve("build.gradle"), """
                plugins { id 'com.ljarocki.parallel-zip' }
                import com.ljarocki.parallelzip.VerifyZip

                tasks.register('verifyReport', VerifyZip) {
                    archiveA = file('a.zip')
                    archiveB = file('b.zip')
                    failOnMismatch = false
                    reportFile = layout.buildDirectory.file('diff.txt')
                }
                tasks.register('verifyStrict', VerifyZip) {
                    archiveA = file('a.zip')
                    archiveB = file('b.zip')
                }
                """);

        BuildResult soft = runner("verifyReport").build();
        assertEquals(TaskOutcome.SUCCESS, soft.task(":verifyReport").getOutcome());
        String report = Files.readString(projectDir.resolve("build/diff.txt"));
        assertTrue(report.contains("only-in-a.txt: missing in b.zip"), report);
        assertTrue(report.contains("same-name.txt: mtime mismatch"), report);
        assertTrue(report.contains("same-name.txt: crc mismatch"), report);

        BuildResult strict = runner("verifyStrict").buildAndFail();
        assertTrue(strict.getOutput().contains("same-name.txt: crc mismatch"));
    }
}
