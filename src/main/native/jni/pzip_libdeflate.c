#include <jni.h>
#include <stddef.h>
#include <stdint.h>
#include <stdlib.h>
#include "libdeflate.h"

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
 * Compress one whole buffer, sniffing large inputs first. Returns the
 * compressed length (> 0), PZIP_STORE_SENTINEL (-2) when the sniff says the
 * input is already compressed and should be STOREd, or -1 on any failure
 * (output buffer too small) so the caller can fall back to the JDK Deflater.
 */
static jint pzip_compress_or_store(struct libdeflate_compressor *c,
                                   const void *inp, size_t in_len,
                                   void *outp, size_t out_cap) {
    if (in_len >= PZIP_SNIFF_MIN_INPUT) {
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

/* Returns the compressed length, PZIP_STORE_SENTINEL (-2) when the sniff says
 * to STORE, or -1 on failure (caller falls back to the JDK Deflater). */
JNIEXPORT jint JNICALL Java_com_ljarocki_parallelzip_internal_LibdeflateNative_compress(
        JNIEnv *env, jclass clazz,
        jlong handle,
        jbyteArray inArr, jint inOff, jint inLen,
        jbyteArray outArr, jint outOff, jint outCap) {
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

    jint rc = pzip_compress_or_store(
            c,
            (const void *) (in + inOff), (size_t) inLen,
            (void *) (out + outOff), (size_t) outCap);

    (*env)->ReleasePrimitiveArrayCritical(env, outArr, out, 0);
    (*env)->ReleasePrimitiveArrayCritical(env, inArr, in, JNI_ABORT);

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
 */
JNIEXPORT void JNICALL Java_com_ljarocki_parallelzip_internal_LibdeflateNative_compressBatch(
        JNIEnv *env, jclass clazz,
        jlong handle, jobjectArray ins, jobjectArray outs, jintArray outLensArr, jint count) {
    (void) clazz;
    struct libdeflate_compressor *c = (struct libdeflate_compressor *) (intptr_t) handle;

    jint *lens = (jint *) malloc(sizeof(jint) * (size_t) (count > 0 ? count : 1));
    if (lens == NULL) {
        return; /* leaves outLensArr as its Java-side default (0), treated as failure by the caller */
    }

    for (jint i = 0; i < count; i++) {
        if (c == NULL) {
            lens[i] = -1;
            continue;
        }
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
        } else {
            lens[i] = pzip_compress_or_store(
                    c, (const void *) in, (size_t) inLen, (void *) out, (size_t) outCap);
            (*env)->ReleasePrimitiveArrayCritical(env, outArr, out, 0);
            (*env)->ReleasePrimitiveArrayCritical(env, inArr, in, JNI_ABORT);
        }

        (*env)->DeleteLocalRef(env, inArr);
        (*env)->DeleteLocalRef(env, outArr);
    }

    (*env)->SetIntArrayRegion(env, outLensArr, 0, count, lens);
    free(lens);
}
