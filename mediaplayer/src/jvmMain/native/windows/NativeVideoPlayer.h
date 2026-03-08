// NativeVideoPlayer.h
#pragma once
#ifndef NATIVE_VIDEO_PLAYER_H
#define NATIVE_VIDEO_PLAYER_H

#include <windows.h>
#include <mfapi.h>
#include <mfidl.h>
#include <mfreadwrite.h>
#include <audioclient.h>
#include <mmdeviceapi.h>

// Native API version — bump when the exported API changes.
// Kotlin JNA bindings should call GetNativeVersion() and compare.
#define NATIVE_VIDEO_PLAYER_VERSION 2

// Structure to hold video metadata
typedef struct VideoMetadata {
    wchar_t title[256];          // Title of the video (empty if not available)
    LONGLONG duration;           // Duration in 100-ns units
    UINT32 width;                // Width in pixels
    UINT32 height;               // Height in pixels
    LONGLONG bitrate;            // Bitrate in bits per second
    float frameRate;             // Frame rate in frames per second
    wchar_t mimeType[64];        // MIME type of the video
    UINT32 audioChannels;        // Number of audio channels
    UINT32 audioSampleRate;      // Audio sample rate in Hz
    BOOL hasTitle;               // TRUE if title is available
    BOOL hasDuration;            // TRUE if duration is available
    BOOL hasWidth;               // TRUE if width is available
    BOOL hasHeight;              // TRUE if height is available
    BOOL hasBitrate;             // TRUE if bitrate is available
    BOOL hasFrameRate;           // TRUE if frame rate is available
    BOOL hasMimeType;            // TRUE if MIME type is available
    BOOL hasAudioChannels;       // TRUE if audio channels is available
    BOOL hasAudioSampleRate;     // TRUE if audio sample rate is available
} VideoMetadata;

// DLL export macro
#ifdef _WIN32
#ifdef NATIVEVIDEOPLAYER_EXPORTS
#define NATIVEVIDEOPLAYER_API __declspec(dllexport)
#else
#define NATIVEVIDEOPLAYER_API __declspec(dllimport)
#endif
#else
#define NATIVEVIDEOPLAYER_API
#endif

// Custom error codes
#define OP_E_NOT_INITIALIZED     ((HRESULT)0x80000001L)
#define OP_E_ALREADY_INITIALIZED ((HRESULT)0x80000002L)
#define OP_E_INVALID_PARAMETER   ((HRESULT)0x80000003L)

// Forward declaration for the video player instance state
struct VideoPlayerInstance;

