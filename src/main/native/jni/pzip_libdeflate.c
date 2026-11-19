#ifndef _WIN32
#ifndef _POSIX_C_SOURCE
#define _POSIX_C_SOURCE 200809L
#endif
#endif

#include <jni.h>
#include <stddef.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include "libdeflate.h"

#ifdef _WIN32
#define WIN32_LEAN_AND_MEAN
#include <windows.h>
#define PZIP_EXPORT __declspec(dllexport)
#else
#include <errno.h>
#include <fcntl.h>
#include <unistd.h>
#define PZIP_EXPORT __attribute__((visibility("default")))
#endif

/*
 * The compressor is now allocated once per worker thread (see
 * LibdeflateNative.allocCompressor / freeCompressor) and reused across every
 * entry that thread compresses, instead of alloc+free on every single call.
 * libdeflate_alloc_compressor sizes internal match-finder tables up front, so
 * paying that cost per call was real overhead on archives with many small
 * entries. libdeflate has no streaming API (whole-buffer in, whole-buffer
 * out), which is why this is only used for the in-memory fast path, not
 * spilled entries.
 */

/*
 * Incompressibility sniff. Archives of jars/.gz/.png/... are ~entirely
 * already-compressed bytes: DEFLATE can't shrink them, so compressing each
 * entry in full only to discover it didn't help (and STORE it anyway) is
 * wasted CPU -- the dominant cost on jar-heavy archives. Instead, for a
 * large-enough entry, deflate just the first PZIP_SNIFF_SAMPLE bytes as a
 * probe; if that barely shrinks, return PZIP_STORE_SENTINEL so the caller
 * STOREs the whole entry without compressing the rest.
 *
 * The probe is written into the caller's output buffer (always at least
 * input-sized), so no scratch allocation is needed; on a compressible entry
 * the probe bytes are simply overwritten by the full compression that follows.
 * The threshold is deliberately conservative (store only when the sample saves
 * < 2%) so genuinely compressible entries are never mis-stored; the decision
 * is a pure function of the content, so output stays deterministic.
 */
#define PZIP_STORE_SENTINEL   (-2)
#define PZIP_SNIFF_MIN_INPUT  (256u * 1024u)
#define PZIP_SNIFF_SAMPLE     (64u * 1024u)
/* Treat as incompressible when the probe compresses to >= this % of its size. */
#define PZIP_SNIFF_KEEP_PCT   98u

/*
 * Fast ~2 us 4 KiB byte-frequency pre-check: if all 256 byte values appear and the max
 * frequency is <= 36 (vs uniform mean 16), the data is near-8-bits/byte random/encrypted/
 * compressed and can be STOREd without paying the 64 KiB DEFLATE match-finder probe.
 */
static int pzip_looks_incompressible_fast(const uint8_t *inp, size_t in_len) {
    if (in_len < PZIP_SNIFF_MIN_INPUT) return 0;
    uint16_t counts[256] = {0};
    for (size_t i = 0; i < 4096u; i++) {
        counts[inp[i]]++;
    }
    uint16_t max_c = 0;
    for (int i = 0; i < 256; i++) {
        if (counts[i] == 0) return 0;
        if (counts[i] > max_c) max_c = counts[i];
    }
    return max_c <= 36u;
}

/*
 * Compress one whole buffer, sniffing large inputs first. Returns the
 * compressed length (> 0), PZIP_STORE_SENTINEL (-2) when the sniff says the
 * input is already compressed and should be STOREd, or -1 on any failure
 * (output buffer too small) so the caller can fall back to the JDK Deflater.
 */
