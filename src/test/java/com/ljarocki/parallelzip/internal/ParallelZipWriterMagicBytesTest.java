package com.ljarocki.parallelzip.internal;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A signature match must win even when the entry's body would otherwise look statistically
 * compressible to {@link ParallelZipWriter#looksIncompressible}: the whole point of
 * {@link ParallelZipWriter#looksAlreadyCompressed(byte[], int)} is to short-circuit before the
 * sample-based probe can be fooled by a leading stored/uncompressed section (e.g. a jar's
 * {@code META-INF/MANIFEST.MF}) that isn't representative of the container's bulk content.
 */
class ParallelZipWriterMagicBytesTest {

    @TempDir
    Path tmp;

    /** ZIP local file header signature, followed by content that deflates extremely well. */
    private static byte[] fakeZipWithCompressibleBody(int bodyRepeats) {
        byte[] header = {0x50, 0x4B, 0x03, 0x04};
        byte[] body = "compress me ".repeat(bodyRepeats)
                .getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] out = new byte[header.length + body.length];
        System.arraycopy(header, 0, out, 0, header.length);
        System.arraycopy(body, 0, out, header.length, body.length);
        return out;
    }

    /** gzip magic, followed by content that deflates extremely well. */
    private static byte[] fakeGzipWithCompressibleBody(int bodyRepeats) {
        byte[] header = {0x1F, (byte) 0x8B, 0x08, 0x00};
        byte[] body = "compress me ".repeat(bodyRepeats)
                .getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] out = new byte[header.length + body.length];
        System.arraycopy(header, 0, out, 0, header.length);
        System.arraycopy(body, 0, out, header.length, body.length);
        return out;
    }

    @Test
    void smallInMemoryEntryWithZipMagicIsStoredDespiteCompressibleBody() throws Exception {
        Path src = tmp.resolve("small-src");
        Files.createDirectories(src);
        // Small enough to take the in-memory compressBytes() path, not compressLarge().
        Files.write(src.resolve("nested.jar"), fakeZipWithCompressibleBody(2_000));

        Path out = tmp.resolve("small.zip");
        var sources = List.of(new ParallelZipWriter.Source(src, ""));
        ParallelZipWriter.write(sources, out, false, -1, 4, false);

        try (ZipFile zf = new ZipFile(out.toFile())) {
            assertEquals(ZipEntry.STORED, zf.getEntry("nested.jar").getMethod(),
                    "ZIP-signature entry must be STOREd even though its body deflates well");
        }
    }

    @Test
    void largeFileBackedEntryWithGzipMagicIsStoredDespiteCompressibleBody() throws Exception {
        Path src = tmp.resolve("large-src");
        Files.createDirectories(src);
        // Large enough (with a tiny spillThreshold below) to take the file-backed
        // compressLarge() path, which is what feeds both tryMmapNativeDeflate and the
        // streamed JDK Deflater fallback.
        Files.write(src.resolve("archive.tar.gz"), fakeGzipWithCompressibleBody(200_000));

        Path out = tmp.resolve("large.zip");
        var sources = List.of(new ParallelZipWriter.Source(src, ""));
        ParallelZipWriter.write(sources, out, false, -1, 4, false, false, 1_000);

        try (ZipFile zf = new ZipFile(out.toFile())) {
            assertEquals(ZipEntry.STORED, zf.getEntry("archive.tar.gz").getMethod(),
                    "gzip-signature entry must be STOREd even though its body deflates well");
        }
    }

    @Test
    void plainCompressibleEntryIsUnaffected() throws Exception {
        Path src = tmp.resolve("plain-src");
        Files.createDirectories(src);
        Files.writeString(src.resolve("text.txt"), "compress me ".repeat(5_000));

        Path out = tmp.resolve("plain.zip");
        var sources = List.of(new ParallelZipWriter.Source(src, ""));
        ParallelZipWriter.write(sources, out, false, -1, 4, false);

        try (ZipFile zf = new ZipFile(out.toFile())) {
            assertEquals(ZipEntry.DEFLATED, zf.getEntry("text.txt").getMethod(),
                    "an entry with no known-compressed signature must still be DEFLATEd normally");
        }
    }
}
