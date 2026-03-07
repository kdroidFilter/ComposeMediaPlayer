#pragma once

#include <windows.h>
#include <audioclient.h>
#include <mmdeviceapi.h>
#include <mfapi.h>
#include <mfidl.h>

// Error code definitions
#define OP_E_NOT_INITIALIZED     ((HRESULT)0x80000001L)
#define OP_E_ALREADY_INITIALIZED ((HRESULT)0x80000002L)
#define OP_E_INVALID_PARAMETER   ((HRESULT)0x80000003L)

// Forward declarations
struct VideoPlayerInstance;

namespace AudioManager {

/**
 * @brief Initializes WASAPI for audio playback.
 * @param pInstance Pointer to the video player instance.
 * @param pSourceFormat Optional source audio format.
 * @return S_OK on success, or an error code.
 */
HRESULT InitWASAPI(VideoPlayerInstance* pInstance, const WAVEFORMATEX* pSourceFormat = nullptr);

/**
 * @brief Audio processing thread procedure.
 * @param lpParam Pointer to the video player instance.
 * @return Thread exit code.
 */
DWORD WINAPI AudioThreadProc(LPVOID lpParam);

/**
 * @brief Starts the audio thread for a video player instance.
 * @param pInstance Pointer to the video player instance.
 * @return S_OK on success, or an error code.
 */
HRESULT StartAudioThread(VideoPlayerInstance* pInstance);

/**
 * @brief Stops the audio thread for a video player instance.
 * @param pInstance Pointer to the video player instance.
 */
void StopAudioThread(VideoPlayerInstance* pInstance);

/**
 * @brief Sets the audio volume for a video player instance.
 * @param pInstance Pointer to the video player instance.
 * @param volume Volume level (0.0 to 1.0).
 * @return S_OK on success, or an error code.
 */
HRESULT SetVolume(VideoPlayerInstance* pInstance, float volume);

/**
 * @brief Gets the audio volume for a video player instance.
 * @param pInstance Pointer to the video player instance.
 * @param volume Pointer to receive the volume level.
 * @return S_OK on success, or an error code.
 */
HRESULT GetVolume(const VideoPlayerInstance* pInstance, float* volume);

/**
 * @brief Gets the audio levels for a video player instance.
 * @param pInstance Pointer to the video player instance.
 * @param pLeftLevel Pointer to receive the left channel level.
 * @param pRightLevel Pointer to receive the right channel level.
 * @return S_OK on success, or an error code.
 */
HRESULT GetAudioLevels(const VideoPlayerInstance* pInstance, float* pLeftLevel, float* pRightLevel);

} // namespace AudioManager