static jint pzip_compress_or_store(struct libdeflate_compressor *c,
                                   const void *inp, size_t in_len,
                                   void *outp, size_t out_cap) {
    if (in_len >= PZIP_SNIFF_MIN_INPUT) {
        if (pzip_looks_incompressible_fast((const uint8_t *) inp, in_len)) {
            return PZIP_STORE_SENTINEL;
        }
        size_t probe = libdeflate_deflate_compress(c, inp, PZIP_SNIFF_SAMPLE, outp, out_cap);
        if (probe == 0 || probe * 100u >= (size_t) PZIP_SNIFF_SAMPLE * PZIP_SNIFF_KEEP_PCT) {
            return PZIP_STORE_SENTINEL;
        }
        /* Looks compressible: compress the whole buffer below, overwriting the
         * probe bytes already sitting in outp. */
    }
    size_t result = libdeflate_deflate_compress(c, inp, in_len, outp, out_cap);
    return (result == 0) ? -1 : (jint) result;
}

JNIEXPORT jlong JNICALL Java_com_ljarocki_parallelzip_internal_LibdeflateNative_allocCompressor(
        JNIEnv *env, jclass clazz, jint level) {
    (void) env;
    (void) clazz;
    if (level < 1) level = 1;
    if (level > 12) level = 12;
    struct libdeflate_compressor *c = libdeflate_alloc_compressor(level);
    return (jlong) (intptr_t) c;
}

JNIEXPORT void JNICALL Java_com_ljarocki_parallelzip_internal_LibdeflateNative_freeCompressor(
        JNIEnv *env, jclass clazz, jlong handle) {
    (void) env;
    (void) clazz;
    struct libdeflate_compressor *c = (struct libdeflate_compressor *) (intptr_t) handle;
    if (c != NULL) {
        libdeflate_free_compressor(c);
    }
}

/*
 * Sets crcOut[0] to a fully-computed jlong CRC-32. Done as a separate JNI call
 * from the compress work above it so it never runs while a critical array is
 * still pinned -- the JNI spec forbids calling back into other JNI functions
 * inside a Get/ReleasePrimitiveArrayCritical section.
 */
static void pzip_set_crc_out(JNIEnv *env, jlongArray crcOut, uint32_t crc) {
    jlong crc64 = (jlong) crc;
    (*env)->SetLongArrayRegion(env, crcOut, 0, 1, &crc64);
}

/* Returns the compressed length, PZIP_STORE_SENTINEL (-2) when the sniff says
 * to STORE, or -1 on failure (caller falls back to the JDK Deflater). crcOut[0]
 * always receives the CRC-32 of the input -- computed here, adjacent to
 * compression, while the array is already pinned -- so the Java side never
 * needs its own separate full-buffer CRC32 pass. */
JNIEXPORT jint JNICALL Java_com_ljarocki_parallelzip_internal_LibdeflateNative_compress(
        JNIEnv *env, jclass clazz,
        jlong handle,
        jbyteArray inArr, jint inOff, jint inLen,
        jbyteArray outArr, jint outOff, jint outCap,
        jlongArray crcOut) {
    (void) clazz;
    struct libdeflate_compressor *c = (struct libdeflate_compressor *) (intptr_t) handle;
    if (c == NULL) {
        return -1;
    }

    jbyte *in = (*env)->GetPrimitiveArrayCritical(env, inArr, NULL);
    if (in == NULL) {
        return -1;
    }
    jbyte *out = (*env)->GetPrimitiveArrayCritical(env, outArr, NULL);
    if (out == NULL) {
        (*env)->ReleasePrimitiveArrayCritical(env, inArr, in, JNI_ABORT);
        return -1;
    }

    const void *inp = (const void *) (in + inOff);
    uint32_t crc = libdeflate_crc32(0, inp, (size_t) inLen);
    jint rc = pzip_compress_or_store(
            c, inp, (size_t) inLen,
            (void *) (out + outOff), (size_t) outCap);

    (*env)->ReleasePrimitiveArrayCritical(env, outArr, out, 0);
    (*env)->ReleasePrimitiveArrayCritical(env, inArr, in, JNI_ABORT);

    pzip_set_crc_out(env, crcOut, crc);
    return rc;
}

