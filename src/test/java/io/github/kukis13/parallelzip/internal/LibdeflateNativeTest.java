package io.github.kukis13.parallelzip.internal;

import org.junit.jupiter.api.Test;

import java.util.Random;
import java.util.zip.Inflater;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Exercises the native libdeflate codec directly. Skipped (not failed) when no native
 * build is bundled for the current platform/arch, or when the build that produced this
 * jar had no C toolchain available -- both are supported, expected states, not bugs.
 */
class LibdeflateNativeTest {

    @Test
    void compressesRepetitiveDataAndRoundTrips() throws Exception {
        assumeTrue(LibdeflateNative.available(), "native libdeflate not available on this platform/build");
        byte[] input = "The quick brown fox jumps over the lazy dog. ".repeat(20_000)
                .getBytes(java.nio.charset.StandardCharsets.UTF_8);
        long handle = alloc(6);
        try {
            int compLen = compress(handle, input);
            assertTrue(compLen > 0 && compLen < input.length, "should compress well below the input size");
        } finally {
            LibdeflateNative.freeCompressor(handle);
        }
    }

    @Test
    void largeIncompressibleInputIsSniffedToStore() throws Exception {
        assumeTrue(LibdeflateNative.available(), "native libdeflate not available on this platform/build");
        byte[] input = new byte[500_000]; // >= sniff threshold (256 KiB) and incompressible
        new Random(42).nextBytes(input);
        long handle = alloc(6);
        try {
            byte[] out = new byte[input.length + (input.length >> 12) + 64];
            int n = LibdeflateNative.compress(handle, input, 0, input.length, out, 0, out.length);
            assertEquals(LibdeflateNative.STORE_SENTINEL, n,
                    "the incompressibility sniff must flag a large random buffer for STORE");
        } finally {
            LibdeflateNative.freeCompressor(handle);
        }
    }

    @Test
    void smallIncompressibleInputIsNotSniffedAndStillRoundTrips() throws Exception {
        assumeTrue(LibdeflateNative.available(), "native libdeflate not available on this platform/build");
        byte[] input = new byte[100_000]; // below the 256 KiB sniff threshold
        new Random(99).nextBytes(input);
        long handle = alloc(6);
        try {
            int cap = input.length + (input.length >> 12) + 64;
            byte[] out = new byte[cap];
            int n = LibdeflateNative.compress(handle, input, 0, input.length, out, 0, cap);
            assertTrue(n > 0, "input under the sniff threshold must be compressed, not sniffed to STORE");
            assertRoundTrips(input, out, n);
        } finally {
            LibdeflateNative.freeCompressor(handle);
        }
    }

    @Test
    void largeCompressibleInputIsCompressedNotStored() throws Exception {
        assumeTrue(LibdeflateNative.available(), "native libdeflate not available on this platform/build");
        // >= sniff threshold but highly compressible: the sniff must let it through to a
        // real compression, not mistake it for already-compressed data.
        byte[] input = "The quick brown fox jumps over the lazy dog. ".repeat(20_000)
                .getBytes(java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(input.length > 256 * 1024, "test input must exceed the sniff threshold");
        long handle = alloc(6);
        try {
            int compLen = compress(handle, input);
            assertTrue(compLen > 0 && compLen < input.length, "should compress well below the input size");
        } finally {
            LibdeflateNative.freeCompressor(handle);
        }
    }

    @Test
    void handlesEmptyInputWithoutCrashing() {
        assumeTrue(LibdeflateNative.available(), "native libdeflate not available on this platform/build");
        long handle = alloc(6);
        try {
            byte[] out = new byte[64];
            int n = LibdeflateNative.compress(handle, new byte[0], 0, 0, out, 0, out.length);
            assertTrue(n >= 0, "empty input must not fail or crash");
        } finally {
            LibdeflateNative.freeCompressor(handle);
        }
    }

    @Test
    void compressorHandleIsReusableAcrossManyCalls() throws Exception {
        assumeTrue(LibdeflateNative.available(), "native libdeflate not available on this platform/build");
        long handle = alloc(6);
        try {
            for (int i = 0; i < 500; i++) {
                byte[] input = ("entry " + i + " ").repeat(200).getBytes(java.nio.charset.StandardCharsets.UTF_8);
                int compLen = compress(handle, input);
                assertTrue(compLen > 0 && compLen < input.length, "reused handle must keep compressing correctly");
            }
        } finally {
            LibdeflateNative.freeCompressor(handle);
        }
    }

    @Test
    void compressBatchMatchesPerEntryCompression() throws Exception {
        assumeTrue(LibdeflateNative.available(), "native libdeflate not available on this platform/build");
        long handle = alloc(6);
        try {
            int n = 300;
            byte[][] ins = new byte[n][];
            byte[][] outs = new byte[n][];
            Random rnd = new Random(7);
            for (int i = 0; i < n; i++) {
                byte[] input = ("payload-" + i + "-").repeat(50 + rnd.nextInt(200))
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8);
                ins[i] = input;
                outs[i] = new byte[input.length + (input.length >> 12) + 64];
            }
            int[] outLens = new int[n];
            LibdeflateNative.compressBatch(handle, ins, outs, outLens, n);
            for (int i = 0; i < n; i++) {
                assertTrue(outLens[i] > 0, "entry " + i + " must compress successfully in a batch call");
                assertRoundTrips(ins[i], outs[i], outLens[i]);
            }
        } finally {
            LibdeflateNative.freeCompressor(handle);
        }
    }

    private static long alloc(int level) {
        long handle = LibdeflateNative.allocCompressor(level);
        assertTrue(handle != 0, "compressor allocation must succeed");
        return handle;
    }

    /** Compresses via the native codec and asserts the raw-DEFLATE round trip matches. */
    private static int compress(long handle, byte[] input) throws Exception {
        int cap = input.length + (input.length >> 12) + 64;
        byte[] out = new byte[cap];
        int compLen = LibdeflateNative.compress(handle, input, 0, input.length, out, 0, cap);
        assertTrue(compLen > 0, "native compress must succeed");
        assertRoundTrips(input, out, compLen);
        return compLen;
    }

    private static void assertRoundTrips(byte[] input, byte[] out, int compLen) throws Exception {
        Inflater inf = new Inflater(true); // nowrap = raw deflate, matching the JNI side
        inf.setInput(out, 0, compLen);
        byte[] decoded = new byte[input.length];
        int off = 0;
        while (off < decoded.length) {
            int n = inf.inflate(decoded, off, decoded.length - off);
            if (n == 0 && (inf.finished() || inf.needsInput())) break;
            off += n;
        }
        inf.end();
        assertArrayEquals(input, decoded, "round-trip mismatch");
    }
}
