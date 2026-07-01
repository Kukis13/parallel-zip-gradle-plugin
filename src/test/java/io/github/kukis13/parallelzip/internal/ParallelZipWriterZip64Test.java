package io.github.kukis13.parallelzip.internal;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Exercises the ZIP64 encoding of {@link ParallelZipWriter} directly. */
class ParallelZipWriterZip64Test {

    @TempDir
    Path tmp;

    private static boolean containsSignature(byte[] data, int sig) {
        byte b0 = (byte) sig, b1 = (byte) (sig >>> 8), b2 = (byte) (sig >>> 16), b3 = (byte) (sig >>> 24);
        for (int i = 0; i + 3 < data.length; i++) {
            if (data[i] == b0 && data[i + 1] == b1 && data[i + 2] == b2 && data[i + 3] == b3) return true;
        }
        return false;
    }

    /** Read every entry via ZipFile (central directory) validating CRCs; return name->uncompressed size. */
    private static Map<String, Long> readViaZipFile(Path zip) throws IOException {
        Map<String, Long> sizes = new HashMap<>();
        byte[] buf = new byte[8192];
        try (ZipFile zf = new ZipFile(zip.toFile())) {
            var en = zf.entries();
            while (en.hasMoreElements()) {
                ZipEntry e = en.nextElement();
                if (e.isDirectory()) continue;
                long read = 0;
                try (InputStream in = zf.getInputStream(e)) {
                    int n;
                    while ((n = in.read(buf)) >= 0) read += n; // validates CRC
                }
                sizes.put(e.getName(), read);
            }
        }
        return sizes;
    }

    @Test
    void forcedZip64ProducesValidReadableArchive() throws Exception {
        Path src = tmp.resolve("src");
        Files.createDirectories(src.resolve("nested"));
        Files.writeString(src.resolve("readme.txt"), "hello world\n".repeat(500));
        Files.writeString(src.resolve("nested/data.txt"), "x".repeat(12345));
        Files.write(src.resolve("nested/blob.bin"), new byte[4096]);

        Path out = tmp.resolve("forced.zip");
        var sources = List.of(new ParallelZipWriter.Source(src, "root"));
        // forceZip64 = true -> emit ZIP64 extra fields + EOCD64 despite tiny sizes.
        ParallelZipWriter.Result r =
                ParallelZipWriter.write(sources, out, false, -1, 4, false, true);

        assertTrue(r.zip64(), "writer should report ZIP64 was used");

        byte[] bytes = Files.readAllBytes(out);
        assertTrue(containsSignature(bytes, ParallelZipWriter.EOCD64_SIG), "ZIP64 EOCD record present");
        assertTrue(containsSignature(bytes, ParallelZipWriter.EOCD64_LOC_SIG), "ZIP64 EOCD locator present");

        // Central-directory read: sizes come from the ZIP64 extra (32-bit fields are 0xFFFFFFFF).
        Map<String, Long> sizes = readViaZipFile(out);
        assertEquals(3, sizes.size());
        assertEquals(6000L, sizes.get("root/readme.txt"));   // 12 * 500
        assertEquals(12345L, sizes.get("root/nested/data.txt"));
        assertEquals(4096L, sizes.get("root/nested/blob.bin"));

        // Streaming read exercises the LOCAL headers' ZIP64 extra fields too.
        int filesSeen = 0;
        try (ZipInputStream zis = new ZipInputStream(Files.newInputStream(out))) {
            ZipEntry e;
            byte[] buf = new byte[8192];
            while ((e = zis.getNextEntry()) != null) {
                if (e.isDirectory()) continue;
                filesSeen++;
                while (zis.read(buf) >= 0) { /* drain, validates CRC */ }
            }
        }
        assertEquals(3, filesSeen);
    }

    @Test
    void moreThan65535EntriesTriggersZip64() throws Exception {
        Path src = tmp.resolve("many");
        // Spread across a few dirs to avoid one pathologically large directory.
        final int count = 66_000;
        for (int i = 0; i < count; i++) {
            Path dir = src.resolve(String.format("d%02d", i % 100));
            Files.createDirectories(dir);
            Files.writeString(dir.resolve("f" + i + ".txt"), Integer.toString(i), StandardCharsets.UTF_8);
        }

        Path out = tmp.resolve("many.zip");
        var sources = List.of(new ParallelZipWriter.Source(src, ""));
        ParallelZipWriter.Result r =
                ParallelZipWriter.write(sources, out, true, -1, 8, false);

        assertTrue(r.zip64(), "> 65535 entries must switch to ZIP64");
        assertEquals(count, r.entryCount() - countDirs(src));
        assertEquals(count, readViaZipFile(out).size(), "all entries must be present and readable");
    }

    private static long countDirs(Path root) throws IOException {
        try (var s = Files.walk(root)) {
            return s.filter(Files::isDirectory).count() - 1; // exclude root itself
        }
    }
}
