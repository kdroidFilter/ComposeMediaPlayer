#pragma once

#include <windows.h>
#include <mfapi.h>
#include <mfidl.h>
#include <mfreadwrite.h>
#include <audioclient.h>
#include <mmdeviceapi.h>
#include <endpointvolume.h>
#include <atomic>

// Forward declaration
class HLSPlayer;

/**
 * @brief Structure to encapsulate the state of a video player instance.
 */
struct VideoPlayerInstance {
    // Video related members
    IMFSourceReader* pSourceReader = nullptr; // Single reader for both audio & video
    IMFMediaBuffer* pLockedBuffer = nullptr;
    BYTE* pLockedBytes = nullptr;
    DWORD lockedMaxSize = 0;
    DWORD lockedCurrSize = 0;
    UINT32 videoWidth = 0;
    UINT32 videoHeight = 0;
    UINT32 nativeWidth = 0;   // Original video resolution (before scaling)
    UINT32 nativeHeight = 0;
    BOOL bEOF = FALSE;

    // Frame caching for paused state
    IMFSample* pCachedSample = nullptr;     // Cached sample for paused state
    BOOL bHasInitialFrame = FALSE;          // Whether we've read an initial frame when paused

    // Audio related members
    IMFSourceReader* pSourceReaderAudio = nullptr; // Separate reader for audio (no serialization with video)
    BOOL bHasAudio = FALSE;
    BOOL bAudioInitialized = FALSE;
    IAudioClient* pAudioClient = nullptr;
    IAudioRenderClient* pRenderClient = nullptr;
    IMMDevice* pDevice = nullptr;
    WAVEFORMATEX* pSourceAudioFormat = nullptr;
    HANDLE hAudioSamplesReadyEvent = nullptr;
    HANDLE hAudioThread = nullptr;
    BOOL bAudioThreadRunning = FALSE;
    HANDLE hAudioReadyEvent = nullptr;
    IAudioEndpointVolume* pAudioEndpointVolume = nullptr;

    // WASAPI latency: updated by audio thread, read by video thread for A/V sync
    std::atomic<double> audioLatencyMs{0.0};

    // Protects WASAPI GetBuffer/ReleaseBuffer vs Stop/Reset/Start during seeks
    CRITICAL_SECTION csAudioFeed{};

    // Media Foundation clock for synchronization
    IMFPresentationClock* pPresentationClock = nullptr;
    IMFMediaSource* pMediaSource = nullptr;
    BOOL bUseClockSync = FALSE;

    // Timing and synchronization
    LONGLONG llCurrentPosition = 0;
    ULONGLONG llPlaybackStartTime = 0;
    ULONGLONG llTotalPauseTime = 0;
    ULONGLONG llPauseStart = 0;
    CRITICAL_SECTION csClockSync{};
    BOOL bSeekInProgress = FALSE;

    // Playback control (atomic for lock-free access from the audio thread)
    std::atomic<float> instanceVolume{1.0f}; // Volume specific to this instance (1.0 = 100%)
    std::atomic<float> playbackSpeed{1.0f};  // Playback speed (1.0 = 100%)

    // Audio resampling fractional position for playback speed (audio thread only)
    double resampleFracPos = 0.0;


    // Network / HLS streaming
    BOOL bIsNetworkSource = FALSE;  // TRUE when URL is http:// or https://
    BOOL bIsLiveStream = FALSE;     // TRUE when duration is unknown (live HLS)
    HLSPlayer* pHLSPlayer = nullptr; // Non-null when playing HLS via IMFMediaEngine
};
