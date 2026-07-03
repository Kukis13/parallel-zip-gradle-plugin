package com.ljarocki.parallelzip.internal;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Verifies the streaming (spill) path against the in-memory path. */
class ParallelZipWriterStreamingTest {

    @TempDir
    Path tmp;

    private Path sampleTree() throws IOException {
        Path src = tmp.resolve("src");
        Files.createDirectories(src.resolve("a/b"));
        Files.writeString(src.resolve("a/text.txt"), "compress me ".repeat(5000));
        Files.writeString(src.resolve("a/b/more.txt"), "lorem ipsum\n".repeat(3000));
        Files.write(src.resolve("a/b/blob.bin"), pseudoRandom(200_000));
        Files.createFile(src.resolve("a/empty"));
        return src;
    }

    private static byte[] pseudoRandom(int n) {
        byte[] b = new byte[n];
        long s = 0x2545F4914F6CDD1DL;
        for (int i = 0; i < n; i++) { s ^= s << 13; s ^= s >>> 7; s ^= s << 17; b[i] = (byte) s; }
        return b;
    }

    private long write(Path src, Path out, boolean store, long spillThreshold) throws Exception {
        var sources = List.of(new ParallelZipWriter.Source(src, ""));
        // preserveTimestamps=false so both runs are byte-comparable.
        var r = ParallelZipWriter.write(sources, out, store, -1, 6, false, false, spillThreshold);
        return r.archiveSize();
    }

    @Test
    void streamedDeflateDecodesIdenticallyToInMemory() throws Exception {
        // Not byte-identical: the in-memory path may use the native libdeflate
        // codec (when bundled/available) while the streamed path always uses the
        // JDK Deflater, since libdeflate has no streaming API. Both are valid,
        // independently-decodable DEFLATE encoders of the same input, so the
        // invariant that must hold is decoded-content equality, not raw bytes.
        Path src = sampleTree();
        Path inMem = tmp.resolve("inmem.zip");
        Path streamed = tmp.resolve("streamed.zip");
        write(src, inMem, false, Long.MAX_VALUE); // never spills
        write(src, streamed, false, 0);           // every non-empty entry streams
        assertSameDecodedContents(inMem, streamed);
    }

    private static void assertSameDecodedContents(Path zipA, Path zipB) throws IOException {
        Map<String, byte[]> a = readAllEntries(zipA);
        Map<String, byte[]> b = readAllEntries(zipB);
        assertEquals(a.keySet(), b.keySet());
        for (String name : a.keySet()) {
            assertArrayEquals(a.get(name), b.get(name), "content mismatch for entry " + name);
        }
    }

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
    void streamedStoreIsByteIdenticalToInMemory() throws Exception {
        Path src = sampleTree();
        Path inMem = tmp.resolve("inmem-s.zip");
        Path streamed = tmp.resolve("streamed-s.zip");
        write(src, inMem, true, Long.MAX_VALUE);
        write(src, streamed, true, 0);
        assertEquals(-1L, Files.mismatch(inMem, streamed),
                "streamed STORE output must be byte-identical to in-memory output");
    }

    @Test
    void streamedIncompressibleEntryIsSniffedToStore() throws Exception {
        Path src = tmp.resolve("sniff-src");
        Files.createDirectories(src);
        // Both entries exceed the 256 KiB sniff threshold; spill=0 forces both through the
        // streaming path. The random blob must be STOREd by the sniff, the repetitive text
        // must still be DEFLATEd -- and both must round-trip.
        byte[] incompressible = pseudoRandom(300_000);
        byte[] compressible = "compress me ".repeat(30_000)
                .getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Files.write(src.resolve("blob.bin"), incompressible);
        Files.write(src.resolve("text.txt"), compressible);

        Path out = tmp.resolve("sniff.zip");
        var sources = List.of(new ParallelZipWriter.Source(src, ""));
        ParallelZipWriter.write(sources, out, false, -1, 4, false, false, 0); // spill=0 => everything streams

        try (ZipFile zf = new ZipFile(out.toFile())) {
            ZipEntry blob = zf.getEntry("blob.bin");
            ZipEntry text = zf.getEntry("text.txt");
            assertEquals(ZipEntry.STORED, blob.getMethod(),
                    "incompressible streamed entry must be STOREd by the sniff");
            assertEquals(ZipEntry.DEFLATED, text.getMethod(),
                    "compressible streamed entry must still be DEFLATEd");
            assertArrayEquals(incompressible, readAllEntries(out).get("blob.bin"));
            assertArrayEquals(compressible, readAllEntries(out).get("text.txt"));
        }
    }

    @Test
    void streamingComposesWithZip64() throws Exception {
        Path src = sampleTree();
        Path out = tmp.resolve("stream-z64.zip");
        var sources = List.of(new ParallelZipWriter.Source(src, ""));
        // Force streaming (spill=0) AND ZIP64 encoding at once.
        var r = ParallelZipWriter.write(sources, out, false, -1, 4, false, true, 0);
        assertTrue(r.zip64());
        int files = 0;
        byte[] buf = new byte[8192];
        try (ZipFile zf = new ZipFile(out.toFile())) {
            var en = zf.entries();
            while (en.hasMoreElements()) {
                ZipEntry e = en.nextElement();
                if (e.isDirectory()) continue;
                files++;
                try (InputStream in = zf.getInputStream(e)) { while (in.read(buf) >= 0) { } } // CRC check
            }
        }
        assertEquals(4, files);
    }

    /**
     * Real >2 GiB single entry: proves the byte[] limit is lifted. Gated (heavy on
     * disk/time); run with -Dpzip.hugeTest=true.
     */
    @Test
    @EnabledIfSystemProperty(named = "pzip.hugeTest", matches = "true")
    void singleEntryLargerThan2GiB() throws Exception {
        Path src = tmp.resolve("huge");
        Files.createDirectories(src);
        Path big = src.resolve("big.bin");
        final long size = 2200L * 1024 * 1024; // ~2.15 GiB, past Integer.MAX_VALUE
        byte[] chunk = new byte[8 << 20];
        for (int i = 0; i < chunk.length; i++) chunk[i] = (byte) (i * 31 + 7);
        try (OutputStream os = Files.newOutputStream(big)) {
            long written = 0;
            while (written < size) {
                int n = (int) Math.min(chunk.length, size - written);
                os.write(chunk, 0, n);
                written += n;
            }
        }

        Path out = tmp.resolve("huge.zip");
        var sources = List.of(new ParallelZipWriter.Source(src, ""));
        var r = ParallelZipWriter.write(sources, out, true, -1, 4, false); // STORE

        try (ZipFile zf = new ZipFile(out.toFile())) {
            ZipEntry e = zf.getEntry("big.bin");
            assertEquals(size, e.getSize());
            long read = 0;
            byte[] buf = new byte[1 << 20];
            try (InputStream in = zf.getInputStream(e)) { // validates CRC over the whole 2.15 GiB
                int n;
                while ((n = in.read(buf)) >= 0) read += n;
            }
            assertEquals(size, read);
        }
    }
}
