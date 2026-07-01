#include <jni.h>
#include <stddef.h>
#include "libdeflate.h"

/*
 * One stateless call per invocation: alloc a compressor, compress the whole
 * buffer, free the compressor. No native handles are kept alive across
 * calls, so there is nothing for the Java side to leak or need to close.
 * libdeflate has no streaming API (whole-buffer in, whole-buffer out), which
 * is why this is only used for the in-memory fast path, not spilled entries.
 */
JNIEXPORT jint JNICALL Java_io_github_kukis13_parallelzip_internal_LibdeflateNative_compress(
        JNIEnv *env, jclass clazz,
        jbyteArray inArr, jint inOff, jint inLen,
        jbyteArray outArr, jint outOff, jint outCap,
        jint level) {
    (void) clazz;

    if (level < 1) level = 1;
    if (level > 12) level = 12;

    struct libdeflate_compressor *c = libdeflate_alloc_compressor(level);
    if (c == NULL) {
        return -1;
    }

    jbyte *in = (*env)->GetPrimitiveArrayCritical(env, inArr, NULL);
    if (in == NULL) {
        libdeflate_free_compressor(c);
        return -1;
    }
    jbyte *out = (*env)->GetPrimitiveArrayCritical(env, outArr, NULL);
    if (out == NULL) {
        (*env)->ReleasePrimitiveArrayCritical(env, inArr, in, JNI_ABORT);
        libdeflate_free_compressor(c);
        return -1;
    }

    size_t result = libdeflate_deflate_compress(
            c,
            (const void *) (in + inOff), (size_t) inLen,
            (void *) (out + outOff), (size_t) outCap);

    (*env)->ReleasePrimitiveArrayCritical(env, outArr, out, 0);
    (*env)->ReleasePrimitiveArrayCritical(env, inArr, in, JNI_ABORT);
    libdeflate_free_compressor(c);

    /* libdeflate returns 0 to mean "output buffer too small"; the Java side
     * sizes the buffer with a conservative bound, so this should not happen
     * in practice, but fall back cleanly if it ever does. */
    if (result == 0) {
        return -1;
    }
    return (jint) result;
}
