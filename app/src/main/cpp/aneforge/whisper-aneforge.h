#ifndef WHISPER_ANEFORGE_H
#define WHISPER_ANEFORGE_H

#ifdef __cplusplus
extern "C" {
#endif

// Stub - ANE forge not used on Android
typedef void* whisper_aneforge_context;

static inline whisper_aneforge_context * whisper_aneforge_init(const char* /*path*/) { return nullptr; }
static inline void whisper_aneforge_free(whisper_aneforge_context * /*ctx*/) {}
static inline void whisper_aneforge_encode(
    whisper_aneforge_context * /*ctx*/,
    int /*n_rows*/, int /*n_cols*/,
    const float* /*data*/,
    float* /*output*/) {}

#ifdef __cplusplus
}
#endif

#endif
