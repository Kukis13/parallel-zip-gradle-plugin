package io.github.kukis13.parallelzip.internal;

import org.junit.jupiter.api.Test;

import java.util.Random;
import java.util.zip.Inflater;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
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
        int compLen = compress(input, 6);
        assertTrue(compLen > 0 && compLen < input.length, "should compress well below the input size");
    }

    @Test
    void compressesIncompressibleRandomDataAndRoundTrips() throws Exception {
        assumeTrue(LibdeflateNative.available(), "native libdeflate not available on this platform/build");
        byte[] input = new byte[500_000];
        new Random(42).nextBytes(input);
        int compLen = compress(input, 6);
        assertTrue(compLen > 0, "native compress must succeed even when incompressible");
    }

    @Test
    void handlesEmptyInputWithoutCrashing() {
        assumeTrue(LibdeflateNative.available(), "native libdeflate not available on this platform/build");
        byte[] out = new byte[64];
        int n = LibdeflateNative.compress(new byte[0], 0, 0, out, 0, out.length, 6);
        assertTrue(n >= 0, "empty input must not fail or crash");
    }

    /** Compresses via the native codec and asserts the raw-DEFLATE round trip matches. */
    private static int compress(byte[] input, int level) throws Exception {
        int cap = input.length + (input.length >> 12) + 64;
        byte[] out = new byte[cap];
        int compLen = LibdeflateNative.compress(input, 0, input.length, out, 0, cap, level);
        assertTrue(compLen > 0, "native compress must succeed");

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
        return compLen;
    }
}