/*
 * Compresses a whole batch of entries in one JNI call, reusing the single
 * passed-in compressor for all of them. Small-entry archives batch dozens to
 * hundreds of entries into one compression task already (see
 * ParallelZipWriter.Sink); previously each entry still paid its own JNI call
 * overhead and critical-array pin/unpin pair. This collapses that to one call
 * for the whole batch. outLens[i] receives the compressed length for entry i,
 * PZIP_STORE_SENTINEL (-2) if the sniff says to STORE it, or -1 if that entry
 * failed and needs a JDK Deflater fallback -- one failure never aborts the
 * rest of the batch. (Batched entries are small, so in practice the sniff
 * never fires here; the shared helper keeps the two paths consistent anyway.)
 * crcs[i] always receives the CRC-32 of ins[i], computed here while that
 * entry's array is pinned, regardless of outLens[i] -- including on a JDK
 * Deflater fallback, so the Java side never redoes that scan.
 */
JNIEXPORT void JNICALL Java_com_ljarocki_parallelzip_internal_LibdeflateNative_compressBatch(
        JNIEnv *env, jclass clazz,
        jlong handle, jobjectArray ins, jobjectArray outs, jintArray outLensArr,
        jlongArray crcsArr, jint count) {
    (void) clazz;
    if (count <= 0) {
        return;
    }
    struct libdeflate_compressor *c = (struct libdeflate_compressor *) (intptr_t) handle;

    jint lens_stack[256];
    jlong crcs_stack[256];
    jint *lens = (count <= 256) ? lens_stack : (jint *) malloc(sizeof(jint) * (size_t) count);
    jlong *crcs = (count <= 256) ? crcs_stack : (jlong *) malloc(sizeof(jlong) * (size_t) count);
    if (lens == NULL || crcs == NULL) {
        if (lens != lens_stack) free(lens);
        if (crcs != crcs_stack) free(crcs);
        return; /* leaves outLensArr/crcsArr at their Java-side defaults (0), treated as failure by the caller */
    }

    for (jint i = 0; i < count; i++) {
        jbyteArray inArr = (jbyteArray) (*env)->GetObjectArrayElement(env, ins, i);
        jbyteArray outArr = (jbyteArray) (*env)->GetObjectArrayElement(env, outs, i);
        jsize inLen = (*env)->GetArrayLength(env, inArr);
        jsize outCap = (*env)->GetArrayLength(env, outArr);

        jbyte *in = (*env)->GetPrimitiveArrayCritical(env, inArr, NULL);
        jbyte *out = (in != NULL) ? (*env)->GetPrimitiveArrayCritical(env, outArr, NULL) : NULL;

        if (in == NULL || out == NULL) {
            if (in != NULL) {
                (*env)->ReleasePrimitiveArrayCritical(env, inArr, in, JNI_ABORT);
            }
            lens[i] = -1;
            crcs[i] = 0;
        } else {
            crcs[i] = (jlong) libdeflate_crc32(0, (const void *) in, (size_t) inLen);
            lens[i] = (c == NULL) ? -1 : pzip_compress_or_store(
                    c, (const void *) in, (size_t) inLen, (void *) out, (size_t) outCap);
            (*env)->ReleasePrimitiveArrayCritical(env, outArr, out, 0);
            (*env)->ReleasePrimitiveArrayCritical(env, inArr, in, JNI_ABORT);
        }

        (*env)->DeleteLocalRef(env, inArr);
        (*env)->DeleteLocalRef(env, outArr);
    }

    (*env)->SetIntArrayRegion(env, outLensArr, 0, count, lens);
    (*env)->SetLongArrayRegion(env, crcsArr, 0, count, crcs);
    if (lens != lens_stack) free(lens);
    if (crcs != crcs_stack) free(crcs);
}

/*
 * Compresses directly from a direct (typically memory-mapped) ByteBuffer,
 * without ever copying the input through a JVM byte[] first -- the
 * large-entry counterpart to compress() above, which needs a byte[] because
 * that's what Files.readAllBytes produces. GetDirectBufferAddress needs no
 * pin/unpin: direct buffers live outside the movable Java heap already.
 * Same return convention (including the fused crcOut[0]) as compress().
 */
