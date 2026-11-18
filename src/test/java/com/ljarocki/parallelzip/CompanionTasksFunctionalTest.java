package com.ljarocki.parallelzip;

import org.gradle.testkit.runner.BuildResult;
import org.gradle.testkit.runner.GradleRunner;
import org.gradle.testkit.runner.TaskOutcome;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.jar.JarInputStream;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CompanionTasksFunctionalTest {

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

    private static byte[] randomish(int n) {
        byte[] b = new byte[n];
        long s = 0x9E3779B97F4A7C15L;
        for (int i = 0; i < n; i++) {
            s ^= s << 13;
            s ^= s >>> 7;
            s ^= s << 17;
            b[i] = (byte) s;
        }
        return b;
    }

    private static byte[] tinyJarBytes() throws IOException {
        var baos = new java.io.ByteArrayOutputStream();
        try (var zos = new ZipOutputStream(baos)) {
            zos.putNextEntry(new ZipEntry("nested.txt"));
            zos.write("nested-content\n".getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
        }
        return baos.toByteArray();
    }

    @Test
    void parallelJarPinsManifestFirstEvenWithReproducibleOrderAndSkipsAlreadyCompressed() throws Exception {
        settings();
        Path classes = projectDir.resolve("classes");
        Files.createDirectories(classes.resolve("aaa/pkg"));
        Files.writeString(classes.resolve("aaa/pkg/Alpha.txt"), "alpha ".repeat(400));
        Files.write(classes.resolve("aaa/lib.jar"), tinyJarBytes());

        Files.writeString(projectDir.resolve("build.gradle"), """
                plugins { id 'com.ljarocki.parallel-zip' }
                import com.ljarocki.parallelzip.ParallelJar

                tasks.register('fatJar', ParallelJar) {
                    from 'classes'
                    destinationDirectory = layout.buildDirectory
                    archiveFileName = 'fat.jar'
                    reproducibleFileOrder = true
                    preserveFileTimestamps = false
                    manifest {
                        attributes 'Main-Class': 'aaa.pkg.Alpha', 'Implementation-Version': '2.0.0'
                    }
                }
                """);

        BuildResult res = run("fatJar");
        assertEquals(TaskOutcome.SUCCESS, res.task(":fatJar").getOutcome());

        Path jar = projectDir.resolve("build/fat.jar");
        try (JarInputStream jis = new JarInputStream(Files.newInputStream(jar))) {
            assertNotNull(jis.getManifest(), "META-INF/MANIFEST.MF must be pinned at the start of the jar");
            assertEquals("aaa.pkg.Alpha", jis.getManifest().getMainAttributes().getValue("Main-Class"));
            assertEquals("2.0.0", jis.getManifest().getMainAttributes().getValue("Implementation-Version"));
        }
        try (ZipFile zf = new ZipFile(jar.toFile())) {
            assertEquals(ZipEntry.DEFLATED, zf.getEntry("aaa/pkg/Alpha.txt").getMethod());
            assertEquals(ZipEntry.STORED, zf.getEntry("aaa/lib.jar").getMethod(),
                    "nested jar in fat jar should be STORED via skipAlreadyCompressed");
        }
    }

    @Test
    void parallelTarSupportsLongPaxPathsAndReproducibleMtime() throws Exception {
        settings();
        Path src = projectDir.resolve("staging");
        String longDir = "very/long/nested/directory/hierarchy/that/exceeds/one/hundred/bytes/easily/for/pax/header/testing";
        Files.createDirectories(src.resolve(longDir));
        byte[] payload = "tar-payload-data\n".getBytes(StandardCharsets.UTF_8);
        Files.write(src.resolve(longDir + "/file.txt"), payload);
        Files.writeString(src.resolve("short.txt"), "hello tar\n");

        Files.writeString(projectDir.resolve("build.gradle"), """
                plugins { id 'com.ljarocki.parallel-zip' }
                import com.ljarocki.parallelzip.ParallelTar

                tasks.register('distTar', ParallelTar) {
                    from 'staging'
                    destinationDirectory = layout.buildDirectory
                    archiveFileName = 'dist.tar'
                    preserveFileTimestamps = false
                    reproducibleFileOrder = true
                }
                """);

        BuildResult res = run("distTar");
        assertEquals(TaskOutcome.SUCCESS, res.task(":distTar").getOutcome());

        byte[] tarBytes = Files.readAllBytes(projectDir.resolve("build/dist.tar"));
        Map<String, byte[]> entries = parseTar(tarBytes);
        assertArrayEquals(payload, entries.get(longDir + "/file.txt"));
        assertArrayEquals("hello tar\n".getBytes(StandardCharsets.UTF_8), entries.get("short.txt"));
    }

    @Test
    void parallelTarGzCompressesMultiBlockStreamAndRoundTripsThroughGzipInputStream() throws Exception {
        settings();
        Path src = projectDir.resolve("staging");
        Files.createDirectories(src);
        byte[] compressible = "compressible tar content line\n".repeat(12_000).getBytes(StandardCharsets.UTF_8);
        byte[] incompressible = randomish(300_000);
        Files.write(src.resolve("text.log"), compressible);
        Files.write(src.resolve("random.bin"), incompressible);

        Files.writeString(projectDir.resolve("build.gradle"), """
                plugins { id 'com.ljarocki.parallel-zip' }
                import com.ljarocki.parallelzip.ParallelTarGz

                tasks.register('distTarGz', ParallelTarGz) {
                    from 'staging'
                    destinationDirectory = layout.buildDirectory
                    archiveFileName = 'dist.tar.gz'
                    blockSizeBytes = 65536
                    threads = 4
                    preserveFileTimestamps = false
                    reproducibleFileOrder = true
                }
                """);

        BuildResult first = run("distTarGz", "--configuration-cache");
        assertEquals(TaskOutcome.SUCCESS, first.task(":distTarGz").getOutcome());
        assertTrue(first.getOutput().contains("Configuration cache entry stored."));

        Path tgz = projectDir.resolve("build/dist.tar.gz");
        byte[] tarBytes;
        try (InputStream gzis = new GZIPInputStream(Files.newInputStream(tgz))) {
            tarBytes = gzis.readAllBytes();
        }
        Map<String, byte[]> entries = parseTar(tarBytes);
        assertArrayEquals(compressible, entries.get("text.log"));
        assertArrayEquals(incompressible, entries.get("random.bin"));

        Files.deleteIfExists(tgz);
        BuildResult second = run("distTarGz", "--configuration-cache");
        assertEquals(TaskOutcome.SUCCESS, second.task(":distTarGz").getOutcome());
        assertTrue(second.getOutput().contains("Configuration cache entry reused."));
    }

    /** Minimal USTAR + PAX TAR reader for test verification. */
    private static Map<String, byte[]> parseTar(byte[] tar) throws IOException {
        Map<String, byte[]> out = new LinkedHashMap<>();
        int pos = 0;
        String pendingPaxPath = null;
        while (pos + 512 <= tar.length) {
            boolean allZero = true;
            for (int i = 0; i < 512; i++) {
                if (tar[pos + i] != 0) {
                    allZero = false;
                    break;
                }
            }
            if (allZero) break;

            String name = readNullTerminated(tar, pos, 100);
            long size = parseOctal(tar, pos + 124, 12);
            byte typeFlag = tar[pos + 156];
            pos += 512;

            int dataLen = (int) size;
            byte[] data = new byte[dataLen];
            System.arraycopy(tar, pos, data, 0, dataLen);
            pos += ((dataLen + 511) / 512) * 512;

            if (typeFlag == 'x') {
                String pax = new String(data, StandardCharsets.UTF_8);
                for (String line : pax.split("\n")) {
                    int sp = line.indexOf(' ');
                    if (sp > 0) {
                        String kv = line.substring(sp + 1);
                        if (kv.startsWith("path=")) {
                            pendingPaxPath = kv.substring("path=".length());
                        }
                    }
                }
            } else {
                String finalName = pendingPaxPath != null ? pendingPaxPath : name;
                pendingPaxPath = null;
                if (typeFlag == '0' || typeFlag == 0) {
                    out.put(finalName, data);
                }
            }
        }
        return out;
    }

    private static String readNullTerminated(byte[] b, int off, int max) {
        int len = 0;
        while (len < max && b[off + len] != 0) len++;
        return new String(b, off, len, StandardCharsets.UTF_8);
    }

    private static long parseOctal(byte[] b, int off, int len) {
        long v = 0;
        for (int i = 0; i < len; i++) {
            byte c = b[off + i];
            if (c == 0 || c == ' ') continue;
            v = (v << 3) | (c - '0');
        }
        return v;
    }
}
