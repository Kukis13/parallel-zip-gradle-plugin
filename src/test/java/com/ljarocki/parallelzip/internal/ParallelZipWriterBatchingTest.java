package com.ljarocki.parallelzip.internal;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Small entries are batched into fewer compression tasks (see {@link ParallelZipWriter.Sink#add}).
 * These tests exercise that batching directly: many small entries spanning several batch
 * flushes, mixed with a few entries too large to batch, and confirm both correctness
 * (every entry decodes back to its original content, in the right relative order) and that
 * batching doesn't introduce any new source of non-determinism.
 */
class ParallelZipWriterBatchingTest {

    @TempDir
    Path tmp;

    /** Builds many small files (well under the batching threshold) plus a few large ones. */
    private Path mixedSizeTree(int smallCount) throws IOException {
        Path src = tmp.resolve("src");
        for (int i = 0; i < smallCount; i++) {
            Path dir = src.resolve(String.format("d%03d", i % 50));
            Files.createDirectories(dir);
            // Varied, non-trivial content so DEFLATE actually does some work per entry.
            Files.writeString(dir.resolve(String.format("f%05d.txt", i)),
                    ("entry " + i + " ").repeat(50 + (i % 200)), StandardCharsets.UTF_8);
        }
        // A few entries above the small-entry batching threshold, interspersed alphabetically
        // so the writer must flush a pending batch before/after handling them in order.
        Files.write(src.resolve("d000/zzz-large-1.bin"), pseudoRandom(200_000));
        Files.write(src.resolve("d025/zzz-large-2.bin"), pseudoRandom(300_000));
        return src;
    }

    private static byte[] pseudoRandom(int n) {
        byte[] b = new byte[n];
        long s = 0x2545F4914F6CDD1DL;
        for (int i = 0; i < n; i++) { s ^= s << 13; s ^= s >>> 7; s ^= s << 17; b[i] = (byte) s; }
        return b;
    }

    /** name -> uncompressed bytes, read via ZipFile (validates CRC as a side effect of reading). */
    private static Map<String, byte[]> readAllEntries(Path zip) throws IOException {
        Map<String, byte[]> out = new LinkedHashMap<>();
        try (ZipFile zf = new ZipFile(zip.toFile())) {
            var en = zf.entries();
            while (en.hasMoreElements()) {
                ZipEntry e = en.nextElement();
                if (e.isDirectory()) continue;
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                try (InputStream in = zf.getInputStream(e)) { in.transferTo(bos); }
                out.put(e.getName(), bos.toByteArray());
            }
        }
        return out;
    }

    @Test
    void manySmallEntriesAcrossMultipleBatchesDecodeToOriginalContent() throws Exception {
        // Comfortably more than one batch's worth (BATCH_MAX_ENTRIES = 64, BATCH_MAX_BYTES = 1 MiB).
        final int smallCount = 5_000;
        Path src = mixedSizeTree(smallCount);
        Path out = tmp.resolve("batched.zip");
        var sources = List.of(new ParallelZipWriter.Source(src, ""));

        ParallelZipWriter.write(sources, out, false, -1, 8, false);

        Map<String, byte[]> decoded = readAllEntries(out);
        assertEquals(smallCount + 2, decoded.size(), "every small entry plus the two large ones");

        for (int i = 0; i < smallCount; i++) {
            String name = String.format("d%03d/f%05d.txt", i % 50, i);
            byte[] expected = ("entry " + i + " ").repeat(50 + (i % 200)).getBytes(StandardCharsets.UTF_8);
            assertArrayEquals(expected, decoded.get(name), "content mismatch for " + name);
        }
        assertEquals(200_000, decoded.get("d000/zzz-large-1.bin").length);
        assertEquals(300_000, decoded.get("d025/zzz-large-2.bin").length);
    }

    @Test
    void reproducibleAcrossThreadCountsWithManySmallEntries() throws Exception {
        Path src = mixedSizeTree(3_000);
        var sources = List.of(new ParallelZipWriter.Source(src, ""));

        Path out1 = tmp.resolve("t1.zip");
        Path out8 = tmp.resolve("t8.zip");
        Path out16 = tmp.resolve("t16.zip");
        ParallelZipWriter.write(sources, out1, false, -1, 1, false);
        ParallelZipWriter.write(sources, out8, false, -1, 8, false);
        ParallelZipWriter.write(sources, out16, false, -1, 16, false);

        String sha1 = sha256(out1);
        assertEquals(sha1, sha256(out8), "1 vs 8 threads must be byte-identical with batching");
        assertEquals(sha1, sha256(out16), "1 vs 16 threads must be byte-identical with batching");
    }

    private static String sha256(Path file) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        try (InputStream in = Files.newInputStream(file)) {
            byte[] buf = new byte[1 << 16];
            int n;
            while ((n = in.read(buf)) >= 0) md.update(buf, 0, n);
        }
        return java.util.HexFormat.of().formatHex(md.digest());
    }
}