JNIEXPORT jint JNICALL Java_com_ljarocki_parallelzip_internal_LibdeflateNative_compressDirect(
        JNIEnv *env, jclass clazz,
        jlong handle,
        jobject inBuf, jint inLen,
        jbyteArray outArr, jint outOff, jint outCap,
        jlongArray crcOut) {
    (void) clazz;
    struct libdeflate_compressor *c = (struct libdeflate_compressor *) (intptr_t) handle;
    if (c == NULL) {
        return -1;
    }

    void *inp = (*env)->GetDirectBufferAddress(env, inBuf);
    if (inp == NULL) {
        return -1;
    }
    jbyte *out = (*env)->GetPrimitiveArrayCritical(env, outArr, NULL);
    if (out == NULL) {
        return -1;
    }

    uint32_t crc = libdeflate_crc32(0, inp, (size_t) inLen);
    jint rc = pzip_compress_or_store(
            c, inp, (size_t) inLen,
            (void *) (out + outOff), (size_t) outCap);

    (*env)->ReleasePrimitiveArrayCritical(env, outArr, out, 0);

    pzip_set_crc_out(env, crcOut, crc);
    return rc;
}

/*
 * Probes the first PZIP_SNIFF_SAMPLE bytes of a direct ByteBuffer using a stack
 * scratch buffer, before the Java caller allocates a multi-MiB output byte[].
 * If the probe indicates the input is incompressible, computes the full-buffer
 * CRC-32 into crcOut[0] and returns JNI_TRUE so the caller can STORE the entry
 * with zero Java heap allocation.
 */
JNIEXPORT jboolean JNICALL Java_com_ljarocki_parallelzip_internal_LibdeflateNative_sniffIncompressibleDirect(
        JNIEnv *env, jclass clazz,
        jlong handle, jobject inBuf, jint inLen, jlongArray crcOut) {
    (void) clazz;
    struct libdeflate_compressor *c = (struct libdeflate_compressor *) (intptr_t) handle;
    if (c == NULL || (size_t) inLen < PZIP_SNIFF_MIN_INPUT) {
        return JNI_FALSE;
    }
    const void *inp = (*env)->GetDirectBufferAddress(env, inBuf);
    if (inp == NULL) {
        return JNI_FALSE;
    }
    if (pzip_looks_incompressible_fast((const uint8_t *) inp, (size_t) inLen)) {
        uint32_t crc = libdeflate_crc32(0, inp, (size_t) inLen);
        pzip_set_crc_out(env, crcOut, crc);
        return JNI_TRUE;
    }
    uint8_t probe_buf[PZIP_SNIFF_SAMPLE];
    size_t probe = libdeflate_deflate_compress(c, inp, PZIP_SNIFF_SAMPLE, probe_buf, sizeof(probe_buf));
    if (probe == 0 || probe * 100u >= (size_t) PZIP_SNIFF_SAMPLE * PZIP_SNIFF_KEEP_PCT) {
        uint32_t crc = libdeflate_crc32(0, inp, (size_t) inLen);
        pzip_set_crc_out(env, crcOut, crc);
        return JNI_TRUE;
    }
    return JNI_FALSE;
}

static int pzip_looks_already_compressed(const uint8_t *h, size_t len) {
    if (len < 2) return 0;
    switch (h[0]) {
        case 0x50: /* ZIP / jar / docx */
            return len >= 4 && h[1] == 0x4B && ((h[2] == 0x03 && h[3] == 0x04)
                    || (h[2] == 0x05 && h[3] == 0x06) || (h[2] == 0x07 && h[3] == 0x08));
        case 0x1F: /* gzip */
            return h[1] == 0x8B;
        case 0x42: /* bzip2 */
            return len >= 3 && h[1] == 0x5A && h[2] == 0x68;
        case 0xFD: /* xz */
            return len >= 6 && h[1] == 0x37 && h[2] == 0x7A && h[3] == 0x58 && h[4] == 0x5A && h[5] == 0x00;
        case 0x37: /* 7z */
            return len >= 6 && h[1] == 0x7A && h[2] == 0xBC && h[3] == 0xAF && h[4] == 0x27 && h[5] == 0x1C;
        case 0x28: /* zstd */
            return len >= 4 && h[1] == 0xB5 && h[2] == 0x2F && h[3] == 0xFD;
        case 0x89: /* png */
            return len >= 4 && h[1] == 0x50 && h[2] == 0x4E && h[3] == 0x47;
        case 0xFF: /* jpeg */
            return len >= 3 && h[1] == 0xD8 && h[2] == 0xFF;
        case 0x47: /* gif */
            return len >= 4 && h[1] == 0x49 && h[2] == 0x46 && h[3] == 0x38;
        default:
            return 0;
    }
}

