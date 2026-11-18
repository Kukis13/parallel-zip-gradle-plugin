package com.ljarocki.parallelzip;

import com.ljarocki.parallelzip.internal.ZipDiff;
import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.Optional;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Compares two ZIP archives entry-by-entry and reports the exact entry name and field
 * ({@code mtime}, Unix mode, CRC-32, compression method, sizes, extra bytes, entry order)
 * causing any reproducibility mismatch.
 */
public abstract class VerifyZip extends DefaultTask {

    @InputFile
    @PathSensitive(PathSensitivity.NONE)
    public abstract RegularFileProperty getArchiveA();

    @InputFile
    @PathSensitive(PathSensitivity.NONE)
    public abstract RegularFileProperty getArchiveB();

    /** When {@code true} (default), fails the task if any mismatch is found. */
    @Input
    @Optional
    public abstract Property<Boolean> getFailOnMismatch();

    /** When {@code true} (default), checks that entries appear in the exact same order. */
    @Input
    @Optional
    public abstract Property<Boolean> getCheckOrder();

    /** Optional file to write the human-readable diff report to. */
    @OutputFile
    @Optional
    public abstract RegularFileProperty getReportFile();

    public VerifyZip() {
        getFailOnMismatch().convention(true);
        getCheckOrder().convention(true);
    }

    @TaskAction
    public void verify() throws IOException {
        Path a = getArchiveA().get().getAsFile().toPath();
        Path b = getArchiveB().get().getAsFile().toPath();
        boolean checkOrder = Boolean.TRUE.equals(getCheckOrder().get());

        ZipDiff.DiffResult result = ZipDiff.compare(a, b, checkOrder);
        String report = result.isIdentical()
                ? "parallel-zip: archives are bit-for-bit reproducible (" + result.leftCount() + " entries)\n"
                : result.formatReport(a, b, 50);

        if (getReportFile().isPresent()) {
            Path out = getReportFile().get().getAsFile().toPath();
            Path parent = out.toAbsolutePath().getParent();
            if (parent != null) Files.createDirectories(parent);
            Files.writeString(out, report);
        }

        if (result.isIdentical()) {
            getLogger().lifecycle(report.trim());
            return;
        }

        getLogger().error(report.trim());
        if (Boolean.TRUE.equals(getFailOnMismatch().get())) {
            throw new GradleException(report.trim());
        }
    }
}
