#pragma once

#include "ErrorCodes.h"
#include <windows.h>
#include <audioclient.h>
#include <mmdeviceapi.h>
#include <mfapi.h>
#include <mfidl.h>

struct VideoPlayerInstance;

namespace AudioManager {

HRESULT InitWASAPI(VideoPlayerInstance* pInstance, const WAVEFORMATEX* pSourceFormat = nullptr);
HRESULT PreFillAudioBuffer(VideoPlayerInstance* pInstance);
HRESULT StartAudioThread(VideoPlayerInstance* pInstance);
void    StopAudioThread(VideoPlayerInstance* pInstance);

HRESULT SetVolume(VideoPlayerInstance* pInstance, float volume);
HRESULT GetVolume(const VideoPlayerInstance* pInstance, float* volume);

// Called by the video player when playback is resumed/paused so the audio
// thread can block efficiently instead of busy-waiting.
void SignalResume(VideoPlayerInstance* pInstance);
void SignalPause(VideoPlayerInstance* pInstance);

} // namespace AudioManager
