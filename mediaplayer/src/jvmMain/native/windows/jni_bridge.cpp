// jni_bridge.cpp — JNI bridge for NativeVideoPlayer
// Maps Kotlin external functions to the existing C API.

#include <jni.h>
#include "NativeVideoPlayer.h"
#include <cstring>

// ---------------------------------------------------------------------------
// Utility
// ---------------------------------------------------------------------------
static inline VideoPlayerInstance* toInstance(jlong handle) {
    return reinterpret_cast<VideoPlayerInstance*>(handle);
}

// ---------------------------------------------------------------------------
// JNI implementations
// ---------------------------------------------------------------------------

static jint JNICALL jni_GetNativeVersion(JNIEnv*, jclass) {
    return GetNativeVersion();
}

static jint JNICALL jni_InitMediaFoundation(JNIEnv*, jclass) {
    return InitMediaFoundation();
}

static jlong JNICALL jni_CreateInstance(JNIEnv*, jclass) {
    VideoPlayerInstance* p = nullptr;
    HRESULT hr = CreateVideoPlayerInstance(&p);
    if (FAILED(hr) || !p) return 0;
    return reinterpret_cast<jlong>(p);
}

static void JNICALL jni_DestroyInstance(JNIEnv*, jclass, jlong handle) {
    if (handle) DestroyVideoPlayerInstance(toInstance(handle));
}

static jint JNICALL jni_OpenMedia(JNIEnv* env, jclass, jlong handle, jstring url, jboolean startPlayback) {
    if (!handle || !url) return OP_E_INVALID_PARAMETER;
    const jchar* chars = env->GetStringChars(url, nullptr);
    if (!chars) return E_OUTOFMEMORY;
    HRESULT hr = OpenMedia(toInstance(handle),
                           reinterpret_cast<const wchar_t*>(chars),
                           startPlayback ? TRUE : FALSE);
    env->ReleaseStringChars(url, chars);
    return hr;
}

// Returns a direct ByteBuffer wrapping the locked frame, or null.
// outResult[0] receives the HRESULT.
static jobject JNICALL jni_ReadVideoFrame(JNIEnv* env, jclass, jlong handle, jintArray outResult) {
    if (!handle) {
        if (outResult) { jint v = OP_E_NOT_INITIALIZED; env->SetIntArrayRegion(outResult, 0, 1, &v); }
        return nullptr;
    }
    BYTE* pData = nullptr;
    DWORD dataSize = 0;
    HRESULT hr = ReadVideoFrame(toInstance(handle), &pData, &dataSize);
    if (outResult) { jint v = static_cast<jint>(hr); env->SetIntArrayRegion(outResult, 0, 1, &v); }
    if (FAILED(hr) || !pData || dataSize == 0) return nullptr;
    return env->NewDirectByteBuffer(pData, dataSize);
}

static jint JNICALL jni_UnlockVideoFrame(JNIEnv*, jclass, jlong handle) {
    return handle ? UnlockVideoFrame(toInstance(handle)) : E_INVALIDARG;
}

static void JNICALL jni_CloseMedia(JNIEnv*, jclass, jlong handle) {
    if (handle) CloseMedia(toInstance(handle));
}

static jboolean JNICALL jni_IsEOF(JNIEnv*, jclass, jlong handle) {
    return (handle && IsEOF(toInstance(handle))) ? JNI_TRUE : JNI_FALSE;
}

static void JNICALL jni_GetVideoSize(JNIEnv* env, jclass, jlong handle, jintArray outSize) {
    UINT32 w = 0, h = 0;
    if (handle) GetVideoSize(toInstance(handle), &w, &h);
    jint vals[2] = { static_cast<jint>(w), static_cast<jint>(h) };
    env->SetIntArrayRegion(outSize, 0, 2, vals);
}

static jint JNICALL jni_GetVideoFrameRate(JNIEnv* env, jclass, jlong handle, jintArray outRate) {
    if (!handle) return E_INVALIDARG;
    UINT num = 0, denom = 0;
    HRESULT hr = GetVideoFrameRate(toInstance(handle), &num, &denom);
    jint vals[2] = { static_cast<jint>(num), static_cast<jint>(denom) };
    env->SetIntArrayRegion(outRate, 0, 2, vals);
    return hr;
}

static jint JNICALL jni_SeekMedia(JNIEnv*, jclass, jlong handle, jlong pos) {
    return handle ? SeekMedia(toInstance(handle), pos) : E_INVALIDARG;
}

