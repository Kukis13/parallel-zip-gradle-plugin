package io.github.kukis13.parallelzip.internal;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * Bindings to a bundled native libdeflate build. libdeflate has no streaming
 * API (whole-buffer in, whole-buffer out), so this is only usable for the
 * in-memory fast path; the spilled/streamed path keeps using the JDK's
 * {@code Deflater}. Only linux-x64 and windows-x64 natives are bundled (the
 * two platforms this project's CI builds and tests); every other platform,
 * or any failure while loading, permanently falls back to pure Java with no
 * behavioural change.
 */
public final class LibdeflateNative {

    private static final boolean AVAILABLE = load();

    private LibdeflateNative() {
    }

    public static boolean available() {
        return AVAILABLE;
    }

    /**
     * Allocates a native compressor for the given DEFLATE level (1..12), to be reused
     * across many {@link #compress}/{@link #compressBatch} calls -- typically one per
     * worker thread, held for the life of a write. Returns {@code 0} on failure (e.g.
     * out of memory); callers must treat that as "native unavailable" and fall back to
     * the JDK {@link java.util.zip.Deflater} instead of passing the zero handle through.
     */
    public static native long allocCompressor(int level);

    /** Releases a handle returned by {@link #allocCompressor}. A no-op if {@code handle} is 0. */
    public static native void freeCompressor(long handle);

    /**
     * Compresses {@code in[inOff, inOff+inLen)} into {@code out[outOff, outOff+outCap)}
     * using raw DEFLATE, with the compressor identified by {@code handle}. Returns the
     * compressed length, or a negative value if the native call failed for any reason
     * (e.g. the output buffer was too small) — callers should fall back to the JDK
     * {@link java.util.zip.Deflater} in that case.
     */
    public static native int compress(long handle, byte[] in, int inOff, int inLen,
                                       byte[] out, int outOff, int outCap);

    /**
     * Compresses {@code count} independent buffers in one native call, reusing
     * {@code handle} for all of them. {@code ins[i]} is compressed in full into
     * {@code outs[i]}; {@code outLens[i]} receives the compressed length, or a negative
     * value if that one entry failed and needs a JDK {@link java.util.zip.Deflater}
     * fallback -- one entry's failure does not affect the others in the batch.
     */
    public static native void compressBatch(long handle, byte[][] ins, byte[][] outs,
                                             int[] outLens, int count);

    private static boolean load() {
        try {
            String resource = resourcePath();
            if (resource == null) {
                return false; // unsupported platform/arch; pure-Java fallback
            }
            try (InputStream in = LibdeflateNative.class.getResourceAsStream(resource)) {
                if (in == null) {
                    return false; // native lib wasn't bundled for this platform
                }
                Path tmp = Files.createTempFile("pzip-libdeflate-", suffixOf(resource));
                tmp.toFile().deleteOnExit();
                try (OutputStream out = Files.newOutputStream(tmp)) {
                    in.transferTo(out);
                }
                System.load(tmp.toAbsolutePath().toString());
            }
            return true;
        } catch (IOException | UnsatisfiedLinkError | SecurityException | UncheckedIOException e) {
            return false;
        }
    }

    private static String suffixOf(String resource) {
        int dot = resource.lastIndexOf('.');
        return dot < 0 ? "" : resource.substring(dot);
    }

    /** Null return means "no native build for this platform/arch". */
    private static String resourcePath() {
        String osName = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String archName = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        boolean isX64 = archName.equals("amd64") || archName.equals("x86_64");
        if (!isX64) {
            return null;
        }
        if (osName.contains("win")) {
            return "/natives/windows-x64/pzip_libdeflate.dll";
        }
        if (osName.contains("linux")) {
            return "/natives/linux-x64/libpzip_libdeflate.so";
        }
        return null; // macOS and others: pure-Java fallback for now
    }
}