static FILE *pzip_fopen_utf8_rb(const char *utf8_path) {
    if (utf8_path == NULL) return NULL;
#ifdef _WIN32
    int wlen = MultiByteToWideChar(CP_UTF8, 0, utf8_path, -1, NULL, 0);
    if (wlen <= 0) return NULL;
    wchar_t *wpath = (wchar_t *) malloc((size_t) wlen * sizeof(wchar_t));
    if (wpath == NULL) return NULL;
    MultiByteToWideChar(CP_UTF8, 0, utf8_path, -1, wpath, wlen);
    FILE *fp = _wfopen(wpath, L"rb");
    free(wpath);
    return fp;
#else
    return fopen(utf8_path, "rb");
#endif
}

/*
 * Opens, sniffs, CRCs, and compresses a batch of small/medium files directly in native code,
 * avoiding per-file Java byte[16] header arrays and InputStream wrappers on 20k-file corpora.
 * On any per-file open/read/size-mismatch error, sets outLens[i] = -1 so Java falls back cleanly.
 */
JNIEXPORT void JNICALL Java_com_ljarocki_parallelzip_internal_LibdeflateNative_compressFilesBatch(
        JNIEnv *env, jclass clazz,
        jlong handle,
        jobjectArray paths,
        jobjectArray outs,
        jintArray outLensArr,
        jlongArray rawSizesArr,
        jlongArray crcsArr,
        jint count,
        jboolean store,
        jboolean skipAlreadyCompressed) {
    (void) clazz;
    struct libdeflate_compressor *c = (struct libdeflate_compressor *) (intptr_t) handle;
    jint *outLens = (*env)->GetIntArrayElements(env, outLensArr, NULL);
    jlong *rawSizes = (*env)->GetLongArrayElements(env, rawSizesArr, NULL);
    jlong *crcs = (*env)->GetLongArrayElements(env, crcsArr, NULL);
    if (outLens == NULL || rawSizes == NULL || crcs == NULL) {
        if (outLens) (*env)->ReleaseIntArrayElements(env, outLensArr, outLens, JNI_ABORT);
        if (rawSizes) (*env)->ReleaseLongArrayElements(env, rawSizesArr, rawSizes, JNI_ABORT);
        if (crcs) (*env)->ReleaseLongArrayElements(env, crcsArr, crcs, JNI_ABORT);
        return;
    }

    size_t scratch_cap = 64u * 1024u;
    uint8_t *scratch = (uint8_t *) malloc(scratch_cap);
    if (scratch == NULL) {
        for (jint i = 0; i < count; i++) {
            outLens[i] = -1;
            crcs[i] = 0;
        }
        (*env)->ReleaseIntArrayElements(env, outLensArr, outLens, 0);
        (*env)->ReleaseLongArrayElements(env, rawSizesArr, rawSizes, JNI_ABORT);
        (*env)->ReleaseLongArrayElements(env, crcsArr, crcs, 0);
        return;
    }

    for (jint i = 0; i < count; i++) {
        outLens[i] = -1;
        crcs[i] = 0;
        jlong expected = rawSizes[i];
        if (expected < 0 || expected > (64 * 1024 * 1024)) {
            continue;
        }
        size_t need = (size_t) expected;
        if (need > scratch_cap) {
            uint8_t *grown = (uint8_t *) realloc(scratch, need);
            if (grown == NULL) continue;
            scratch = grown;
            scratch_cap = need;
        }

        jstring jpath = (jstring) (*env)->GetObjectArrayElement(env, paths, i);
        jbyteArray jout = (jbyteArray) (*env)->GetObjectArrayElement(env, outs, i);
        if (jpath == NULL || jout == NULL) {
            if (jpath) (*env)->DeleteLocalRef(env, jpath);
            if (jout) (*env)->DeleteLocalRef(env, jout);
            continue;
        }

        const char *cpath = (*env)->GetStringUTFChars(env, jpath, NULL);
        FILE *fp = pzip_fopen_utf8_rb(cpath);
        if (cpath != NULL) (*env)->ReleaseStringUTFChars(env, jpath, cpath);
        (*env)->DeleteLocalRef(env, jpath);

        if (fp == NULL) {
            (*env)->DeleteLocalRef(env, jout);
            continue;
        }

        size_t total = 0;
        while (total < need) {
            size_t r = fread(scratch + total, 1, need - total, fp);
            if (r == 0) break;
            total += r;
        }
        int extra = fgetc(fp);
        fclose(fp);

        if (total != need || extra != EOF || scratch == NULL) {
            (*env)->DeleteLocalRef(env, jout);
            continue;
        }

        uint32_t crc = libdeflate_crc32(0, scratch, total);
        crcs[i] = (jlong) crc;
        jsize out_cap = (*env)->GetArrayLength(env, jout);

        if (total == 0 || store || (skipAlreadyCompressed && pzip_looks_already_compressed(scratch, total))) {
            if ((size_t) out_cap >= total) {
                if (total > 0) {
                    (*env)->SetByteArrayRegion(env, jout, 0, (jsize) total, (const jbyte *) scratch);
                }
                outLens[i] = PZIP_STORE_SENTINEL;
            }
            (*env)->DeleteLocalRef(env, jout);
            continue;
        }

        if (c != NULL) {
            jbyte *outp = (*env)->GetPrimitiveArrayCritical(env, jout, NULL);
            if (outp != NULL) {
                jint rc = pzip_compress_or_store(c, scratch, total, (void *) outp, (size_t) out_cap);
                (*env)->ReleasePrimitiveArrayCritical(env, jout, outp, 0);
                if (rc > 0 && (size_t) rc < total) {
                    outLens[i] = rc;
                } else if ((rc == PZIP_STORE_SENTINEL || rc >= (jint) total) && (size_t) out_cap >= total) {
                    (*env)->SetByteArrayRegion(env, jout, 0, (jsize) total, (const jbyte *) scratch);
                    outLens[i] = PZIP_STORE_SENTINEL;
                }
            }
        }
        (*env)->DeleteLocalRef(env, jout);
    }

    free(scratch);
    (*env)->ReleaseIntArrayElements(env, outLensArr, outLens, 0);
    (*env)->ReleaseLongArrayElements(env, rawSizesArr, rawSizes, JNI_ABORT);
    (*env)->ReleaseLongArrayElements(env, crcsArr, crcs, 0);
}

