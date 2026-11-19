package com.ljarocki.parallelzip;

import org.gradle.testkit.runner.BuildResult;
import org.gradle.testkit.runner.GradleRunner;
import org.gradle.testkit.runner.TaskOutcome;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SharedPoolAndFfmTest {

    @TempDir
    Path projectDir;

    @Test
    void multiProjectParallelBuildSharesBuildServicePoolAndConfigurationCache() throws Exception {
        Files.writeString(projectDir.resolve("settings.gradle"), """
                rootProject.name = 'multi'
                include 'sub1', 'sub2', 'sub3', 'sub4'
                """);

        for (String sub : List.of("sub1", "sub2", "sub3", "sub4")) {
            Path subDir = projectDir.resolve(sub);
            Files.createDirectories(subDir.resolve("src"));
            Files.writeString(subDir.resolve("src/data.txt"), ("data for " + sub + "\n").repeat(200));
            Files.writeString(subDir.resolve("build.gradle"), """
                    plugins { id 'com.ljarocki.parallel-zip' }
                    parallelZip {
                        threads = 2
                    }
                    tasks.register('dist', Zip) {
                        from 'src'
                        destinationDirectory = layout.buildDirectory
                        archiveFileName = 'dist.zip'
                    }
                    """);
        }

        BuildResult first = GradleRunner.create()
                .withProjectDir(projectDir.toFile())
                .withPluginClasspath()
                .withArguments("dist", "--parallel", "--configuration-cache")
                .build();

        for (String sub : List.of("sub1", "sub2", "sub3", "sub4")) {
            assertEquals(TaskOutcome.SUCCESS, first.task(":" + sub + ":dist").getOutcome());
            Path zip = projectDir.resolve(sub + "/build/dist.zip");
            try (ZipFile zf = new ZipFile(zip.toFile())) {
                assertNotNull(zf.getEntry("data.txt"));
            }
        }
        assertTrue(first.getOutput().contains("Configuration cache entry stored."));

        for (String sub : List.of("sub1", "sub2", "sub3", "sub4")) {
            Files.deleteIfExists(projectDir.resolve(sub + "/build/dist.zip"));
        }
        BuildResult second = GradleRunner.create()
                .withProjectDir(projectDir.toFile())
                .withPluginClasspath()
                .withArguments("dist", "--parallel", "--configuration-cache")
                .build();
        assertTrue(second.getOutput().contains("Configuration cache entry reused."));
        for (String sub : List.of("sub1", "sub2", "sub3", "sub4")) {
            assertEquals(TaskOutcome.SUCCESS, second.task(":" + sub + ":dist").getOutcome());
            assertTrue(Files.exists(projectDir.resolve(sub + "/build/dist.zip")));
        }
    }

    @Test
    void builtJarContainsMultiReleaseJava22MappedFileView() throws IOException {
        if (!Files.exists(Path.of("build/classes/java22/main"))) return;
        Path libsDir = Path.of("build/libs");
        if (!Files.exists(libsDir)) return;
        try (var s = Files.list(libsDir)) {
            Path jar = s.filter(p -> p.getFileName().toString().endsWith(".jar")
                    && !p.getFileName().toString().endsWith("-sources.jar")
                    && !p.getFileName().toString().endsWith("-javadoc.jar"))
                    .max(java.util.Comparator.comparingLong(p -> p.toFile().lastModified()))
                    .orElse(null);
            if (jar == null) return;
            try (ZipFile zf = new ZipFile(jar.toFile())) {
                assertNotNull(zf.getEntry("META-INF/versions/22/com/ljarocki/parallelzip/internal/MappedFileView.class"),
                        "Multi-Release JAR (" + jar.getFileName() + ") must include JDK 22 MappedFileView");
            }
        }
    }
}