#ifdef __cplusplus
extern "C" {
#endif

// ====================================================================
// Exported functions for instance management and media playback
// ====================================================================

/**
 * @brief Returns the native API version number.
 *
 * Kotlin JNA bindings should check that this value matches the expected
 * version to detect DLL/binding mismatches at load time.
 *
 * @return The version number (NATIVE_VIDEO_PLAYER_VERSION).
 */
NATIVEVIDEOPLAYER_API int GetNativeVersion();

/**
 * @brief Initializes Media Foundation, Direct3D11 and the DXGI manager (once for all instances).
 * @return S_OK on success, or an error code.
 */
NATIVEVIDEOPLAYER_API HRESULT InitMediaFoundation();

/**
 * @brief Creates a new video player instance.
 * @param ppInstance Pointer to receive the handle to the new instance.
 * @return S_OK on success, or an error code.
 */
NATIVEVIDEOPLAYER_API HRESULT CreateVideoPlayerInstance(VideoPlayerInstance** ppInstance);

/**
 * @brief Destroys a video player instance and releases its resources.
 * @param pInstance Handle to the instance to destroy.
 */
NATIVEVIDEOPLAYER_API void DestroyVideoPlayerInstance(VideoPlayerInstance* pInstance);

/**
 * @brief Opens a media file or URL and prepares hardware-accelerated decoding for a specific instance.
 * @param pInstance Handle to the instance.
 * @param url Path or URL to the media (wide string).
 * @param startPlayback TRUE to start playback immediately, FALSE to remain paused.
 * @return S_OK on success, or an error code.
 */
NATIVEVIDEOPLAYER_API HRESULT OpenMedia(VideoPlayerInstance* pInstance, const wchar_t* url, BOOL startPlayback = TRUE);

/**
 * @brief Reads the next video frame in RGB32 format for a specific instance.
 * @param pInstance Handle to the instance.
 * @param pData Receives a pointer to the frame data (do not free).
 * @param pDataSize Receives the buffer size in bytes.
 * @return S_OK if a frame is read, S_FALSE at end of stream, or an error code.
 */
NATIVEVIDEOPLAYER_API HRESULT ReadVideoFrame(VideoPlayerInstance* pInstance, BYTE** pData, DWORD* pDataSize);

/**
 * @brief Unlocks the previously locked video frame buffer for a specific instance.
 * @param pInstance Handle to the instance.
 * @return S_OK on success.
 */
NATIVEVIDEOPLAYER_API HRESULT UnlockVideoFrame(VideoPlayerInstance* pInstance);

/**
 * @brief Reads the next video frame and copies it into a destination buffer.
 * @param pTimestamp Receives the 100ns timestamp when available.
 */
NATIVEVIDEOPLAYER_API HRESULT ReadVideoFrameInto(
    VideoPlayerInstance* pInstance,
    BYTE* pDst,
    DWORD dstRowBytes,
    DWORD dstCapacity,
    LONGLONG* pTimestamp);

/**
 * @brief Closes the media and releases associated resources for a specific instance.
 * @param pInstance Handle to the instance.
 */
NATIVEVIDEOPLAYER_API void CloseMedia(VideoPlayerInstance* pInstance);

/**
 * @brief Indicates whether the end of the media stream has been reached for a specific instance.
 * @param pInstance Handle to the instance.
 * @return TRUE if end of stream, FALSE otherwise.
 */
NATIVEVIDEOPLAYER_API BOOL IsEOF(const VideoPlayerInstance* pInstance);

/**
 * @brief Retrieves the video dimensions for a specific instance.
 * @param pInstance Handle to the instance.
 * @param pWidth Pointer to receive the width in pixels.
 * @param pHeight Pointer to receive the height in pixels.
 */
NATIVEVIDEOPLAYER_API void GetVideoSize(const VideoPlayerInstance* pInstance, UINT32* pWidth, UINT32* pHeight);

/**
 * @brief Retrieves the video frame rate for a specific instance.
 * @param pInstance Handle to the instance.
 * @param pNum Pointer to receive the numerator.
 * @param pDenom Pointer to receive the denominator.
 * @return S_OK on success, or an error code.
 */
NATIVEVIDEOPLAYER_API HRESULT GetVideoFrameRate(const VideoPlayerInstance* pInstance, UINT* pNum, UINT* pDenom);

/**
 * @brief Seeks to a specific position in the media for a specific instance.
 * @param pInstance Handle to the instance.
 * @param llPosition Position (in 100-ns units) to seek to.
 * @return S_OK on success, or an error code.
 */
NATIVEVIDEOPLAYER_API HRESULT SeekMedia(VideoPlayerInstance* pInstance, LONGLONG llPosition);

/**
 * @brief Gets the total duration of the media for a specific instance.
 * @param pInstance Handle to the instance.
 * @param pDuration Pointer to receive the duration (in 100-ns units).
 * @return S_OK on success, or an error code.
 */
NATIVEVIDEOPLAYER_API HRESULT GetMediaDuration(const VideoPlayerInstance* pInstance, LONGLONG* pDuration);

/**
 * @brief Gets the current playback position for a specific instance.
 * @param pInstance Handle to the instance.
 * @param pPosition Pointer to receive the position (in 100-ns units).
 * @return S_OK on success, or an error code.
 */
NATIVEVIDEOPLAYER_API HRESULT GetMediaPosition(const VideoPlayerInstance* pInstance, LONGLONG* pPosition);

/**
 * @brief Sets the playback state (playing or paused) for a specific instance.
 * @param pInstance Handle to the instance.
 * @param bPlaying TRUE for playback, FALSE for pause.
 * @param bStop TRUE for a full stop, FALSE for a simple pause.
 * @return S_OK on success, or an error code.
 */
NATIVEVIDEOPLAYER_API HRESULT SetPlaybackState(VideoPlayerInstance* pInstance, BOOL bPlaying, BOOL bStop = FALSE);

/**
 * @brief Shuts down Media Foundation and releases global resources (after all instances are destroyed).
 * @return S_OK on success, or an error code.
 */
NATIVEVIDEOPLAYER_API HRESULT ShutdownMediaFoundation();

/**
 * @brief Sets the audio volume level for a specific instance.
 * @param pInstance Handle to the instance.
 * @param volume Volume level (0.0 to 1.0).
 * @return S_OK on success, or an error code.
 */
NATIVEVIDEOPLAYER_API HRESULT SetAudioVolume(VideoPlayerInstance* pInstance, float volume);

/**
 * @brief Gets the current audio volume level for a specific instance.
 * @param pInstance Handle to the instance.
 * @param volume Pointer to receive the volume level (0.0 to 1.0).
 * @return S_OK on success, or an error code.
 */
NATIVEVIDEOPLAYER_API HRESULT GetAudioVolume(const VideoPlayerInstance* pInstance, float* volume);

/**
 * @brief Gets the audio levels for left and right channels for a specific instance.
 * @param pInstance Handle to the instance.
 * @param pLeftLevel Pointer for the left channel level.
 * @param pRightLevel Pointer for the right channel level.
 * @return S_OK on success, or an error code.
 */
NATIVEVIDEOPLAYER_API HRESULT GetAudioLevels(const VideoPlayerInstance* pInstance, float* pLeftLevel, float* pRightLevel);

/**
 * @brief Sets the playback speed for a specific instance.
 * @param pInstance Handle to the instance.
 * @param speed Playback speed (0.5 to 2.0, where 1.0 is normal speed).
 * @return S_OK on success, or an error code.
 */
NATIVEVIDEOPLAYER_API HRESULT SetPlaybackSpeed(VideoPlayerInstance* pInstance, float speed);

/**
 * @brief Gets the current playback speed for a specific instance.
 * @param pInstance Handle to the instance.
 * @param pSpeed Pointer to receive the playback speed.
 * @return S_OK on success, or an error code.
 */
NATIVEVIDEOPLAYER_API HRESULT GetPlaybackSpeed(const VideoPlayerInstance* pInstance, float* pSpeed);

/**
 * @brief Retrieves all available metadata for the current media.
 * @param pInstance Handle to the instance.
 * @param pMetadata Pointer to receive the metadata structure.
 * @return S_OK on success, or an error code.
 */
NATIVEVIDEOPLAYER_API HRESULT GetVideoMetadata(const VideoPlayerInstance* pInstance, VideoMetadata* pMetadata);

/**
 * @brief Sets the desired output resolution for decoded video frames.
 *
 * Reconfigures the MF source reader output type to produce frames at the
 * requested size (hardware-scaled via DXVA2). The aspect ratio of the
 * original video is preserved; the requested size acts as a bounding box.
 * Passing 0,0 resets to the native video resolution.
 *
 * @param pInstance Handle to the instance.
 * @param targetWidth  Desired output width  (0 = native).
 * @param targetHeight Desired output height (0 = native).
 * @return S_OK on success, or an error code.
 */
NATIVEVIDEOPLAYER_API HRESULT SetOutputSize(VideoPlayerInstance* pInstance, UINT32 targetWidth, UINT32 targetHeight);

#ifdef __cplusplus
}
#endif

#endif // NATIVE_VIDEO_PLAYER_H