static jint JNICALL jni_GetMediaDuration(JNIEnv* env, jclass, jlong handle, jlongArray out) {
    if (!handle) return E_INVALIDARG;
    LONGLONG v = 0;
    HRESULT hr = GetMediaDuration(toInstance(handle), &v);
    jlong jv = static_cast<jlong>(v);
    env->SetLongArrayRegion(out, 0, 1, &jv);
    return hr;
}

static jint JNICALL jni_GetMediaPosition(JNIEnv* env, jclass, jlong handle, jlongArray out) {
    if (!handle) return E_INVALIDARG;
    LONGLONG v = 0;
    HRESULT hr = GetMediaPosition(toInstance(handle), &v);
    jlong jv = static_cast<jlong>(v);
    env->SetLongArrayRegion(out, 0, 1, &jv);
    return hr;
}

static jint JNICALL jni_SetPlaybackState(JNIEnv*, jclass, jlong handle, jboolean playing, jboolean stop) {
    return handle ? SetPlaybackState(toInstance(handle), playing ? TRUE : FALSE, stop ? TRUE : FALSE) : E_INVALIDARG;
}

static jint JNICALL jni_ShutdownMediaFoundation(JNIEnv*, jclass) {
    return ShutdownMediaFoundation();
}

static jint JNICALL jni_SetAudioVolume(JNIEnv*, jclass, jlong handle, jfloat vol) {
    return handle ? SetAudioVolume(toInstance(handle), vol) : E_INVALIDARG;
}

static jint JNICALL jni_GetAudioVolume(JNIEnv* env, jclass, jlong handle, jfloatArray out) {
    if (!handle) return E_INVALIDARG;
    float v = 0;
    HRESULT hr = GetAudioVolume(toInstance(handle), &v);
    jfloat jv = v;
    env->SetFloatArrayRegion(out, 0, 1, &jv);
    return hr;
}

static jint JNICALL jni_SetPlaybackSpeed(JNIEnv*, jclass, jlong handle, jfloat speed) {
    return handle ? SetPlaybackSpeed(toInstance(handle), speed) : E_INVALIDARG;
}

static jint JNICALL jni_GetPlaybackSpeed(JNIEnv* env, jclass, jlong handle, jfloatArray out) {
    if (!handle) return E_INVALIDARG;
    float v = 0;
    HRESULT hr = GetPlaybackSpeed(toInstance(handle), &v);
    jfloat jv = v;
    env->SetFloatArrayRegion(out, 0, 1, &jv);
    return hr;
}

// Metadata — fills parallel arrays so the Kotlin side can construct VideoMetadata.
static jint JNICALL jni_GetVideoMetadata(JNIEnv* env, jclass, jlong handle,
        jcharArray outTitle, jcharArray outMimeType,
        jlongArray outLongVals, jintArray outIntVals,
        jfloatArray outFloatVals, jbooleanArray outHasFlags) {
    if (!handle) return E_INVALIDARG;

    VideoMetadata m;
    HRESULT hr = GetVideoMetadata(toInstance(handle), &m);
    if (FAILED(hr)) return hr;

    if (outTitle)
        env->SetCharArrayRegion(outTitle, 0, 256, reinterpret_cast<const jchar*>(m.title));
    if (outMimeType)
        env->SetCharArrayRegion(outMimeType, 0, 64, reinterpret_cast<const jchar*>(m.mimeType));
    if (outLongVals) {
        jlong lv[2] = { static_cast<jlong>(m.duration), static_cast<jlong>(m.bitrate) };
        env->SetLongArrayRegion(outLongVals, 0, 2, lv);
    }
    if (outIntVals) {
        jint iv[4] = { static_cast<jint>(m.width), static_cast<jint>(m.height),
                       static_cast<jint>(m.audioChannels), static_cast<jint>(m.audioSampleRate) };
        env->SetIntArrayRegion(outIntVals, 0, 4, iv);
    }
    if (outFloatVals) {
        jfloat fv = m.frameRate;
        env->SetFloatArrayRegion(outFloatVals, 0, 1, &fv);
    }
    if (outHasFlags) {
        jboolean flags[9] = {
            static_cast<jboolean>(m.hasTitle),  static_cast<jboolean>(m.hasDuration),
            static_cast<jboolean>(m.hasWidth),  static_cast<jboolean>(m.hasHeight),
            static_cast<jboolean>(m.hasBitrate), static_cast<jboolean>(m.hasFrameRate),
            static_cast<jboolean>(m.hasMimeType), static_cast<jboolean>(m.hasAudioChannels),
            static_cast<jboolean>(m.hasAudioSampleRate)
        };
        env->SetBooleanArrayRegion(outHasFlags, 0, 9, flags);
    }
    return hr;
}

