#pragma once

#include "ComHelpers.h"
#include <windows.h>
#include <mfapi.h>
#include <mfidl.h>
#include <mfreadwrite.h>
#include <audioclient.h>
#include <mmdeviceapi.h>
#include <endpointvolume.h>
#include <wrl/client.h>
#include <atomic>

#include "HLSPlayer.h"

// Per-player state. All COM pointers use ComPtr, events/critical sections use
// RAII wrappers. The destructor performs a full teardown; CloseMedia() resets
// the fields that describe the *current* media so the object can be reused.
struct VideoPlayerInstance {
    // ---- Video source reader ----
    Microsoft::WRL::ComPtr<IMFSourceReader> pSourceReader;
    Microsoft::WRL::ComPtr<IMFMediaBuffer>  pLockedBuffer;
    BYTE*  pLockedBytes     = nullptr;
    DWORD  lockedMaxSize    = 0;
    DWORD  lockedCurrSize   = 0;
    UINT32 videoWidth       = 0;
    UINT32 videoHeight      = 0;
    UINT32 nativeWidth      = 0;
    UINT32 nativeHeight     = 0;
    std::atomic<bool> bEOF{false};

    // Frame caching: used when paused, and when the decoded frame arrived
    // earlier than its presentation time (replaces the sleep-in-render-path
    // pattern so the JNI thread never blocks).
    Microsoft::WRL::ComPtr<IMFSample> pCachedSample;
    LONGLONG llCachedTimestamp = 0;
    ULONGLONG llCachedInsertedAtMs = 0; // wall-clock time when pCachedSample was stored
    bool bHasInitialFrame      = false;

    // ---- Audio ----
    Microsoft::WRL::ComPtr<IMFSourceReader>      pSourceReaderAudio;
    Microsoft::WRL::ComPtr<IAudioClient>         pAudioClient;
    Microsoft::WRL::ComPtr<IAudioRenderClient>   pRenderClient;
    Microsoft::WRL::ComPtr<IMMDevice>            pDevice;
    Microsoft::WRL::ComPtr<IAudioEndpointVolume> pAudioEndpointVolume;

    bool bHasAudio         = false;
    bool bAudioInitialized = false;

    WAVEFORMATEX* pSourceAudioFormat = nullptr; // allocated with CoTaskMemAlloc

    VideoPlayerUtils::UniqueHandle hAudioSamplesReadyEvent;
    VideoPlayerUtils::UniqueHandle hAudioResumeEvent;  // manual-reset; signaled while playing
    VideoPlayerUtils::UniqueHandle hAudioThread;
    std::atomic<bool> bAudioThreadRunning{false};

    // WASAPI latency (ms), updated by audio thread, read by video thread.
    std::atomic<double> audioLatencyMs{0.0};

    // Protects GetBuffer/ReleaseBuffer vs Stop/Reset/Start during seeks.
    VideoPlayerUtils::CriticalSection csAudioFeed;

    // ---- Presentation clock ----
    Microsoft::WRL::ComPtr<IMFPresentationClock> pPresentationClock;
    Microsoft::WRL::ComPtr<IMFMediaSource>       pMediaSource;
    bool bUseClockSync = false;

    // ---- Timing ----
    // Shared across JNI, audio, and render threads: all atomic.
    std::atomic<LONGLONG>  llCurrentPosition{0};
    std::atomic<ULONGLONG> llPlaybackStartTime{0};
    std::atomic<ULONGLONG> llTotalPauseTime{0};
    std::atomic<ULONGLONG> llPauseStart{0};
    std::atomic<bool>      bSeekInProgress{false};
    VideoPlayerUtils::CriticalSection csClockSync; // guards composite seek operations

    // ---- Playback control ----
    std::atomic<float> instanceVolume{1.0f};
    std::atomic<float> playbackSpeed{1.0f};
    double resampleFracPos = 0.0; // audio thread only

    // ---- Network / HLS ----
    bool bIsNetworkSource = false;
    bool bIsLiveStream    = false;
    Microsoft::WRL::ComPtr<HLSPlayer> pHLSPlayer;

    VideoPlayerInstance() = default;
    ~VideoPlayerInstance();

    VideoPlayerInstance(const VideoPlayerInstance&) = delete;
    VideoPlayerInstance& operator=(const VideoPlayerInstance&) = delete;
};
