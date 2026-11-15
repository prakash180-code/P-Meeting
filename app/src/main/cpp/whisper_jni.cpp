/*
 * whisper_jni.cpp - JNI bridge between Kotlin and whisper.cpp
 *
 * Calls the real whisper.cpp C API. Built from whisper.cpp source
 * (see app/src/main/cpp/CMakeLists.txt).
 */

#include <jni.h>
#include <string>
#include <vector>
#include <cstring>
#include <cmath>
#include <cstdint>

#include "whisper.h"

// ---------------------------------------------------------------------------
// Native field/method IDs (cached on first use)
// ---------------------------------------------------------------------------

static jclass g_segmentCls = nullptr;
static jmethodID g_segmentCtor = nullptr;

// ---------------------------------------------------------------------------
// Helper: convert jbyteArray to float array (16-bit PCM -> float32)
// ---------------------------------------------------------------------------

static void pcm16_to_float(const jbyte *pcm, int n_samples, float *out) {
    for (int i = 0; i < n_samples; ++i) {
        // Little-endian 16-bit PCM. jbyte is signed, so shifting a negative
        // byte left is undefined behaviour; reinterpret each byte as unsigned
        // before assembling the sample.
        const uint8_t lo = static_cast<uint8_t>(pcm[2 * i]);
        const uint8_t hi = static_cast<uint8_t>(pcm[2 * i + 1]);
        const int16_t sample = static_cast<int16_t>(
            (static_cast<uint16_t>(hi) << 8) | static_cast<uint16_t>(lo));
        out[i] = static_cast<float>(sample) / 32768.0f;
    }
}

// ---------------------------------------------------------------------------
// JNI: com.prakash.pmeeting.transcription.WhisperEngine
// ---------------------------------------------------------------------------

extern "C" JNIEXPORT jlong JNICALL
Java_com_prakash_pmeeting_transcription_WhisperEngine_nativeInit(
        JNIEnv *env, jobject /*thiz*/, jstring modelPath) {

    const char *path = env->GetStringUTFChars(modelPath, nullptr);
    whisper_context_params cparams = whisper_context_default_params();
    whisper_context *ctx = whisper_init_from_file_with_params(path, cparams);
    env->ReleaseStringUTFChars(modelPath, path);

    return reinterpret_cast<jlong>(ctx);
}

extern "C" JNIEXPORT void JNICALL
Java_com_prakash_pmeeting_transcription_WhisperEngine_nativeFree(
        JNIEnv *env, jobject /*thiz*/, jlong ctxPtr) {

    whisper_context *ctx = reinterpret_cast<whisper_context *>(ctxPtr);
    if (ctx) {
        whisper_free(ctx);
    }
}

extern "C" JNIEXPORT jobject JNICALL
Java_com_prakash_pmeeting_transcription_WhisperEngine_nativeTranscribe(
        JNIEnv *env, jobject /*thiz*/, jlong ctxPtr, jbyteArray pcm16, jint sampleRate,
        jint nThreads, jstring language) {

    whisper_context *ctx = reinterpret_cast<whisper_context *>(ctxPtr);
    if (!ctx) {
        return nullptr;
    }

    jbyte *pcm = env->GetByteArrayElements(pcm16, nullptr);
    jsize n_bytes = env->GetArrayLength(pcm16);
    int n_samples = n_bytes / 2;

    std::vector<float> samples(n_samples);
    pcm16_to_float(pcm, n_samples, samples.data());

    env->ReleaseByteArrayElements(pcm16, pcm, JNI_ABORT);

    // Configure transcription parameters using the real whisper API
    whisper_full_params params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    params.print_progress = 0;
    params.print_timestamps = 0;
    params.print_realtime = 0;
    params.n_threads = nThreads > 0 ? nThreads : 4;
    params.n_max_text_ctx = 16384;
    params.offset_ms = 0;
    params.duration_ms = 0;

    // Run transcription
    const char *lang = nullptr;
    if (language != nullptr) {
        lang = env->GetStringUTFChars(language, nullptr);
        if (lang != nullptr) {
            params.language = lang;
            params.detect_language = false;
        }
    } else {
        // No language was supplied: let whisper auto-detect it instead of
        // silently forcing English (whisper_full_default_params uses "en").
        // detect_language stays false so whisper detects and then transcribes.
        params.language = nullptr;
        params.detect_language = false;
    }

    int result = whisper_full(ctx, params, samples.data(), n_samples);

    if (lang != nullptr) {
        env->ReleaseStringUTFChars(language, lang);
    }

    // NOTE: do NOT call whisper_free_params(&params) here. whisper_free_params
    // performs `delete params`, which is only valid for the heap-allocated
    // struct returned by whisper_full_default_params_by_ref(). `params` above
    // is a stack value, so deleting it corrupts the heap (Scudo abort).

    if (result != 0) {
        return nullptr;
    }

    // Collect segments
    int n_segments = whisper_full_n_segments(ctx);

    // Cache class references
    if (!g_segmentCls) {
        jclass cls = env->FindClass("com/prakash/pmeeting/data/model/TranscriptionSegment");
        g_segmentCls = reinterpret_cast<jclass>(env->NewGlobalRef(cls));
        env->DeleteLocalRef(cls);
        g_segmentCtor = env->GetMethodID(g_segmentCls, "<init>", "(JJLjava/lang/String;)V");
    }

    jobjectArray segments = env->NewObjectArray(n_segments, g_segmentCls, nullptr);

    for (int i = 0; i < n_segments; ++i) {
        const char *text = whisper_full_get_segment_text(ctx, i);
        int64_t t0 = whisper_full_get_segment_t0(ctx, i);
        int64_t t1 = whisper_full_get_segment_t1(ctx, i);

        jstring jtext = env->NewStringUTF(text ? text : "");
        jobject segment = env->NewObject(g_segmentCls, g_segmentCtor,
                                         static_cast<jlong>(t0),
                                         static_cast<jlong>(t1),
                                         jtext);
        env->SetObjectArrayElement(segments, i, segment);
        env->DeleteLocalRef(segment);
        env->DeleteLocalRef(jtext);
    }

    return segments;
}
