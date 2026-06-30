package io.github.kukis13.parallelzip;

import org.gradle.testkit.runner.BuildResult;
import org.gradle.testkit.runner.GradleRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Enumeration;
import java.util.HexFormat;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ParallelZipFunctionalTest {

    @TempDir
    Path projectDir;

    private void writeProject(String taskBody) throws IOException {
        Files.writeString(projectDir.resolve("settings.gradle"), "rootProject.name = 'sample'\n");
        Files.writeString(projectDir.resolve("build.gradle"), """
                plugins { id 'io.github.kukis13.parallel-zip' }
                import io.github.kukis13.parallelzip.ParallelZip
                tasks.register('dist', ParallelZip) {
                %s
                }
                """.formatted(taskBody));

        // A small but varied source tree: compressible text, nested dirs, an "already
        // compressed" blob, and an empty file.
        Path src = projectDir.resolve("staging");
        Files.createDirectories(src.resolve("a/b/c"));
        Files.writeString(src.resolve("a/hello.txt"), "hello ".repeat(1000));
        Files.writeString(src.resolve("a/b/c/deep.txt"), "deep content\n".repeat(200));
        Files.writeString(src.resolve("readme.md"), "# sample\n");
        Files.write(src.resolve("a/b/blob.bin"), randomish(50_000));
        Files.createFile(src.resolve("a/empty"));
    }

    private static byte[] randomish(int n) {
        byte[] b = new byte[n];
        long s = 0x9E3779B97F4A7C15L;
        for (int i = 0; i < n; i++) { s ^= s << 13; s ^= s >>> 7; s ^= s << 17; b[i] = (byte) s; }
        return b;
    }

    private BuildResult run(String... args) {
        return GradleRunner.create()
                .withProjectDir(projectDir.toFile())
                .withPluginClasspath()
                .withArguments(args)
                .build();
    }

    private long verifyAllEntries(Path zip) throws IOException {
        long files = 0;
        byte[] buf = new byte[8192];
        try (ZipFile zf = new ZipFile(zip.toFile())) {
            Enumeration<? extends ZipEntry> en = zf.entries();
            while (en.hasMoreElements()) {
                ZipEntry e = en.nextElement();
                if (e.isDirectory()) continue;
                files++;
                // Reading validates the CRC; a corrupt entry throws here.
                try (InputStream in = zf.getInputStream(e)) {
                    while (in.read(buf) >= 0) { /* drain */ }
                }
            }
        }
        return files;
    }

    private String sha256(Path p) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        md.update(Files.readAllBytes(p));
        return HexFormat.of().formatHex(md.digest());
    }

    @Test
    void deflateProducesValidArchive() throws Exception {
        writeProject("    from = layout.projectDirectory.dir('staging')\n"
                + "    archiveFile = layout.buildDirectory.file('dist.zip')\n"
                + "    threads = 4\n");
        BuildResult result = run("dist");
        assertTrue(result.getOutput().contains("ParallelZip ->"), result.getOutput());
        Path zip = projectDir.resolve("build/dist.zip");
        assertTrue(Files.exists(zip));
        assertEquals(5, verifyAllEntries(zip)); // 5 files in the source tree
    }

    @Test
    void storeProducesValidArchive() throws Exception {
        writeProject("    from = layout.projectDirectory.dir('staging')\n"
                + "    archiveFile = layout.buildDirectory.file('dist.zip')\n"
                + "    store = true\n");
        run("dist");
        assertEquals(5, verifyAllEntries(projectDir.resolve("build/dist.zip")));
    }

    @Test
    void reproducibleAcrossThreadCountsAndRuns() throws Exception {
        // preserveTimestamps = false -> bytes must not depend on mtime or scheduling.
        writeProject("    from = layout.projectDirectory.dir('staging')\n"
                + "    archiveFile = layout.buildDirectory.file('dist.zip')\n"
                + "    preserveTimestamps = false\n"
                + "    threads = providers.gradleProperty('t').map { it as Integer }.orElse(8)\n");

        run("dist", "-Pt=8", "--rerun-tasks");
        String shaA = sha256(projectDir.resolve("build/dist.zip"));
        run("dist", "-Pt=1", "--rerun-tasks");
        String shaB = sha256(projectDir.resolve("build/dist.zip"));
        run("dist", "-Pt=12", "--rerun-tasks");
        String shaC = sha256(projectDir.resolve("build/dist.zip"));

        assertEquals(shaA, shaB, "SHA must not depend on thread count");
        assertEquals(shaA, shaC, "SHA must not depend on thread count");
    }

    @Test
    void intoPrefixIsApplied() throws Exception {
        writeProject("    from = layout.projectDirectory.dir('staging')\n"
                + "    into = 'myapp-1.0'\n"
                + "    archiveFile = layout.buildDirectory.file('dist.zip')\n");
        run("dist");
        try (ZipFile zf = new ZipFile(projectDir.resolve("build/dist.zip").toFile())) {
            assertTrue(zf.getEntry("myapp-1.0/readme.md") != null, "prefix should be applied to entries");
        }
    }
}
