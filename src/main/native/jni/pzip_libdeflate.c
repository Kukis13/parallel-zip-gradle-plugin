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
JNIEXPORT jlong JNICALL Java_io_github_kukis13_parallelzip_internal_LibdeflateNative_allocCompressor(
        JNIEnv *env, jclass clazz, jint level) {
    (void) env;
    (void) clazz;
    if (level < 1) level = 1;
    if (level > 12) level = 12;
    struct libdeflate_compressor *c = libdeflate_alloc_compressor(level);
    return (jlong) (intptr_t) c;
}

JNIEXPORT void JNICALL Java_io_github_kukis13_parallelzip_internal_LibdeflateNative_freeCompressor(
        JNIEnv *env, jclass clazz, jlong handle) {
    (void) env;
    (void) clazz;
    struct libdeflate_compressor *c = (struct libdeflate_compressor *) (intptr_t) handle;
    if (c != NULL) {
        libdeflate_free_compressor(c);
    }
}

/* Returns the compressed length, or a negative value on failure (caller falls
 * back to the JDK Deflater for this entry). */
JNIEXPORT jint JNICALL Java_io_github_kukis13_parallelzip_internal_LibdeflateNative_compress(
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

    size_t result = libdeflate_deflate_compress(
            c,
            (const void *) (in + inOff), (size_t) inLen,
            (void *) (out + outOff), (size_t) outCap);

    (*env)->ReleasePrimitiveArrayCritical(env, outArr, out, 0);
    (*env)->ReleasePrimitiveArrayCritical(env, inArr, in, JNI_ABORT);

    /* libdeflate returns 0 to mean "output buffer too small"; the Java side
     * sizes the buffer with a conservative bound, so this should not happen
     * in practice, but fall back cleanly if it ever does. */
    if (result == 0) {
        return -1;
    }
    return (jint) result;
}

/*
 * Compresses a whole batch of entries in one JNI call, reusing the single
 * passed-in compressor for all of them. Small-entry archives batch dozens to
 * hundreds of entries into one compression task already (see
 * ParallelZipWriter.Sink); previously each entry still paid its own JNI call
 * overhead and critical-array pin/unpin pair. This collapses that to one call
 * for the whole batch. outLens[i] receives the compressed length for entry i,
 * or a negative value if that individual entry failed and needs a JDK
 * Deflater fallback -- one failure never aborts the rest of the batch.
 */
JNIEXPORT void JNICALL Java_io_github_kukis13_parallelzip_internal_LibdeflateNative_compressBatch(
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
            size_t result = libdeflate_deflate_compress(
                    c, (const void *) in, (size_t) inLen, (void *) out, (size_t) outCap);
            (*env)->ReleasePrimitiveArrayCritical(env, outArr, out, 0);
            (*env)->ReleasePrimitiveArrayCritical(env, inArr, in, JNI_ABORT);
            lens[i] = (result == 0) ? -1 : (jint) result;
        }

        (*env)->DeleteLocalRef(env, inArr);
        (*env)->DeleteLocalRef(env, outArr);
    }

    (*env)->SetIntArrayRegion(env, outLensArr, 0, count, lens);
    free(lens);
}
