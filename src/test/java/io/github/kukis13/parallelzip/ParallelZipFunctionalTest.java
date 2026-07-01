package io.github.kukis13.parallelzip;

import org.gradle.testkit.runner.BuildResult;
import org.gradle.testkit.runner.GradleRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Enumeration;
import java.util.HexFormat;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ParallelZipFunctionalTest {

    @TempDir
    Path projectDir;

    private void settings() throws IOException {
        Files.writeString(projectDir.resolve("settings.gradle"), "rootProject.name = 'sample'\n");
    }

    private void buildFile(String taskBody) throws IOException {
        Files.writeString(projectDir.resolve("build.gradle"), """
                plugins { id 'io.github.kukis13.parallel-zip' }
                import io.github.kukis13.parallelzip.ParallelZip
                import org.apache.tools.ant.filters.ReplaceTokens
                tasks.register('dist', ParallelZip) {
                    destinationDirectory = layout.buildDirectory
                    archiveFileName = 'dist.zip'
                %s
                }
                """.formatted(taskBody));
    }

    private void sampleTree() throws IOException {
        Path src = projectDir.resolve("staging");
        Files.createDirectories(src.resolve("a/b/c"));
        Files.writeString(src.resolve("a/hello.txt"), "hello ".repeat(1000));
        Files.writeString(src.resolve("a/b/c/deep.txt"), "deep\n".repeat(200));
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

    private Path archive() {
        return projectDir.resolve("build/dist.zip");
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
                try (InputStream in = zf.getInputStream(e)) { while (in.read(buf) >= 0) { } } // CRC check
            }
        }
        return files;
    }

    private String readEntry(Path zip, String name) throws IOException {
        try (ZipFile zf = new ZipFile(zip.toFile())) {
            ZipEntry e = zf.getEntry(name);
            assertNotNull(e, "missing entry: " + name);
            try (InputStream in = zf.getInputStream(e)) {
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                in.transferTo(bos);
                return bos.toString();
            }
        }
    }

    private String sha256(Path p) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        md.update(Files.readAllBytes(p));
        return HexFormat.of().formatHex(md.digest());
    }

    @Test
    void deflateProducesValidArchive() throws Exception {
        settings();
        sampleTree();
        buildFile("    from 'staging'\n    threads = 4\n");
        BuildResult result = run("dist");
        assertTrue(result.getOutput().contains("BUILD SUCCESSFUL"));
        assertEquals(5, verifyAllEntries(archive()));
    }

    @Test
    void storeProducesValidArchive() throws Exception {
        settings();
        sampleTree();
        buildFile("    from 'staging'\n    store = true\n");
        run("dist");
        assertEquals(5, verifyAllEntries(archive()));
    }

    @Test
    void reproducibleAcrossThreadCountsAndRuns() throws Exception {
        settings();
        sampleTree();
        buildFile("""
                    from 'staging'
                    preserveFileTimestamps = false
                    reproducibleFileOrder = true
                    threads = providers.gradleProperty('t').map { it as Integer }.orElse(8)
                """);
        run("dist", "-Pt=8", "--rerun-tasks");
        String shaA = sha256(archive());
        run("dist", "-Pt=1", "--rerun-tasks");
        String shaB = sha256(archive());
        run("dist", "-Pt=12", "--rerun-tasks");
        String shaC = sha256(archive());
        assertEquals(shaA, shaB, "SHA must not depend on thread count");
        assertEquals(shaA, shaC, "SHA must not depend on thread count");
    }

    @Test
    void supportsFullCopySpecDsl() throws Exception {
        settings();
        Path src = projectDir.resolve("staging");
        Files.createDirectories(src);
        Files.writeString(src.resolve("readme.md"), "# hi\n");
        Files.writeString(src.resolve("hello.txt"), "hello\n");
        Files.writeString(src.resolve("debug.log"), "noise\n");
        Path conf = projectDir.resolve("conf");
        Files.createDirectories(conf);
        Files.writeString(conf.resolve("app.properties"), "version=@version@\n");

        // Nested into, exclude, rename, filter (token replacement) — the real drop-in features.
        buildFile("""
                    into('app') {
                        from('staging') {
                            exclude '**/*.log'
                            rename 'readme.md', 'README.txt'
                        }
                    }
                    into('app/conf') {
                        from('conf') {
                            filter(ReplaceTokens, tokens: [version: '1.2.3'])
                        }
                    }
                """);
        run("dist");

        Path zip = archive();
        try (ZipFile zf = new ZipFile(zip.toFile())) {
            assertNotNull(zf.getEntry("app/README.txt"), "rename applied");
            assertNotNull(zf.getEntry("app/hello.txt"), "into prefix applied");
            assertNull(zf.getEntry("app/debug.log"), "exclude applied");
            assertNull(zf.getEntry("app/readme.md"), "old name gone after rename");
        }
        String props = readEntry(zip, "app/conf/app.properties");
        assertTrue(props.contains("version=1.2.3"), "filter replaced token: " + props);
        assertFalse(props.contains("@version@"), "token should be gone");
    }
}