// Wrap an arbitrary native address as a direct ByteBuffer (used for Skia pixel access).
static jobject JNICALL jni_WrapPointer(JNIEnv* env, jclass, jlong address, jlong size) {
    if (!address || size <= 0) return nullptr;
    return env->NewDirectByteBuffer(reinterpret_cast<void*>(address), static_cast<jlong>(size));
}

static jint JNICALL jni_SetOutputSize(JNIEnv*, jclass, jlong handle, jint width, jint height) {
    return handle ? SetOutputSize(toInstance(handle),
                                  static_cast<UINT32>(width),
                                  static_cast<UINT32>(height))
                  : E_INVALIDARG;
}

// ---------------------------------------------------------------------------
// Registration table
// ---------------------------------------------------------------------------
static const JNINativeMethod g_methods[] = {
    { const_cast<char*>("nGetNativeVersion"),   const_cast<char*>("()I"),                          (void*)jni_GetNativeVersion },
    { const_cast<char*>("nInitMediaFoundation"),const_cast<char*>("()I"),                          (void*)jni_InitMediaFoundation },
    { const_cast<char*>("nCreateInstance"),      const_cast<char*>("()J"),                          (void*)jni_CreateInstance },
    { const_cast<char*>("nDestroyInstance"),     const_cast<char*>("(J)V"),                         (void*)jni_DestroyInstance },
    { const_cast<char*>("nOpenMedia"),           const_cast<char*>("(JLjava/lang/String;Z)I"),      (void*)jni_OpenMedia },
    { const_cast<char*>("nReadVideoFrame"),      const_cast<char*>("(J[I)Ljava/nio/ByteBuffer;"),   (void*)jni_ReadVideoFrame },
    { const_cast<char*>("nUnlockVideoFrame"),    const_cast<char*>("(J)I"),                         (void*)jni_UnlockVideoFrame },
    { const_cast<char*>("nCloseMedia"),          const_cast<char*>("(J)V"),                         (void*)jni_CloseMedia },
    { const_cast<char*>("nIsEOF"),               const_cast<char*>("(J)Z"),                         (void*)jni_IsEOF },
    { const_cast<char*>("nGetVideoSize"),        const_cast<char*>("(J[I)V"),                       (void*)jni_GetVideoSize },
    { const_cast<char*>("nGetVideoFrameRate"),   const_cast<char*>("(J[I)I"),                       (void*)jni_GetVideoFrameRate },
    { const_cast<char*>("nSeekMedia"),           const_cast<char*>("(JJ)I"),                        (void*)jni_SeekMedia },
    { const_cast<char*>("nGetMediaDuration"),    const_cast<char*>("(J[J)I"),                       (void*)jni_GetMediaDuration },
    { const_cast<char*>("nGetMediaPosition"),    const_cast<char*>("(J[J)I"),                       (void*)jni_GetMediaPosition },
    { const_cast<char*>("nSetPlaybackState"),    const_cast<char*>("(JZZ)I"),                       (void*)jni_SetPlaybackState },
    { const_cast<char*>("nShutdownMediaFoundation"), const_cast<char*>("()I"),                      (void*)jni_ShutdownMediaFoundation },
    { const_cast<char*>("nSetAudioVolume"),      const_cast<char*>("(JF)I"),                        (void*)jni_SetAudioVolume },
    { const_cast<char*>("nGetAudioVolume"),      const_cast<char*>("(J[F)I"),                       (void*)jni_GetAudioVolume },
    { const_cast<char*>("nSetPlaybackSpeed"),    const_cast<char*>("(JF)I"),                        (void*)jni_SetPlaybackSpeed },
    { const_cast<char*>("nGetPlaybackSpeed"),    const_cast<char*>("(J[F)I"),                       (void*)jni_GetPlaybackSpeed },
    { const_cast<char*>("nGetVideoMetadata"),    const_cast<char*>("(J[C[C[J[I[F[Z)I"),             (void*)jni_GetVideoMetadata },
    { const_cast<char*>("nWrapPointer"),         const_cast<char*>("(JJ)Ljava/nio/ByteBuffer;"),    (void*)jni_WrapPointer },
    { const_cast<char*>("nSetOutputSize"),      const_cast<char*>("(JII)I"),                       (void*)jni_SetOutputSize },
};

extern "C" JNIEXPORT jint JNI_OnLoad(JavaVM* vm, void*) {
    JNIEnv* env = nullptr;
    if (vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK)
        return -1;

    jclass cls = env->FindClass("io/github/kdroidfilter/composemediaplayer/windows/WindowsNativeBridge");
    if (!cls) return -1;

    if (env->RegisterNatives(cls, g_methods, sizeof(g_methods) / sizeof(g_methods[0])) < 0)
        return -1;

    return JNI_VERSION_1_6;
}
