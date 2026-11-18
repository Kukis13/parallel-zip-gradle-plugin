package com.ljarocki.parallelzip;

import org.gradle.testkit.runner.BuildResult;
import org.gradle.testkit.runner.GradleRunner;
import org.gradle.testkit.runner.TaskOutcome;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Enumeration;
import java.util.jar.JarInputStream;
import java.util.zip.GZIPOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AutoAccelerateFunctionalTest {

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

    private static byte[] gzippedBytes(byte[] raw) throws IOException {
        var baos = new java.io.ByteArrayOutputStream();
        try (var gz = new GZIPOutputStream(baos)) {
            gz.write(raw);
        }
        return baos.toByteArray();
    }

    private void writeSampleInputs() throws IOException {
        Path src = projectDir.resolve("staging");
        Files.createDirectories(src);
        Files.writeString(src.resolve("hello.txt"), "hello ".repeat(500));
        Files.write(src.resolve("archive.gz"), gzippedBytes("already compressed payload ".repeat(100).getBytes()));
    }

    @Test
    void acceleratesStandardZipTaskByDefault() throws Exception {
        settings();
        writeSampleInputs();
        Files.writeString(projectDir.resolve("build.gradle"), """
                plugins { id 'com.ljarocki.parallel-zip' }
                tasks.register('dist', Zip) {
                    from 'staging'
                    destinationDirectory = layout.buildDirectory
                    archiveFileName = 'dist.zip'
                    preserveFileTimestamps = false
                    reproducibleFileOrder = true
                }
                """);

        BuildResult result = run("dist");
        assertEquals(TaskOutcome.SUCCESS, result.task(":dist").getOutcome());

        Path zip = projectDir.resolve("build/dist.zip");
        try (ZipFile zf = new ZipFile(zip.toFile())) {
            ZipEntry txt = zf.getEntry("hello.txt");
            ZipEntry gz = zf.getEntry("archive.gz");
            assertNotNull(txt);
            assertNotNull(gz);
            assertEquals(ZipEntry.DEFLATED, txt.getMethod(), "compressible text should be DEFLATED");
            // Stock Gradle Zip would DEFLATE archive.gz; ParallelZipCopyAction STOREs it via skipAlreadyCompressed=true.
            assertEquals(ZipEntry.STORED, gz.getMethod(),
                    "already-compressed .gz should be STORED by accelerated Zip action");
        }
    }

    @Test
    void acceleratesApplicationDistZip() throws Exception {
        settings();
        Files.createDirectories(projectDir.resolve("src/main/java/example"));
        Files.writeString(projectDir.resolve("src/main/java/example/Main.java"), """
                package example;
                public class Main {
                    public static void main(String[] args) {
                        System.out.println("hi");
                    }
                }
                """);
        Files.writeString(projectDir.resolve("build.gradle"), """
                plugins {
                    id 'application'
                    id 'com.ljarocki.parallel-zip'
                }
                application {
                    mainClass = 'example.Main'
                }
                """);

        BuildResult result = run("distZip");
        assertEquals(TaskOutcome.SUCCESS, result.task(":distZip").getOutcome());

        Path distZip = projectDir.resolve("build/distributions/sample.zip");
        assertTrue(Files.exists(distZip), "distZip output must exist");
        try (ZipFile zf = new ZipFile(distZip.toFile())) {
            ZipEntry jarEntry = zf.getEntry("sample/lib/sample.jar");
            assertNotNull(jarEntry, "distZip should contain the application jar");
            assertEquals(ZipEntry.STORED, jarEntry.getMethod(),
                    "jar inside distZip should be STORED by ParallelZipCopyAction");
        }
    }

    @Test
    void respectsZipEntryCompressionStored() throws Exception {
        settings();
        writeSampleInputs();
        Files.writeString(projectDir.resolve("build.gradle"), """
                plugins { id 'com.ljarocki.parallel-zip' }
                tasks.register('dist', Zip) {
                    from 'staging'
                    destinationDirectory = layout.buildDirectory
                    archiveFileName = 'dist.zip'
                    entryCompression = ZipEntryCompression.STORED
                }
                """);

        run("dist");
        Path zip = projectDir.resolve("build/dist.zip");
        try (ZipFile zf = new ZipFile(zip.toFile())) {
            Enumeration<? extends ZipEntry> entries = zf.entries();
            while (entries.hasMoreElements()) {
                ZipEntry e = entries.nextElement();
                if (!e.isDirectory()) {
                    assertEquals(ZipEntry.STORED, e.getMethod(), "all entries must be STORED: " + e.getName());
                }
            }
        }
    }

    @Test
    void preservesDoFirstAndDoLastActions() throws Exception {
        settings();
        writeSampleInputs();
        Files.writeString(projectDir.resolve("build.gradle"), """
                plugins { id 'com.ljarocki.parallel-zip' }
                tasks.register('dist', Zip) {
                    from 'staging'
                    destinationDirectory = layout.buildDirectory
                    archiveFileName = 'dist.zip'
                    doFirst {
                        println 'MARKER_DO_FIRST'
                    }
                    doLast {
                        println 'MARKER_DO_LAST'
                        assert archiveFile.get().asFile.exists()
                    }
                }
                """);

        BuildResult result = run("dist");
        assertTrue(result.getOutput().contains("MARKER_DO_FIRST"));
        assertTrue(result.getOutput().contains("MARKER_DO_LAST"));
        assertTrue(result.getOutput().indexOf("MARKER_DO_FIRST") < result.getOutput().indexOf("MARKER_DO_LAST"));
    }

    @Test
    void canDisableViaAccelerateAllZipTasksFalse() throws Exception {
        settings();
        writeSampleInputs();
        Files.writeString(projectDir.resolve("build.gradle"), """
                plugins { id 'com.ljarocki.parallel-zip' }
                parallelZip {
                    accelerateAllZipTasks = false
                }
                tasks.register('dist', Zip) {
                    from 'staging'
                    destinationDirectory = layout.buildDirectory
                    archiveFileName = 'dist.zip'
                }
                """);

        run("dist");
        Path zip = projectDir.resolve("build/dist.zip");
        try (ZipFile zf = new ZipFile(zip.toFile())) {
            ZipEntry gz = zf.getEntry("archive.gz");
            assertNotNull(gz);
            // Stock Gradle Zip does not skip already-compressed entries, so it DEFLATES archive.gz.
            assertEquals(ZipEntry.DEFLATED, gz.getMethod(),
                    "stock Zip should run when accelerateAllZipTasks = false");
        }
    }

    @Test
    void canFilterViaMatching() throws Exception {
        settings();
        writeSampleInputs();
        Files.writeString(projectDir.resolve("build.gradle"), """
                plugins { id 'com.ljarocki.parallel-zip' }
                parallelZip {
                    matching { it.name == 'fastZip' }
                }
                tasks.register('fastZip', Zip) {
                    from 'staging'
                    destinationDirectory = layout.buildDirectory
                    archiveFileName = 'fast.zip'
                }
                tasks.register('stockZip', Zip) {
                    from 'staging'
                    destinationDirectory = layout.buildDirectory
                    archiveFileName = 'stock.zip'
                }
                """);

        run("fastZip", "stockZip");
        try (ZipFile fast = new ZipFile(projectDir.resolve("build/fast.zip").toFile());
             ZipFile stock = new ZipFile(projectDir.resolve("build/stock.zip").toFile())) {
            assertEquals(ZipEntry.STORED, fast.getEntry("archive.gz").getMethod(),
                    "fastZip should be accelerated");
            assertEquals(ZipEntry.DEFLATED, stock.getEntry("archive.gz").getMethod(),
                    "stockZip should be left to stock Zip");
        }
    }

    @Test
    void doesNotBreakJarManifestOrdering() throws Exception {
        settings();
        Files.createDirectories(projectDir.resolve("src/main/java/example"));
        Files.writeString(projectDir.resolve("src/main/java/example/Main.java"), """
                package example;
                public class Main {}
                """);
        Files.writeString(projectDir.resolve("build.gradle"), """
                plugins {
                    id 'java'
                    id 'com.ljarocki.parallel-zip'
                }
                jar {
                    manifest {
                        attributes 'Main-Class': 'example.Main'
                    }
                }
                """);

        run("jar");
        Path jar = projectDir.resolve("build/libs/sample.jar");
        assertTrue(Files.exists(jar));
        try (JarInputStream jis = new JarInputStream(Files.newInputStream(jar))) {
            assertNotNull(jis.getManifest(), "JarInputStream requires MANIFEST.MF to remain at the start of the jar");
            assertEquals("example.Main", jis.getManifest().getMainAttributes().getValue("Main-Class"));
        }
    }

    @Test
    void isConfigurationCacheCompatible() throws Exception {
        settings();
        writeSampleInputs();
        Files.writeString(projectDir.resolve("build.gradle"), """
                plugins { id 'com.ljarocki.parallel-zip' }
                parallelZip {
                    threads = 2
                    skipAlreadyCompressed = true
                }
                tasks.register('dist', Zip) {
                    from 'staging'
                    destinationDirectory = layout.buildDirectory
                    archiveFileName = 'dist.zip'
                }
                """);

        BuildResult first = run("dist", "--configuration-cache");
        assertEquals(TaskOutcome.SUCCESS, first.task(":dist").getOutcome());
        assertTrue(first.getOutput().contains("Configuration cache entry stored."));

        Files.deleteIfExists(projectDir.resolve("build/dist.zip"));
        BuildResult second = run("dist", "--configuration-cache");
        assertEquals(TaskOutcome.SUCCESS, second.task(":dist").getOutcome());
        assertTrue(second.getOutput().contains("Configuration cache entry reused."));
        assertTrue(Files.exists(projectDir.resolve("build/dist.zip")));
    }

    @Test
    void changingLevelInvalidatesUpToDateButChangingThreadsDoesNot() throws Exception {
        settings();
        writeSampleInputs();
        Files.writeString(projectDir.resolve("build.gradle"), """
                plugins { id 'com.ljarocki.parallel-zip' }
                parallelZip {
                    level = providers.gradleProperty('zipLevel').map { it.toInteger() }.orElse(6)
                    threads = providers.gradleProperty('zipThreads').map { it.toInteger() }.orElse(4)
                }
                tasks.register('dist', Zip) {
                    from 'staging'
                    destinationDirectory = layout.buildDirectory
                    archiveFileName = 'dist.zip'
                }
                """);

        assertEquals(TaskOutcome.SUCCESS, run("dist").task(":dist").getOutcome());
        assertEquals(TaskOutcome.UP_TO_DATE, run("dist", "-PzipThreads=2").task(":dist").getOutcome(),
                "changing threads must not invalidate up-to-date state");
        assertEquals(TaskOutcome.SUCCESS, run("dist", "-PzipLevel=1").task(":dist").getOutcome(),
                "changing level must invalidate up-to-date state");
    }
}
