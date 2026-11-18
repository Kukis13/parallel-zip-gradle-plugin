package com.ljarocki.parallelzip.internal;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.nio.file.Path;

/**
 * JDK 22+ FFM {@link Linker} bindings to the native {@code pzip_libdeflate} library,
 * enabling direct {@link MemorySegment}-to-{@link MemorySegment} compression and native
 * {@code pwrite} without JNI {@code GetPrimitiveArrayCritical} pinning.
 */
final class NativeDeflateFFM {

    private static final boolean AVAILABLE;
    private static final MethodHandle ALLOC_COMPRESSOR;
    private static final MethodHandle FREE_COMPRESSOR;
    private static final MethodHandle DEFLATE_MEM;
    private static final MethodHandle PWRITE_PATH;

    static {
        boolean ok = false;
        MethodHandle alloc = null;
        MethodHandle free = null;
        MethodHandle deflate = null;
        MethodHandle pwrite = null;
        try {
            Path libPath = LibdeflateNative.loadedLibraryPath();
            if (LibdeflateNative.available() && libPath != null) {
                SymbolLookup lookup = SymbolLookup.libraryLookup(libPath, Arena.global());
                Linker linker = Linker.nativeLinker();
                alloc = linker.downcallHandle(
                        lookup.find("pzip_alloc_compressor").orElseThrow(),
                        FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.JAVA_INT));
                free = linker.downcallHandle(
                        lookup.find("pzip_free_compressor").orElseThrow(),
                        FunctionDescriptor.ofVoid(ValueLayout.ADDRESS));
                deflate = linker.downcallHandle(
                        lookup.find("pzip_deflate_mem").orElseThrow(),
                        FunctionDescriptor.of(ValueLayout.JAVA_INT,
                                ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG,
                                ValueLayout.ADDRESS, ValueLayout.JAVA_LONG, ValueLayout.ADDRESS));
                pwrite = linker.downcallHandle(
                        lookup.find("pzip_pwrite_path").orElseThrow(),
                        FunctionDescriptor.of(ValueLayout.JAVA_INT,
                                ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG));
                ok = true;
            }
        } catch (Throwable ignored) {
            ok = false;
        }
        AVAILABLE = ok;
        ALLOC_COMPRESSOR = alloc;
        FREE_COMPRESSOR = free;
        DEFLATE_MEM = deflate;
        PWRITE_PATH = pwrite;
    }

    private NativeDeflateFFM() { }

    static boolean isAvailable() {
        return AVAILABLE;
    }

    static MemorySegment allocCompressor(int level) {
        if (!AVAILABLE) return MemorySegment.NULL;
        try {
            return (MemorySegment) ALLOC_COMPRESSOR.invokeExact(level);
        } catch (Throwable t) {
            return MemorySegment.NULL;
        }
    }

    static void freeCompressor(MemorySegment handle) {
        if (!AVAILABLE || handle == null || handle.equals(MemorySegment.NULL)) return;
        try {
            FREE_COMPRESSOR.invokeExact(handle);
        } catch (Throwable ignored) { }
    }

    static int deflateMem(MemorySegment compressor, MemorySegment in, long inLen,
                          MemorySegment out, long outCap, MemorySegment crcOut) {
        if (!AVAILABLE) return -1;
        try {
            return (int) DEFLATE_MEM.invokeExact(compressor, in, inLen, out, outCap, crcOut);
        } catch (Throwable t) {
            return -1;
        }
    }

    static int pwritePath(String path, MemorySegment buf, long len, long offset) {
        if (!AVAILABLE) return -1;
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment cPath = arena.allocateFrom(path);
            return (int) PWRITE_PATH.invokeExact(cPath, buf, len, offset);
        } catch (Throwable t) {
            return -1;
        }
    }
}
