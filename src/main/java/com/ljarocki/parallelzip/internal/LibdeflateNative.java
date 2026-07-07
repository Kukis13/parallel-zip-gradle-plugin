package com.ljarocki.parallelzip.internal;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Set;

/**
 * Bindings to a bundled native libdeflate build. libdeflate has no streaming
 * API (whole-buffer in, whole-buffer out), so this is only usable for the
 * in-memory fast path; the spilled/streamed path keeps using the JDK's
 * {@code Deflater}. Only linux-x64, linux-arm64, windows-x64, windows-arm64,
 * macos-arm64 and macos-x64 natives are bundled (the platforms this project's
 * CI builds and tests); every other platform/arch, or any failure while
 * loading, permanently falls back to pure Java with no behavioural change.
 */
public final class LibdeflateNative {

    // The only os-arch combos actually built and bundled by CI. Anything else
    // (e.g. linux-riscv64) falls back to pure Java rather than guessing at a
    // resource path that was never packaged into the jar. Must be declared
    // before AVAILABLE: its initializer (load() -> resourcePath()) runs at
    // class-init time and needs this already set.
    private static final Set<String> SUPPORTED_CLASSIFIERS = Set.of(
            "windows-x64", "windows-arm64", "linux-x64", "linux-arm64", "macos-arm64", "macos-x64");

    private static final boolean AVAILABLE = load();

    /**
     * Sentinel returned by {@link #compress}/{@link #compressBatch} (as the entry's length)
     * when the native incompressibility sniff judged a large input to be already compressed:
     * the caller should STORE the whole entry instead of compressing it. Distinct from the
     * {@code -1} "call failed, use the JDK Deflater" signal.
     */
    public static final int STORE_SENTINEL = -2;

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
     * compressed length; {@link #STORE_SENTINEL} ({@code -2}) if the sniff judged the input
     * already compressed and it should be STOREd; or {@code -1} if the native call failed
     * for any reason (e.g. the output buffer was too small), in which case callers should
     * fall back to the JDK {@link java.util.zip.Deflater}. {@code crcOut[0]} always receives
     * the CRC-32 of the input, computed natively while the array is already pinned for
     * compression -- regardless of the return value -- so callers never need a separate
     * {@link java.util.zip.CRC32} pass over the same bytes.
     */
    public static native int compress(long handle, byte[] in, int inOff, int inLen,
                                       byte[] out, int outOff, int outCap, long[] crcOut);

    /**
     * Compresses {@code count} independent buffers in one native call, reusing
     * {@code handle} for all of them. {@code ins[i]} is compressed in full into
     * {@code outs[i]}; {@code outLens[i]} receives the compressed length, {@link #STORE_SENTINEL}
     * ({@code -2}) if the sniff says to STORE it, or {@code -1} if that one entry failed and
     * needs a JDK {@link java.util.zip.Deflater} fallback -- one entry's failure does not
     * affect the others in the batch. {@code crcs[i]} always receives the CRC-32 of
     * {@code ins[i]}, computed natively alongside compression, regardless of {@code outLens[i]}.
     */
    public static native void compressBatch(long handle, byte[][] ins, byte[][] outs,
                                             int[] outLens, long[] crcs, int count);

    /**
     * Compresses directly from a direct (typically memory-mapped) {@code ByteBuffer} --
     * {@code in[0, inLen)} -- into {@code out[outOff, outOff+outCap)}, without ever copying
     * the input through a JVM heap {@code byte[]}. Used for the large-entry fast path, where
     * {@code in} is a {@link java.nio.MappedByteBuffer} backed by the OS page cache instead
     * of a {@code byte[]} read via {@link Files#readAllBytes}. Same return convention as
     * {@link #compress}, including the fused {@code crcOut[0]}. {@code in} must be direct;
     * a non-direct buffer causes a native failure ({@code -1}), not a crash.
     */
    public static native int compressDirect(long handle, ByteBuffer in, int inLen,
                                             byte[] out, int outOff, int outCap, long[] crcOut);

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

        String os = osName.contains("win") ? "windows"
                : osName.contains("linux") ? "linux"
                : (osName.contains("mac") || osName.contains("darwin")) ? "macos"
                : null;
        String arch = (archName.equals("amd64") || archName.equals("x86_64")) ? "x64"
                : (archName.equals("aarch64") || archName.equals("arm64")) ? "arm64"
                : null;
        if (os == null || arch == null) {
            return null;
        }

        String classifier = os + "-" + arch;
        if (!SUPPORTED_CLASSIFIERS.contains(classifier)) {
            return null;
        }
        String libFile = os.equals("windows") ? "pzip_libdeflate.dll"
                : os.equals("macos") ? "libpzip_libdeflate.dylib"
                : "libpzip_libdeflate.so";
        return "/natives/" + classifier + "/" + libFile;
    }
}