/*
 * Non-final raw DEFLATE compression for >128 MiB parallel chunking (forces BFINAL=0
 * and appends a byte-aligned Z_SYNC_FLUSH empty stored block).
 */
JNIEXPORT jint JNICALL Java_com_ljarocki_parallelzip_internal_LibdeflateNative_compressNonFinal(
        JNIEnv *env, jclass clazz,
        jlong handle,
        jbyteArray inArr, jint inOff, jint inLen,
        jbyteArray outArr, jint outOff, jint outCap,
        jlongArray crcOut) {
    (void) clazz;
    struct libdeflate_compressor *c = (struct libdeflate_compressor *) (intptr_t) handle;
    if (c == NULL) return -1;

    jbyte *in = (*env)->GetPrimitiveArrayCritical(env, inArr, NULL);
    if (in == NULL) return -1;
    jbyte *out = (*env)->GetPrimitiveArrayCritical(env, outArr, NULL);
    if (out == NULL) {
        (*env)->ReleasePrimitiveArrayCritical(env, inArr, in, JNI_ABORT);
        return -1;
    }

    const void *inp = (const void *) (in + inOff);
    uint32_t crc = libdeflate_crc32(0, inp, (size_t) inLen);
    size_t rc = libdeflate_deflate_compress_nonfinal(
            c, inp, (size_t) inLen, (void *) (out + outOff), (size_t) outCap);

    (*env)->ReleasePrimitiveArrayCritical(env, outArr, out, 0);
    (*env)->ReleasePrimitiveArrayCritical(env, inArr, in, JNI_ABORT);
    pzip_set_crc_out(env, crcOut, crc);
    return (rc == 0) ? -1 : (jint) rc;
}

