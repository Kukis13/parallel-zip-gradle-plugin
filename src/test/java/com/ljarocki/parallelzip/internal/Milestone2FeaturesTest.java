package com.ljarocki.parallelzip.internal;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Milestone2FeaturesTest {

    @TempDir
    Path tempDir;

    @Test
    void crc32CombineMatchesSinglePassCrc() {
        byte[] part1 = "first chunk of data ".repeat(123).getBytes(StandardCharsets.UTF_8);
        byte[] part2 = "second chunk of data!".repeat(77).getBytes(StandardCharsets.UTF_8);
        byte[] both = new byte[part1.length + part2.length];
        System.arraycopy(part1, 0, both, 0, part1.length);
        System.arraycopy(part2, 0, both, part1.length, part2.length);

        CRC32 c1 = new CRC32();
        c1.update(part1);
        CRC32 c2 = new CRC32();
        c2.update(part2);
        CRC32 cBoth = new CRC32();
        cBoth.update(both);

        assertEquals(cBoth.getValue(), ParallelZipWriter.crc32Combine(c1.getValue(), c2.getValue(), part2.length));
    }

    @Test
    void level12AndNativeFilesBatchAndPwriteWorkEndToEnd() throws Exception {
        Path src = tempDir.resolve("src");
        Files.createDirectories(src);
        List<ParallelZipWriter.Entry> entries = new ArrayList<>();
        // 100 small files to trigger compressFilesBatch
        for (int i = 0; i < 100; i++) {
            Path f = src.resolve("small-" + i + ".txt");
            byte[] data = ("small entry " + i + " ").repeat(30).getBytes(StandardCharsets.UTF_8);
            Files.write(f, data);
            entries.add(ParallelZipWriter.fileEntry("small-" + i + ".txt", f, false, data.length,
                    ParallelZipWriter.FIXED_DOS_TIME, ParallelZipWriter.FIXED_DOS_DATE,
                    (ParallelZipWriter.DEFAULT_FILE_MODE << 16)));
        }
        // Large (> 256 KiB) entry to trigger parallel pwrite in runWriter
        Path large = src.resolve("large.txt");
        byte[] largeData = "large pwrite compressible block\n".repeat(20_000).getBytes(StandardCharsets.UTF_8);
        Files.write(large, largeData);
        entries.add(ParallelZipWriter.fileEntry("large.txt", large, false, largeData.length,
                ParallelZipWriter.FIXED_DOS_TIME, ParallelZipWriter.FIXED_DOS_DATE,
                (ParallelZipWriter.DEFAULT_FILE_MODE << 16)));

        Path out = tempDir.resolve("out-l12.zip");
        ParallelZipWriter.writeEntries(entries, out, false, true, 12, 4, false,
                ParallelZipWriter.SPILL_THRESHOLD, null);

        try (ZipFile zf = new ZipFile(out.toFile())) {
            assertEquals(101, zf.size());
            try (InputStream in = zf.getInputStream(zf.getEntry("large.txt"))) {
                assertArrayEquals(largeData, in.readAllBytes());
            }
            for (int i = 0; i < 100; i++) {
                byte[] expected = ("small entry " + i + " ").repeat(30).getBytes(StandardCharsets.UTF_8);
                try (InputStream in = zf.getInputStream(zf.getEntry("small-" + i + ".txt"))) {
                    assertArrayEquals(expected, in.readAllBytes());
                }
            }
        }
    }

    @Test
    void zipPatcherReusesUnchangedEntriesAndUpdatesModifiedOnes() throws Exception {
        Path src = tempDir.resolve("patch-src");
        Files.createDirectories(src);
        Path f1 = src.resolve("unchanged.txt");
        Path f2 = src.resolve("changed.txt");
        byte[] d1 = "unchanged content ".repeat(100).getBytes(StandardCharsets.UTF_8);
        byte[] d2v1 = "version 1 content ".repeat(50).getBytes(StandardCharsets.UTF_8);
        Files.write(f1, d1);
        Files.write(f2, d2v1);

        int t1 = ParallelZipWriter.FIXED_DOS_TIME;
        int d = ParallelZipWriter.FIXED_DOS_DATE;
        int attr = ParallelZipWriter.DEFAULT_FILE_MODE << 16;

        Path prior = tempDir.resolve("prior.zip");
        ParallelZipWriter.writeEntries(List.of(
                ParallelZipWriter.fileEntry("unchanged.txt", f1, false, d1.length, t1, d, attr),
                ParallelZipWriter.fileEntry("changed.txt", f2, false, d2v1.length, t1, d, attr)
        ), prior, false, true, 6, 2, false, ParallelZipWriter.SPILL_THRESHOLD, null);

        byte[] d2v2 = "version 2 updated content!".repeat(60).getBytes(StandardCharsets.UTF_8);
        Files.write(f2, d2v2);
        Path patched = tempDir.resolve("patched.zip");
        int reused = ZipPatcher.patch(prior, patched, List.of(
                ParallelZipWriter.fileEntry("unchanged.txt", f1, false, d1.length, t1, d, attr),
                ParallelZipWriter.fileEntry("changed.txt", f2, false, d2v2.length, t1 + 1, d, attr)
        ), false, true, 6, 2);

        assertEquals(1, reused, "unchanged.txt should be reused without re-compression");
        assertTrue(Files.exists(patched));
        try (ZipFile zf = new ZipFile(patched.toFile())) {
            ZipEntry e1 = zf.getEntry("unchanged.txt");
            ZipEntry e2 = zf.getEntry("changed.txt");
            try (InputStream in1 = zf.getInputStream(e1); InputStream in2 = zf.getInputStream(e2)) {
                assertArrayEquals(d1, in1.readAllBytes());
                assertArrayEquals(d2v2, in2.readAllBytes());
            }
        }
    }

    @Test
    void nativeCompressNonFinalStitchesWithFinalChunkCleanly() throws Exception {
        if (!LibdeflateNative.available()) return;
        byte[] chunk1 = "libdeflate nonfinal chunk one ".repeat(3000).getBytes(StandardCharsets.UTF_8);
        byte[] chunk2 = "libdeflate final chunk two!   ".repeat(3000).getBytes(StandardCharsets.UTF_8);
        byte[] expected = new byte[chunk1.length + chunk2.length];
        System.arraycopy(chunk1, 0, expected, 0, chunk1.length);
        System.arraycopy(chunk2, 0, expected, chunk1.length, chunk2.length);

        long h = LibdeflateNative.allocCompressor(6);
        assertTrue(h != 0L);
        try {
            byte[] out1 = new byte[chunk1.length + 1024];
            byte[] out2 = new byte[chunk2.length + 1024];
            long[] crc1 = new long[1];
            long[] crc2 = new long[1];
            int n1 = LibdeflateNative.compressNonFinal(h, chunk1, 0, chunk1.length, out1, 0, out1.length, crc1);
            int n2 = LibdeflateNative.compress(h, chunk2, 0, chunk2.length, out2, 0, out2.length, crc2);
            assertTrue(n1 > 0 && n2 > 0);

            byte[] stitched = new byte[n1 + n2];
            System.arraycopy(out1, 0, stitched, 0, n1);
            System.arraycopy(out2, 0, stitched, n1, n2);

            var inf = new java.util.zip.Inflater(true);
            inf.setInput(stitched);
            byte[] decoded = new byte[expected.length];
            int total = 0;
            while (!inf.finished()) {
                int r = inf.inflate(decoded, total, decoded.length - total);
                if (r == 0) break;
                total += r;
            }
            inf.end();
            assertEquals(expected.length, total);
            assertArrayEquals(expected, decoded);
        } finally {
            LibdeflateNative.freeCompressor(h);
        }
    }
}