/*
 * Plain C symbols for JDK 22+ FFM Linker (zero JNI overhead / zero GC array pinning).
 */
PZIP_EXPORT void *pzip_alloc_compressor(int level) {
    if (level < 1) level = 1;
    if (level > 12) level = 12;
    return (void *) libdeflate_alloc_compressor(level);
}

PZIP_EXPORT void pzip_free_compressor(void *c) {
    if (c != NULL) libdeflate_free_compressor((struct libdeflate_compressor *) c);
}

PZIP_EXPORT int32_t pzip_deflate_mem(void *c, const void *in, size_t in_len,
                                     void *out, size_t out_cap, uint32_t *crc_out) {
    if (c == NULL || in == NULL || out == NULL) return -1;
    uint32_t crc = libdeflate_crc32(0, in, in_len);
    if (crc_out != NULL) *crc_out = crc;
    return (int32_t) pzip_compress_or_store((struct libdeflate_compressor *) c, in, in_len, out, out_cap);
}

PZIP_EXPORT uint32_t pzip_crc32(uint32_t crc, const void *in, size_t in_len) {
    return libdeflate_crc32(crc, in, in_len);
}

/*
 * Native positional write (pwrite) from a buffer directly into an archive path at offset,
 * used by zero-copy native pwrite paths.
 */
PZIP_EXPORT int32_t pzip_pwrite_path(const char *path, const void *buf, size_t len, uint64_t offset) {
    if (path == NULL || buf == NULL) return -1;
#ifdef _WIN32
    int wlen = MultiByteToWideChar(CP_UTF8, 0, path, -1, NULL, 0);
    if (wlen <= 0) return -1;
    wchar_t *wpath = (wchar_t *) malloc((size_t) wlen * sizeof(wchar_t));
    if (wpath == NULL) return -1;
    MultiByteToWideChar(CP_UTF8, 0, path, -1, wpath, wlen);
    HANDLE h = CreateFileW(wpath, GENERIC_WRITE, FILE_SHARE_READ | FILE_SHARE_WRITE,
                           NULL, OPEN_EXISTING, FILE_ATTRIBUTE_NORMAL, NULL);
    free(wpath);
    if (h == INVALID_HANDLE_VALUE) return -1;
    const uint8_t *p = (const uint8_t *) buf;
    size_t rem = len;
    uint64_t off = offset;
    while (rem > 0) {
        DWORD chunk = (rem > 0x40000000u) ? 0x40000000u : (DWORD) rem;
        OVERLAPPED ov;
        memset(&ov, 0, sizeof(ov));
        ov.Offset = (DWORD) (off & 0xFFFFFFFFu);
        ov.OffsetHigh = (DWORD) (off >> 32);
        DWORD written = 0;
        if (!WriteFile(h, p, chunk, &written, &ov) || written == 0) {
            CloseHandle(h);
            return -1;
        }
        p += written;
        off += written;
        rem -= written;
    }
    CloseHandle(h);
    return 0;
#else
    int fd = open(path, O_WRONLY);
    if (fd < 0) return -1;
    const uint8_t *p = (const uint8_t *) buf;
    size_t rem = len;
    off_t off = (off_t) offset;
    while (rem > 0) {
        ssize_t w = pwrite(fd, p, rem, off);
        if (w < 0 && errno == EINTR) {
            continue;
        }
        if (w <= 0) {
            close(fd);
            return -1;
        }
        p += (size_t) w;
        off += (off_t) w;
        rem -= (size_t) w;
    }
    close(fd);
    return 0;
#endif
}
