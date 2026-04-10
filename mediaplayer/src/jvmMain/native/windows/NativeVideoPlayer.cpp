// NativeVideoPlayer.cpp
#include "NativeVideoPlayer.h"
#include "VideoPlayerInstance.h"
#include "Utils.h"
#include "MediaFoundationManager.h"
#include "AudioManager.h"
#include "HLSPlayer.h"
#include <algorithm>
#include <cstring>
#include <mfapi.h>
#include <mferror.h>
#include <string>
#include <cctype>

// For IMF2DBuffer and IMF2DBuffer2 interfaces
#include <evr.h>

using namespace VideoPlayerUtils;
using namespace MediaFoundation;
using namespace AudioManager;

// ---------------------------------------------------------------------------
// Helper: detect HTTP/HTTPS URLs (network streaming sources incl. HLS)
// ---------------------------------------------------------------------------
static bool IsNetworkUrl(const wchar_t* url) {
    return (_wcsnicmp(url, L"http://", 7) == 0 || _wcsnicmp(url, L"https://", 8) == 0);
}

// ---------------------------------------------------------------------------
// Helper: detect HLS URLs (.m3u8 anywhere in URL, case-insensitive)
// ---------------------------------------------------------------------------
static bool IsHLSUrl(const wchar_t* url) {
    if (!url) return false;
    std::wstring lower(url);
    for (auto& ch : lower) ch = static_cast<wchar_t>(towlower(ch));
    return lower.find(L".m3u8") != std::wstring::npos;
}

// ---------------------------------------------------------------------------
// Helper: open HLS media via IMFMediaEngine
// ---------------------------------------------------------------------------
static HRESULT OpenMediaHLS(VideoPlayerInstance* pInstance, const wchar_t* url, BOOL startPlayback) {
    auto* hlsPlayer = new (std::nothrow) HLSPlayer();
    if (!hlsPlayer) return E_OUTOFMEMORY;

    HRESULT hr = hlsPlayer->Initialize(MediaFoundation::GetD3DDevice(),
                                        MediaFoundation::GetDXGIDeviceManager());
    if (FAILED(hr)) {
        delete hlsPlayer;
        return hr;
    }

    hr = hlsPlayer->Open(url);
    if (FAILED(hr)) {
        hlsPlayer->Close();
        delete hlsPlayer;
        return hr;
    }

    pInstance->pHLSPlayer      = hlsPlayer;
    pInstance->bIsNetworkSource = TRUE;

    // Dimensions
    hlsPlayer->GetVideoSize(&pInstance->videoWidth, &pInstance->videoHeight);
    pInstance->nativeWidth  = pInstance->videoWidth;
    pInstance->nativeHeight = pInstance->videoHeight;

    // Duration (0 → live stream)
    LONGLONG duration = 0;
    hlsPlayer->GetDuration(&duration);
    pInstance->bIsLiveStream = (duration == 0) ? TRUE : FALSE;

    // Audio is handled internally by the engine
    pInstance->bHasAudio = TRUE;

    if (startPlayback) {
        hlsPlayer->SetPlaying(TRUE);
        pInstance->llPlaybackStartTime = GetCurrentTimeMs();
        pInstance->llTotalPauseTime = 0;
        pInstance->llPauseStart     = 0;
    }

    return S_OK;
}

// Error code definitions from header
#define OP_E_NOT_INITIALIZED     ((HRESULT)0x80000001L)
#define OP_E_ALREADY_INITIALIZED ((HRESULT)0x80000002L)
#define OP_E_INVALID_PARAMETER   ((HRESULT)0x80000003L)

// Debug print macro
#ifdef _DEBUG
#define PrintHR(msg, hr) fprintf(stderr, "%s (hr=0x%08x)\n", msg, static_cast<unsigned int>(hr))
#else
#define PrintHR(msg, hr) ((void)0)
#endif

// ---------------------------------------------------------------------------
// Named constants for synchronization thresholds (issue #6)
// ---------------------------------------------------------------------------

// Default frame rate used when the actual rate cannot be determined
static constexpr UINT kDefaultFrameRateNum   = 30;
static constexpr UINT kDefaultFrameRateDenom = 1;

// A video frame that is more than this many frame intervals late is skipped
static constexpr double kFrameSkipThreshold = 3.0;

// Minimum "ahead" time (ms) before the renderer sleeps to pace the output
static constexpr double kFrameAheadMinMs = 1.0;

// Maximum wait time is clamped to this many frame intervals
static constexpr double kFrameMaxWaitIntervals = 2.0;

// Stabilisation delay (ms) used around audio client stop/start during seeks
static constexpr DWORD kSeekAudioSettleMs = 5;

// ---------------------------------------------------------------------------
// Helper: safely release a COM object
// ---------------------------------------------------------------------------
static inline void SafeRelease(IUnknown* obj) { if (obj) obj->Release(); }

// ---------------------------------------------------------------------------
// Helper: configure an MF audio media type with the given parameters.
//         If channels/sampleRate are 0, defaults of 2 / 48000 are used.
// ---------------------------------------------------------------------------
static HRESULT ConfigureAudioType(IMFMediaType* pType, UINT32 channels, UINT32 sampleRate) {
    if (channels == 0)    channels = 2;
    if (sampleRate == 0)  sampleRate = 48000;

    UINT32 bitsPerSample = 16;
    UINT32 blockAlign    = channels * (bitsPerSample / 8);
    UINT32 avgBytesPerSec = sampleRate * blockAlign;

    pType->SetGUID(MF_MT_MAJOR_TYPE, MFMediaType_Audio);
    pType->SetGUID(MF_MT_SUBTYPE, MFAudioFormat_PCM);
    pType->SetUINT32(MF_MT_AUDIO_NUM_CHANNELS, channels);
    pType->SetUINT32(MF_MT_AUDIO_SAMPLES_PER_SECOND, sampleRate);
    pType->SetUINT32(MF_MT_AUDIO_BLOCK_ALIGNMENT, blockAlign);
    pType->SetUINT32(MF_MT_AUDIO_AVG_BYTES_PER_SECOND, avgBytesPerSec);
    pType->SetUINT32(MF_MT_AUDIO_BITS_PER_SAMPLE, bitsPerSample);
    return S_OK;
}

// ---------------------------------------------------------------------------
// Helper: query the native channel count and sample rate of the first audio
//         stream so that the PCM conversion preserves them (issue #2).
// ---------------------------------------------------------------------------
static void QueryNativeAudioParams(IMFSourceReader* pReader, UINT32* pChannels, UINT32* pSampleRate) {
    *pChannels   = 0;
    *pSampleRate = 0;
    if (!pReader) return;

    IMFMediaType* pNativeType = nullptr;
    HRESULT hr = pReader->GetNativeMediaType(MF_SOURCE_READER_FIRST_AUDIO_STREAM, 0, &pNativeType);
    if (SUCCEEDED(hr) && pNativeType) {
        pNativeType->GetUINT32(MF_MT_AUDIO_NUM_CHANNELS, pChannels);
        pNativeType->GetUINT32(MF_MT_AUDIO_SAMPLES_PER_SECOND, pSampleRate);
        pNativeType->Release();
    }
}

// ---------------------------------------------------------------------------
// Helper: acquire the next video sample (handles pause/cache and timing sync).
//
// Returns:
//   S_OK    – *ppSample is set (may be nullptr if the frame was skipped).
//   S_FALSE – end of stream reached (bEOF set on the instance).
//   other   – error HRESULT.
// ---------------------------------------------------------------------------
static HRESULT AcquireNextSample(VideoPlayerInstance* pInstance, IMFSample** ppSample) {
    *ppSample = nullptr;

    BOOL isPaused = (pInstance->llPauseStart != 0);
    IMFSample* pSample = nullptr;
    HRESULT hr = S_OK;
    DWORD streamIndex = 0, dwFlags = 0;
    LONGLONG llTimestamp = 0;

    if (isPaused) {
        // ----- Paused path: read one frame and cache, or reuse cached frame -----
        if (!pInstance->bHasInitialFrame) {
            hr = pInstance->pSourceReader->ReadSample(
                MF_SOURCE_READER_FIRST_VIDEO_STREAM, 0,
                &streamIndex, &dwFlags, &llTimestamp, &pSample);
            if (FAILED(hr)) return hr;

            if (dwFlags & MF_SOURCE_READERF_ENDOFSTREAM) {
                pInstance->bEOF = TRUE;
                if (pSample) pSample->Release();
                return S_FALSE;
            }

            // HLS adaptive bitrate: handle media type changes (resolution switch)
            if (dwFlags & MF_SOURCE_READERF_NATIVEMEDIATYPECHANGED) {
                // Re-apply desired output format after native format change
                IMFMediaType* pNewType = nullptr;
                if (SUCCEEDED(MFCreateMediaType(&pNewType))) {
                    pNewType->SetGUID(MF_MT_MAJOR_TYPE, MFMediaType_Video);
                    pNewType->SetGUID(MF_MT_SUBTYPE, MFVideoFormat_RGB32);
                    pInstance->pSourceReader->SetCurrentMediaType(
                        MF_SOURCE_READER_FIRST_VIDEO_STREAM, nullptr, pNewType);
                    SafeRelease(pNewType);
                }
            }
            if (dwFlags & MF_SOURCE_READERF_CURRENTMEDIATYPECHANGED) {
                IMFMediaType* pCurrent = nullptr;
                if (SUCCEEDED(pInstance->pSourceReader->GetCurrentMediaType(
                        MF_SOURCE_READER_FIRST_VIDEO_STREAM, &pCurrent))) {
                    UINT32 newW = 0, newH = 0;
                    MFGetAttributeSize(pCurrent, MF_MT_FRAME_SIZE, &newW, &newH);
                    if (newW > 0 && newH > 0) {
                        pInstance->videoWidth = newW;
                        pInstance->videoHeight = newH;
                    }
                    SafeRelease(pCurrent);
                }
            }

            if (!pSample) return S_OK; // decoder starved

            if (pInstance->pCachedSample) {
                pInstance->pCachedSample->Release();
                pInstance->pCachedSample = nullptr;
            }
            pSample->AddRef();
            pInstance->pCachedSample = pSample;
            pInstance->bHasInitialFrame = TRUE;
        } else {
            if (pInstance->pCachedSample) {
                pSample = pInstance->pCachedSample;
                pSample->AddRef();
            } else {
                return S_OK; // no cached sample available
            }
        }
    } else {
        // ----- Playing path: decode a new frame -----
        hr = pInstance->pSourceReader->ReadSample(
            MF_SOURCE_READER_FIRST_VIDEO_STREAM, 0,
            &streamIndex, &dwFlags, &llTimestamp, &pSample);
        if (FAILED(hr)) return hr;

        if (dwFlags & MF_SOURCE_READERF_ENDOFSTREAM) {
            pInstance->bEOF = TRUE;
            if (pSample) pSample->Release();
            return S_FALSE;
        }

        // HLS adaptive bitrate: handle media type changes (resolution switch)
        if (dwFlags & MF_SOURCE_READERF_NATIVEMEDIATYPECHANGED) {
            IMFMediaType* pNewType = nullptr;
            if (SUCCEEDED(MFCreateMediaType(&pNewType))) {
                pNewType->SetGUID(MF_MT_MAJOR_TYPE, MFMediaType_Video);
                pNewType->SetGUID(MF_MT_SUBTYPE, MFVideoFormat_RGB32);
                pInstance->pSourceReader->SetCurrentMediaType(
                    MF_SOURCE_READER_FIRST_VIDEO_STREAM, nullptr, pNewType);
                SafeRelease(pNewType);
            }
        }
        if (dwFlags & MF_SOURCE_READERF_CURRENTMEDIATYPECHANGED) {
            IMFMediaType* pCurrent = nullptr;
            if (SUCCEEDED(pInstance->pSourceReader->GetCurrentMediaType(
                    MF_SOURCE_READER_FIRST_VIDEO_STREAM, &pCurrent))) {
                UINT32 newW = 0, newH = 0;
                MFGetAttributeSize(pCurrent, MF_MT_FRAME_SIZE, &newW, &newH);
                if (newW > 0 && newH > 0) {
                    pInstance->videoWidth = newW;
                    pInstance->videoHeight = newH;
                }
                SafeRelease(pCurrent);
            }
        }

        if (!pSample) return S_OK; // decoder starved

        // Release any cached sample from a previous pause — not needed during playback
        if (pInstance->pCachedSample) {
            pInstance->pCachedSample->Release();
            pInstance->pCachedSample = nullptr;
        }

        // On the first decoded frame after play/seek, recalibrate the wall clock
        // so that any decode or network latency doesn't cause mass frame skipping.
        // This is critical for HTTP sources where ReadSample may block for seconds.
        if (!pInstance->bHasInitialFrame) {
            if (pInstance->bUseClockSync && pInstance->llPlaybackStartTime != 0) {
                double frameTimeMs = llTimestamp / 10000.0;
                double adjustedMs = frameTimeMs / static_cast<double>(pInstance->playbackSpeed.load());
                pInstance->llPlaybackStartTime = GetCurrentTimeMs() - static_cast<LONGLONG>(adjustedMs);
                pInstance->llTotalPauseTime = 0;
            }
            pInstance->bHasInitialFrame = TRUE;
        }

        pInstance->llCurrentPosition = llTimestamp;
    }

    // ----- Frame timing synchronization (wall-clock based) -----
    if (!isPaused && pInstance->bUseClockSync &&
        pInstance->llPlaybackStartTime != 0 && llTimestamp > 0) {

        LONGLONG currentTimeMs = GetCurrentTimeMs();
        LONGLONG elapsedMs = currentTimeMs - pInstance->llPlaybackStartTime - pInstance->llTotalPauseTime;
        double adjustedElapsedMs = elapsedMs * pInstance->playbackSpeed.load();
        double frameTimeMs = llTimestamp / 10000.0;

        // Determine frame interval, guarding against division by zero (issue #3)
        UINT frameRateNum = kDefaultFrameRateNum, frameRateDenom = kDefaultFrameRateDenom;
        GetVideoFrameRate(pInstance, &frameRateNum, &frameRateDenom);
        if (frameRateNum == 0) {
            frameRateNum  = kDefaultFrameRateNum;
            frameRateDenom = kDefaultFrameRateDenom;
        }
        double frameIntervalMs = 1000.0 * frameRateDenom / frameRateNum;

        double diffMs = frameTimeMs - adjustedElapsedMs;

        if (diffMs < -frameIntervalMs * kFrameSkipThreshold) {
            // Frame is very late — skip it
            pSample->Release();
            *ppSample = nullptr;
            return S_OK;
        } else if (diffMs > kFrameAheadMinMs) {
            double waitTime = std::min(diffMs, frameIntervalMs * kFrameMaxWaitIntervals);
            PreciseSleepHighRes(waitTime);
        }
    }

    *ppSample = pSample;
    return S_OK;
}

// ====================================================================
// API Implementation
// ====================================================================

NATIVEVIDEOPLAYER_API int GetNativeVersion() {
    return NATIVE_VIDEO_PLAYER_VERSION;
}

NATIVEVIDEOPLAYER_API HRESULT InitMediaFoundation() {
    return Initialize();
}

NATIVEVIDEOPLAYER_API HRESULT CreateVideoPlayerInstance(VideoPlayerInstance** ppInstance) {
    if (!ppInstance)
        return E_INVALIDARG;

    // Ensure Media Foundation is initialized
    if (!IsInitialized()) {
        HRESULT hr = Initialize();
        if (FAILED(hr))
            return hr;
    }

    auto* pInstance = new (std::nothrow) VideoPlayerInstance();
    if (!pInstance)
        return E_OUTOFMEMORY;

    InitializeCriticalSection(&pInstance->csClockSync);
    pInstance->bUseClockSync = TRUE;

    pInstance->hAudioReadyEvent = CreateEvent(nullptr, FALSE, FALSE, nullptr);
    if (!pInstance->hAudioReadyEvent) {
        DeleteCriticalSection(&pInstance->csClockSync);
        delete pInstance;
        return HRESULT_FROM_WIN32(GetLastError());
    }

    IncrementInstanceCount();
    *ppInstance = pInstance;
    return S_OK;
}

NATIVEVIDEOPLAYER_API void DestroyVideoPlayerInstance(VideoPlayerInstance* pInstance) {
    if (pInstance) {
        CloseMedia(pInstance);

        if (pInstance->pCachedSample) {
            pInstance->pCachedSample->Release();
            pInstance->pCachedSample = nullptr;
        }

        DeleteCriticalSection(&pInstance->csClockSync);
        delete pInstance;
        DecrementInstanceCount();
    }
}

NATIVEVIDEOPLAYER_API HRESULT OpenMedia(VideoPlayerInstance* pInstance, const wchar_t* url, BOOL startPlayback) {
    if (!pInstance || !url)
        return OP_E_INVALID_PARAMETER;
    if (!IsInitialized())
        return OP_E_NOT_INITIALIZED;

    // Close previous media and reset state
    CloseMedia(pInstance);
    pInstance->bEOF = FALSE;
    pInstance->videoWidth = pInstance->videoHeight = 0;
    pInstance->bHasAudio = FALSE;

    pInstance->bHasInitialFrame = FALSE;
    if (pInstance->pCachedSample) {
        pInstance->pCachedSample->Release();
        pInstance->pCachedSample = nullptr;
    }

    HRESULT hr = S_OK;

    // Detect network sources (HTTP/HTTPS — includes HLS .m3u8 streams)
    const bool isNetwork = IsNetworkUrl(url);
    pInstance->bIsNetworkSource = isNetwork ? TRUE : FALSE;
    pInstance->bIsLiveStream = FALSE;

    // HLS streams (.m3u8): use IMFMediaEngine which has native HLS support
    if (isNetwork && IsHLSUrl(url)) {
        return OpenMediaHLS(pInstance, url, startPlayback);
    }

    // 1. Configure and open media source with both audio and video streams
    // ------------------------------------------------------------------
    IMFAttributes* pAttributes = nullptr;
    hr = MFCreateAttributes(&pAttributes, 6);
    if (FAILED(hr))
        return hr;

    pAttributes->SetUINT32(MF_READWRITE_ENABLE_HARDWARE_TRANSFORMS, TRUE);
    pAttributes->SetUINT32(MF_SOURCE_READER_DISABLE_DXVA, FALSE);
    pAttributes->SetUnknown(MF_SOURCE_READER_D3D_MANAGER, GetDXGIDeviceManager());
    pAttributes->SetUINT32(MF_SOURCE_READER_ENABLE_ADVANCED_VIDEO_PROCESSING, TRUE);

    // For network/HLS sources: hint the pipeline to reduce buffering latency
    if (isNetwork) {
        pAttributes->SetUINT32(MF_LOW_LATENCY, TRUE);
    }

    hr = MFCreateSourceReaderFromURL(url, pAttributes, &pInstance->pSourceReader);
    SafeRelease(pAttributes);
    if (FAILED(hr)) {
        // Fallback: for network sources that fail with "unsupported byte stream",
        // try the IMFMediaEngine path (handles HLS and other streaming formats)
        if (isNetwork && hr == static_cast<HRESULT>(0xC00D36C4)) {
            return OpenMediaHLS(pInstance, url, startPlayback);
        }
        return hr;
    }

    // 2. Configure video stream (RGB32)
    // ------------------------------------------
    hr = pInstance->pSourceReader->SetStreamSelection(MF_SOURCE_READER_ALL_STREAMS, FALSE);
    if (SUCCEEDED(hr))
        hr = pInstance->pSourceReader->SetStreamSelection(MF_SOURCE_READER_FIRST_VIDEO_STREAM, TRUE);
    if (FAILED(hr))
        return hr;

    IMFMediaType* pType = nullptr;
    hr = MFCreateMediaType(&pType);
    if (SUCCEEDED(hr)) {
        hr = pType->SetGUID(MF_MT_MAJOR_TYPE, MFMediaType_Video);
        if (SUCCEEDED(hr))
            hr = pType->SetGUID(MF_MT_SUBTYPE, MFVideoFormat_RGB32);
        if (SUCCEEDED(hr))
            hr = pInstance->pSourceReader->SetCurrentMediaType(MF_SOURCE_READER_FIRST_VIDEO_STREAM, nullptr, pType);
        SafeRelease(pType);
    }
    if (FAILED(hr))
        return hr;

    // Retrieve video dimensions (this is the native resolution of the video)
    IMFMediaType* pCurrent = nullptr;
    hr = pInstance->pSourceReader->GetCurrentMediaType(MF_SOURCE_READER_FIRST_VIDEO_STREAM, &pCurrent);
    if (SUCCEEDED(hr)) {
        hr = MFGetAttributeSize(pCurrent, MF_MT_FRAME_SIZE, &pInstance->videoWidth, &pInstance->videoHeight);
        pInstance->nativeWidth  = pInstance->videoWidth;
        pInstance->nativeHeight = pInstance->videoHeight;
        SafeRelease(pCurrent);
    }

    // 3. Configure audio stream (if available)
    // ------------------------------------------
    hr = pInstance->pSourceReader->SetStreamSelection(MF_SOURCE_READER_FIRST_AUDIO_STREAM, TRUE);
    if (SUCCEEDED(hr)) {
        // Try native audio params first, fall back to 2ch/48kHz if WASAPI rejects them
        UINT32 nativeChannels = 0, nativeSampleRate = 0;
        QueryNativeAudioParams(pInstance->pSourceReader, &nativeChannels, &nativeSampleRate);

        // Helper lambda: configure audio on reader, init WASAPI, return success
        auto tryAudioFormat = [&](UINT32 ch, UINT32 sr) -> bool {
            IMFMediaType* pWantedType = nullptr;
            HRESULT hrt = MFCreateMediaType(&pWantedType);
            if (FAILED(hrt)) return false;
            ConfigureAudioType(pWantedType, ch, sr);
            hrt = pInstance->pSourceReader->SetCurrentMediaType(MF_SOURCE_READER_FIRST_AUDIO_STREAM, nullptr, pWantedType);
            SafeRelease(pWantedType);
            if (FAILED(hrt)) return false;

            IMFMediaType* pActualType = nullptr;
            hrt = pInstance->pSourceReader->GetCurrentMediaType(MF_SOURCE_READER_FIRST_AUDIO_STREAM, &pActualType);
            if (FAILED(hrt) || !pActualType) return false;

            WAVEFORMATEX* pWfx = nullptr;
            UINT32 size = 0;
            hrt = MFCreateWaveFormatExFromMFMediaType(pActualType, &pWfx, &size);
            SafeRelease(pActualType);
            if (FAILED(hrt) || !pWfx) return false;

            hrt = InitWASAPI(pInstance, pWfx);
            if (FAILED(hrt)) {
                PrintHR("InitWASAPI failed", hrt);
                CoTaskMemFree(pWfx);
                return false;
            }
            if (pInstance->pSourceAudioFormat) CoTaskMemFree(pInstance->pSourceAudioFormat);
            pInstance->pSourceAudioFormat = pWfx;
            pInstance->bHasAudio = TRUE;
            return true;
        };

        // First try native format, then fall back to safe stereo 48kHz
        if (!tryAudioFormat(nativeChannels, nativeSampleRate)) {
            if (nativeChannels != 2 || nativeSampleRate != 48000) {
                tryAudioFormat(2, 48000);
            }
        }

        // Create a separate audio source reader for the audio thread
        IMFAttributes* pAudioAttrs = nullptr;
        if (isNetwork) {
            MFCreateAttributes(&pAudioAttrs, 1);
            if (pAudioAttrs) pAudioAttrs->SetUINT32(MF_LOW_LATENCY, TRUE);
        }
        hr = MFCreateSourceReaderFromURL(url, pAudioAttrs, &pInstance->pSourceReaderAudio);
        SafeRelease(pAudioAttrs);
        if (SUCCEEDED(hr)) {
            hr = pInstance->pSourceReaderAudio->SetStreamSelection(MF_SOURCE_READER_ALL_STREAMS, FALSE);
            if (SUCCEEDED(hr))
                hr = pInstance->pSourceReaderAudio->SetStreamSelection(MF_SOURCE_READER_FIRST_AUDIO_STREAM, TRUE);

            if (SUCCEEDED(hr)) {
                // Use the same format that succeeded for the main reader
                UINT32 usedCh = pInstance->pSourceAudioFormat ? pInstance->pSourceAudioFormat->nChannels : 2;
                UINT32 usedSr = pInstance->pSourceAudioFormat ? pInstance->pSourceAudioFormat->nSamplesPerSec : 48000;

                IMFMediaType* pWantedAudioType = nullptr;
                hr = MFCreateMediaType(&pWantedAudioType);
                if (SUCCEEDED(hr)) {
                    ConfigureAudioType(pWantedAudioType, usedCh, usedSr);
                    hr = pInstance->pSourceReaderAudio->SetCurrentMediaType(MF_SOURCE_READER_FIRST_AUDIO_STREAM, nullptr, pWantedAudioType);
                    SafeRelease(pWantedAudioType);
                }
            }

            if (FAILED(hr)) {
                PrintHR("Failed to configure audio source reader", hr);
                SafeRelease(pInstance->pSourceReaderAudio);
                pInstance->pSourceReaderAudio = nullptr;
            }
        } else {
            PrintHR("Failed to create audio source reader", hr);
        }
    }

    if (pInstance->bUseClockSync) {
        // 4. Set up presentation clock for synchronization
        // ----------------------------------------------------------
        hr = pInstance->pSourceReader->GetServiceForStream(
            MF_SOURCE_READER_MEDIASOURCE,
            GUID_NULL,
            IID_PPV_ARGS(&pInstance->pMediaSource));

        if (SUCCEEDED(hr)) {
            hr = MFCreatePresentationClock(&pInstance->pPresentationClock);
            if (SUCCEEDED(hr)) {
                IMFPresentationTimeSource* pTimeSource = nullptr;
                hr = MFCreateSystemTimeSource(&pTimeSource);
                if (SUCCEEDED(hr)) {
                    hr = pInstance->pPresentationClock->SetTimeSource(pTimeSource);
                    if (SUCCEEDED(hr)) {
                        IMFRateControl* pRateControl = nullptr;
                        hr = pInstance->pPresentationClock->QueryInterface(IID_PPV_ARGS(&pRateControl));
                        if (SUCCEEDED(hr)) {
                            hr = pRateControl->SetRate(FALSE, 1.0f);
                            if (FAILED(hr)) {
                                PrintHR("Failed to set initial presentation clock rate", hr);
                            }
                            pRateControl->Release();
                        }

                        IMFMediaSink* pMediaSink = nullptr;
                        hr = pInstance->pMediaSource->QueryInterface(IID_PPV_ARGS(&pMediaSink));
                        if (SUCCEEDED(hr)) {
                            IMFClockStateSink* pClockStateSink = nullptr;
                            hr = pMediaSink->QueryInterface(IID_PPV_ARGS(&pClockStateSink));
                            if (SUCCEEDED(hr)) {
                                if (startPlayback) {
                                    hr = pInstance->pPresentationClock->Start(0);
                                    if (FAILED(hr)) {
                                        PrintHR("Failed to start presentation clock", hr);
                                    }
                                } else {
                                    // Keep the player paused until explicitly started
                                    hr = pInstance->pPresentationClock->Pause();
                                    if (FAILED(hr)) {
                                        PrintHR("Failed to pause presentation clock", hr);
                                    }
                                }
                                pClockStateSink->Release();
                            }
                            pMediaSink->Release();
                        } else {
                            PrintHR("Failed to get media sink from media source", hr);
                        }
                    }
                    SafeRelease(pTimeSource);
                }
            }
        }
    }

    // 5. Initialize playback timing and start audio thread
    // ----------------------------------------------------
    if (startPlayback) {
        pInstance->llPlaybackStartTime = GetCurrentTimeMs();
        pInstance->llTotalPauseTime = 0;
        pInstance->llPauseStart = 0;

        if (pInstance->bHasAudio && pInstance->bAudioInitialized && pInstance->pSourceReaderAudio) {
            hr = StartAudioThread(pInstance);
            if (FAILED(hr)) {
                PrintHR("StartAudioThread failed", hr);
            }
        }
    }

    return S_OK;
}

// ---------------------------------------------------------------------------
// ReadVideoFrame — locks a frame buffer and returns a pointer to the caller
// ---------------------------------------------------------------------------
NATIVEVIDEOPLAYER_API HRESULT ReadVideoFrame(VideoPlayerInstance* pInstance, BYTE** pData, DWORD* pDataSize) {
    if (!pInstance || !pData || !pDataSize)
        return OP_E_NOT_INITIALIZED;

    // HLS path — delegate to IMFMediaEngine
    if (pInstance->pHLSPlayer) {
        return pInstance->pHLSPlayer->ReadFrame(pData, pDataSize);
    }

    if (!pInstance->pSourceReader)
        return OP_E_NOT_INITIALIZED;

    if (pInstance->pLockedBuffer)
        UnlockVideoFrame(pInstance);

    if (pInstance->bEOF) {
        *pData = nullptr;
        *pDataSize = 0;
        return S_FALSE;
    }

    IMFSample* pSample = nullptr;
    HRESULT hr = AcquireNextSample(pInstance, &pSample);

    if (hr == S_FALSE) {
        // End of stream
        *pData = nullptr;
        *pDataSize = 0;
        return S_FALSE;
    }
    if (FAILED(hr))
        return hr;
    if (!pSample) {
        // Frame was skipped or decoder starved
        *pData = nullptr;
        *pDataSize = 0;
        return S_OK;
    }

    // Lock the buffer and expose its pointer to the caller
    IMFMediaBuffer* pBuffer = nullptr;
    DWORD bufferCount = 0;
    hr = pSample->GetBufferCount(&bufferCount);
    if (SUCCEEDED(hr) && bufferCount == 1) {
        hr = pSample->GetBufferByIndex(0, &pBuffer);
    } else {
        hr = pSample->ConvertToContiguousBuffer(&pBuffer);
    }
    if (FAILED(hr)) {
        PrintHR("Failed to get contiguous buffer", hr);
        pSample->Release();
        return hr;
    }

    BYTE* pBytes = nullptr;
    DWORD cbMax = 0, cbCurr = 0;
    hr = pBuffer->Lock(&pBytes, &cbMax, &cbCurr);
    if (FAILED(hr)) {
        PrintHR("Buffer->Lock failed", hr);
        pBuffer->Release();
        pSample->Release();
        return hr;
    }

    // Force alpha byte to 0xFF — MFVideoFormat_RGB32 (X8R8G8B8) leaves the
    // high byte undefined, which causes washed-out colours when Skia
    // composites the frame against the window background.
    {
        const DWORD pixelCount = cbCurr / 4;
        DWORD* px = reinterpret_cast<DWORD*>(pBytes);
        for (DWORD i = 0; i < pixelCount; ++i)
            px[i] |= 0xFF000000;
    }

    pInstance->pLockedBuffer = pBuffer;
    pInstance->pLockedBytes = pBytes;
    pInstance->lockedMaxSize = cbMax;
    pInstance->lockedCurrSize = cbCurr;
    *pData = pBytes;
    *pDataSize = cbCurr;
    pSample->Release();
    return S_OK;
}

NATIVEVIDEOPLAYER_API HRESULT UnlockVideoFrame(VideoPlayerInstance* pInstance) {
    if (!pInstance)
        return E_INVALIDARG;
    if (pInstance->pHLSPlayer) {
        pInstance->pHLSPlayer->UnlockFrame();
        return S_OK;
    }
    if (pInstance->pLockedBuffer) {
        pInstance->pLockedBuffer->Unlock();
        pInstance->pLockedBuffer->Release();
        pInstance->pLockedBuffer = nullptr;
    }
    pInstance->pLockedBytes = nullptr;
    pInstance->lockedMaxSize = pInstance->lockedCurrSize = 0;
    return S_OK;
}

// ---------------------------------------------------------------------------
// ReadVideoFrameInto — copies the decoded frame into a caller-owned buffer
// ---------------------------------------------------------------------------
NATIVEVIDEOPLAYER_API HRESULT ReadVideoFrameInto(
    VideoPlayerInstance* pInstance,
    BYTE* pDst,
    DWORD dstRowBytes,
    DWORD dstCapacity,
    LONGLONG* pTimestamp) {

    if (!pInstance || !pDst || dstRowBytes == 0 || dstCapacity == 0)
        return OP_E_INVALID_PARAMETER;
    if (!pInstance->pSourceReader)
        return OP_E_NOT_INITIALIZED;
    if (pInstance->pLockedBuffer)
        UnlockVideoFrame(pInstance);

    if (pInstance->bEOF) {
        if (pTimestamp) *pTimestamp = pInstance->llCurrentPosition;
        return S_FALSE;
    }

    IMFSample* pSample = nullptr;
    HRESULT hr = AcquireNextSample(pInstance, &pSample);

    if (hr == S_FALSE) {
        if (pTimestamp) *pTimestamp = pInstance->llCurrentPosition;
        return S_FALSE;
    }
    if (FAILED(hr))
        return hr;
    if (!pSample) {
        if (pTimestamp) *pTimestamp = pInstance->llCurrentPosition;
        return S_OK;
    }

    if (pTimestamp)
        *pTimestamp = pInstance->llCurrentPosition;

    const UINT32 width = pInstance->videoWidth;
    const UINT32 height = pInstance->videoHeight;
    if (width == 0 || height == 0) {
        pSample->Release();
        return S_FALSE;
    }

    const DWORD requiredDst = dstRowBytes * height;
    if (dstCapacity < requiredDst) {
        pSample->Release();
        return OP_E_INVALID_PARAMETER;
    }

    // Try to use IMF2DBuffer2 for optimized zero-copy access
    IMFMediaBuffer* pBuffer = nullptr;
    hr = pSample->ConvertToContiguousBuffer(&pBuffer);
    if (FAILED(hr)) {
        pSample->Release();
        return hr;
    }

    // Attempt IMF2DBuffer2 for direct 2D access (most efficient)
    IMF2DBuffer2* p2DBuffer2 = nullptr;
    IMF2DBuffer* p2DBuffer = nullptr;
    BYTE* pScanline0 = nullptr;
    LONG srcPitch = 0;
    BYTE* pBufferStart = nullptr;
    DWORD cbBufferLength = 0;
    bool usedDirect2D = false;

    hr = pBuffer->QueryInterface(IID_PPV_ARGS(&p2DBuffer2));
    if (SUCCEEDED(hr) && p2DBuffer2) {
        hr = p2DBuffer2->Lock2DSize(MF2DBuffer_LockFlags_Read, &pScanline0, &srcPitch, &pBufferStart, &cbBufferLength);
        if (SUCCEEDED(hr)) {
            usedDirect2D = true;
            const DWORD srcRowBytes = width * 4;

            if (static_cast<LONG>(dstRowBytes) == srcPitch && static_cast<LONG>(srcRowBytes) == srcPitch) {
                memcpy(pDst, pScanline0, srcRowBytes * height);
            } else {
                BYTE* pSrc = pScanline0;
                BYTE* pDstRow = pDst;
                const DWORD copyBytes = std::min(srcRowBytes, dstRowBytes);
                for (UINT32 y = 0; y < height; y++) {
                    memcpy(pDstRow, pSrc, copyBytes);
                    pSrc += srcPitch;
                    pDstRow += dstRowBytes;
                }
            }
            p2DBuffer2->Unlock2D();
        }
        p2DBuffer2->Release();
    }

    // Fallback to IMF2DBuffer
    if (!usedDirect2D) {
        hr = pBuffer->QueryInterface(IID_PPV_ARGS(&p2DBuffer));
        if (SUCCEEDED(hr) && p2DBuffer) {
            hr = p2DBuffer->Lock2D(&pScanline0, &srcPitch);
            if (SUCCEEDED(hr)) {
                usedDirect2D = true;
                const DWORD srcRowBytes = width * 4;

                if (static_cast<LONG>(dstRowBytes) == srcPitch && static_cast<LONG>(srcRowBytes) == srcPitch) {
                    memcpy(pDst, pScanline0, srcRowBytes * height);
                } else {
                    BYTE* pSrc = pScanline0;
                    BYTE* pDstRow = pDst;
                    const DWORD copyBytes = std::min(srcRowBytes, dstRowBytes);
                    for (UINT32 y = 0; y < height; y++) {
                        memcpy(pDstRow, pSrc, copyBytes);
                        pSrc += srcPitch;
                        pDstRow += dstRowBytes;
                    }
                }
                p2DBuffer->Unlock2D();
            }
            p2DBuffer->Release();
        }
    }

    // Ultimate fallback to standard buffer lock
    if (!usedDirect2D) {
        BYTE* pBytes = nullptr;
        DWORD cbMax = 0, cbCurr = 0;
        hr = pBuffer->Lock(&pBytes, &cbMax, &cbCurr);
        if (SUCCEEDED(hr)) {
            const DWORD srcRowBytes = width * 4;
            const DWORD requiredSrc = srcRowBytes * height;
            if (cbCurr >= requiredSrc) {
                MFCopyImage(pDst, dstRowBytes, pBytes, srcRowBytes, srcRowBytes, height);
            }
            pBuffer->Unlock();
        }
    }

    pBuffer->Release();
    pSample->Release();
    return S_OK;
}

NATIVEVIDEOPLAYER_API BOOL IsEOF(const VideoPlayerInstance* pInstance) {
    if (!pInstance)
        return FALSE;
    if (pInstance->pHLSPlayer)
        return pInstance->pHLSPlayer->IsEOF();
    return pInstance->bEOF;
}

NATIVEVIDEOPLAYER_API void GetVideoSize(const VideoPlayerInstance* pInstance, UINT32* pWidth, UINT32* pHeight) {
    if (!pInstance)
        return;
    if (pInstance->pHLSPlayer) {
        pInstance->pHLSPlayer->GetVideoSize(pWidth, pHeight);
        return;
    }
    if (pWidth)  *pWidth = pInstance->videoWidth;
    if (pHeight) *pHeight = pInstance->videoHeight;
}

NATIVEVIDEOPLAYER_API HRESULT GetVideoFrameRate(const VideoPlayerInstance* pInstance, UINT* pNum, UINT* pDenom) {
    if (!pInstance || !pNum || !pDenom) return OP_E_NOT_INITIALIZED;

    // HLS: frame rate is variable, default to 30fps
    if (pInstance->pHLSPlayer) {
        *pNum   = 30;
        *pDenom = 1;
        return S_OK;
    }

    if (!pInstance->pSourceReader) return OP_E_NOT_INITIALIZED;

    IMFMediaType* pType = nullptr;
    HRESULT hr = pInstance->pSourceReader->GetCurrentMediaType(MF_SOURCE_READER_FIRST_VIDEO_STREAM, &pType);
    if (SUCCEEDED(hr)) {
        hr = MFGetAttributeRatio(pType, MF_MT_FRAME_RATE, pNum, pDenom);
        pType->Release();
    }
    return hr;
}

NATIVEVIDEOPLAYER_API HRESULT SeekMedia(VideoPlayerInstance* pInstance, LONGLONG llPositionIn100Ns) {
    if (!pInstance) return OP_E_NOT_INITIALIZED;

    if (pInstance->pHLSPlayer)
        return pInstance->pHLSPlayer->Seek(llPositionIn100Ns);

    if (!pInstance->pSourceReader)
        return OP_E_NOT_INITIALIZED;

    EnterCriticalSection(&pInstance->csClockSync);
    pInstance->bSeekInProgress = TRUE;
    LeaveCriticalSection(&pInstance->csClockSync);

    if (pInstance->llPauseStart != 0) {
        pInstance->llTotalPauseTime += (GetCurrentTimeMs() - pInstance->llPauseStart);
        pInstance->llPauseStart = GetCurrentTimeMs();
    }

    if (pInstance->pLockedBuffer)
        UnlockVideoFrame(pInstance);

    // Release cached sample when seeking
    if (pInstance->pCachedSample) {
        pInstance->pCachedSample->Release();
        pInstance->pCachedSample = nullptr;
    }
    pInstance->bHasInitialFrame = FALSE;

    PROPVARIANT var;
    PropVariantInit(&var);
    var.vt = VT_I8;
    var.hVal.QuadPart = llPositionIn100Ns;

    bool wasPlaying = false;
    if (pInstance->bHasAudio && pInstance->pAudioClient) {
        wasPlaying = (pInstance->llPauseStart == 0);
        pInstance->pAudioClient->Stop();
        Sleep(kSeekAudioSettleMs);
    }

    // Stop the presentation clock
    if (pInstance->bUseClockSync && pInstance->pPresentationClock) {
        pInstance->pPresentationClock->Stop();
    }

    // Seek the main source reader
    HRESULT hr = pInstance->pSourceReader->SetCurrentPosition(GUID_NULL, var);
    if (FAILED(hr)) {
        EnterCriticalSection(&pInstance->csClockSync);
        pInstance->bSeekInProgress = FALSE;
        LeaveCriticalSection(&pInstance->csClockSync);
        PropVariantClear(&var);
        return hr;
    }

    // Also seek the audio source reader if available
    if (pInstance->pSourceReaderAudio) {
        PROPVARIANT varAudio;
        PropVariantInit(&varAudio);
        varAudio.vt = VT_I8;
        varAudio.hVal.QuadPart = llPositionIn100Ns;

        HRESULT hrAudio = pInstance->pSourceReaderAudio->SetCurrentPosition(GUID_NULL, varAudio);
        if (FAILED(hrAudio)) {
            PrintHR("Failed to seek audio source reader", hrAudio);
        }
        PropVariantClear(&varAudio);
    }

    // Reset audio client if needed
    if (pInstance->bHasAudio && pInstance->pRenderClient && pInstance->pAudioClient) {
        UINT32 bufferFrameCount = 0;
        if (SUCCEEDED(pInstance->pAudioClient->GetBufferSize(&bufferFrameCount))) {
            pInstance->pAudioClient->Reset();
        }
    }

    PropVariantClear(&var);

    // Update position and state
    EnterCriticalSection(&pInstance->csClockSync);
    pInstance->llCurrentPosition = llPositionIn100Ns;
    pInstance->bSeekInProgress = FALSE;
    LeaveCriticalSection(&pInstance->csClockSync);

    pInstance->bEOF = FALSE;

    // Reset timing for A/V sync after seek:
    // Adjust llPlaybackStartTime so that elapsed time matches the seek position.
    if (pInstance->bUseClockSync) {
        double seekPositionMs = llPositionIn100Ns / 10000.0;
        double adjustedSeekMs = seekPositionMs / static_cast<double>(pInstance->playbackSpeed.load());
        pInstance->llPlaybackStartTime = GetCurrentTimeMs() - static_cast<LONGLONG>(adjustedSeekMs);
        pInstance->llTotalPauseTime = 0;

        if (!wasPlaying) {
            pInstance->llPauseStart = GetCurrentTimeMs();
        } else {
            pInstance->llPauseStart = 0;
        }
    }

    // Restart the presentation clock at the new position
    if (pInstance->bUseClockSync && pInstance->pPresentationClock) {
        hr = pInstance->pPresentationClock->Start(llPositionIn100Ns);
        if (FAILED(hr)) {
            PrintHR("Failed to restart presentation clock after seek", hr);
        }
    }

    // Restart audio if it was playing
    if (pInstance->bHasAudio && pInstance->pAudioClient && wasPlaying) {
        Sleep(kSeekAudioSettleMs);
        pInstance->pAudioClient->Start();
    }

    // Signal audio thread to continue
    if (pInstance->hAudioReadyEvent)
        SetEvent(pInstance->hAudioReadyEvent);

    return S_OK;
}

NATIVEVIDEOPLAYER_API HRESULT GetMediaDuration(const VideoPlayerInstance* pInstance, LONGLONG* pDuration) {
    if (!pInstance || !pDuration) return OP_E_NOT_INITIALIZED;

    if (pInstance->pHLSPlayer)
        return pInstance->pHLSPlayer->GetDuration(pDuration);

    if (!pInstance->pSourceReader)
        return OP_E_NOT_INITIALIZED;

    *pDuration = 0;

    IMFMediaSource* pMediaSource = nullptr;
    IMFPresentationDescriptor* pPresentationDescriptor = nullptr;
    HRESULT hr = pInstance->pSourceReader->GetServiceForStream(MF_SOURCE_READER_MEDIASOURCE, GUID_NULL, IID_PPV_ARGS(&pMediaSource));
    if (SUCCEEDED(hr)) {
        hr = pMediaSource->CreatePresentationDescriptor(&pPresentationDescriptor);
        if (SUCCEEDED(hr)) {
            HRESULT hrDur = pPresentationDescriptor->GetUINT64(MF_PD_DURATION, reinterpret_cast<UINT64*>(pDuration));
            if (FAILED(hrDur)) {
                // Duration unavailable — live HLS stream or network source
                *pDuration = 0;
            }
            pPresentationDescriptor->Release();
        }
        pMediaSource->Release();
    }
    // Return S_OK even when duration is 0 (live stream) — caller checks the value
    return S_OK;
}

NATIVEVIDEOPLAYER_API HRESULT GetMediaPosition(const VideoPlayerInstance* pInstance, LONGLONG* pPosition) {
    if (!pInstance || !pPosition)
        return OP_E_NOT_INITIALIZED;

    if (pInstance->pHLSPlayer)
        return pInstance->pHLSPlayer->GetPosition(pPosition);

    *pPosition = pInstance->llCurrentPosition;
    return S_OK;
}

NATIVEVIDEOPLAYER_API HRESULT SetPlaybackState(VideoPlayerInstance* pInstance, BOOL bPlaying, BOOL bStop) {
    if (!pInstance)
        return OP_E_NOT_INITIALIZED;

    if (pInstance->pHLSPlayer)
        return pInstance->pHLSPlayer->SetPlaying(bPlaying, bStop);

    HRESULT hr = S_OK;

    if (bStop && !bPlaying) {
        // Stop playback completely
        if (pInstance->llPlaybackStartTime != 0) {
            pInstance->llTotalPauseTime = 0;
            pInstance->llPauseStart = 0;
            pInstance->llPlaybackStartTime = 0;

            if (pInstance->bUseClockSync && pInstance->pPresentationClock) {
                pInstance->pPresentationClock->Stop();
            }

            if (pInstance->bAudioThreadRunning) {
                StopAudioThread(pInstance);
            }

            pInstance->bHasInitialFrame = FALSE;

            if (pInstance->pCachedSample) {
                pInstance->pCachedSample->Release();
                pInstance->pCachedSample = nullptr;
            }
        }
    } else if (bPlaying) {
        // Start or resume playback
        if (pInstance->llPlaybackStartTime == 0) {
            pInstance->llPlaybackStartTime = GetCurrentTimeMs();
        } else if (pInstance->llPauseStart != 0) {
            pInstance->llTotalPauseTime += (GetCurrentTimeMs() - pInstance->llPauseStart);
            pInstance->llPauseStart = 0;
        }

        pInstance->bHasInitialFrame = FALSE;

        // Start audio client if available
        if (pInstance->pAudioClient && pInstance->bAudioInitialized) {
            hr = pInstance->pAudioClient->Start();
            if (FAILED(hr)) {
                PrintHR("Failed to start audio client", hr);
            }
        }

        // Start audio thread if it is not already running
        // (important when the player was opened in paused state and then play() is called)
        if (pInstance->bHasAudio && pInstance->bAudioInitialized && pInstance->pSourceReaderAudio) {
            if (!pInstance->bAudioThreadRunning || pInstance->hAudioThread == nullptr) {
                hr = StartAudioThread(pInstance);
                if (FAILED(hr)) {
                    PrintHR("Failed to start audio thread on play", hr);
                }
            }
        }

        // Start or resume presentation clock from the current stored position
        if (pInstance->bUseClockSync && pInstance->pPresentationClock) {
            hr = pInstance->pPresentationClock->Start(pInstance->llCurrentPosition);
            if (FAILED(hr)) {
                PrintHR("Failed to start presentation clock", hr);
            }
        }

        if (pInstance->hAudioReadyEvent) {
            SetEvent(pInstance->hAudioReadyEvent);
        }
    } else {
        // Pause playback
        if (pInstance->llPauseStart == 0) {
            pInstance->llPauseStart = GetCurrentTimeMs();
        }

        pInstance->bHasInitialFrame = FALSE;

        if (pInstance->pAudioClient && pInstance->bAudioInitialized) {
            pInstance->pAudioClient->Stop();
        }

        if (pInstance->bUseClockSync && pInstance->pPresentationClock) {
            hr = pInstance->pPresentationClock->Pause();
            if (FAILED(hr)) {
                PrintHR("Failed to pause presentation clock", hr);
            }
        }
        // Note: the audio thread is not stopped on pause — it simply waits on sync events
    }
    return hr;
}

NATIVEVIDEOPLAYER_API HRESULT ShutdownMediaFoundation() {
    return Shutdown();
}

NATIVEVIDEOPLAYER_API void CloseMedia(VideoPlayerInstance* pInstance) {
    if (!pInstance)
        return;

    // Shut down HLS player first (before releasing D3D resources)
    if (pInstance->pHLSPlayer) {
        pInstance->pHLSPlayer->Close();
        delete pInstance->pHLSPlayer;
        pInstance->pHLSPlayer = nullptr;
    }

    StopAudioThread(pInstance);

    if (pInstance->pLockedBuffer) {
        UnlockVideoFrame(pInstance);
    }

    if (pInstance->pCachedSample) {
        pInstance->pCachedSample->Release();
        pInstance->pCachedSample = nullptr;
    }
    pInstance->bHasInitialFrame = FALSE;

    #define SAFE_RELEASE(obj) if (obj) { obj->Release(); obj = nullptr; }

    if (pInstance->pAudioClient) {
        pInstance->pAudioClient->Stop();
        SAFE_RELEASE(pInstance->pAudioClient);
    }

    if (pInstance->pPresentationClock) {
        pInstance->pPresentationClock->Stop();
        SAFE_RELEASE(pInstance->pPresentationClock);
    }

    SAFE_RELEASE(pInstance->pMediaSource);
    SAFE_RELEASE(pInstance->pRenderClient);
    SAFE_RELEASE(pInstance->pDevice);
    SAFE_RELEASE(pInstance->pAudioEndpointVolume);
    SAFE_RELEASE(pInstance->pSourceReader);
    SAFE_RELEASE(pInstance->pSourceReaderAudio);

    if (pInstance->pSourceAudioFormat) {
        CoTaskMemFree(pInstance->pSourceAudioFormat);
        pInstance->pSourceAudioFormat = nullptr;
    }

    #define SAFE_CLOSE_HANDLE(handle) if (handle) { CloseHandle(handle); handle = nullptr; }

    SAFE_CLOSE_HANDLE(pInstance->hAudioSamplesReadyEvent);
    SAFE_CLOSE_HANDLE(pInstance->hAudioReadyEvent);

    pInstance->bEOF = FALSE;
    pInstance->videoWidth = pInstance->videoHeight = 0;
    pInstance->bHasAudio = FALSE;
    pInstance->bAudioInitialized = FALSE;
    pInstance->llPlaybackStartTime = 0;
    pInstance->llTotalPauseTime = 0;
    pInstance->llPauseStart = 0;
    pInstance->llCurrentPosition = 0;
    pInstance->bSeekInProgress = FALSE;
    pInstance->playbackSpeed = 1.0f;
    pInstance->bIsNetworkSource = FALSE;
    pInstance->bIsLiveStream = FALSE;

    #undef SAFE_RELEASE
    #undef SAFE_CLOSE_HANDLE
}

NATIVEVIDEOPLAYER_API HRESULT SetAudioVolume(VideoPlayerInstance* pInstance, float volume) {
    if (pInstance && pInstance->pHLSPlayer)
        return pInstance->pHLSPlayer->SetVolume(volume);
    return SetVolume(pInstance, volume);
}

NATIVEVIDEOPLAYER_API HRESULT GetAudioVolume(const VideoPlayerInstance* pInstance, float* volume) {
    if (pInstance && pInstance->pHLSPlayer)
        return pInstance->pHLSPlayer->GetVolume(volume);
    return GetVolume(pInstance, volume);
}

NATIVEVIDEOPLAYER_API HRESULT SetPlaybackSpeed(VideoPlayerInstance* pInstance, float speed) {
    if (!pInstance)
        return OP_E_NOT_INITIALIZED;

    if (pInstance->pHLSPlayer)
        return pInstance->pHLSPlayer->SetPlaybackSpeed(speed);

    speed = std::max(0.5f, std::min(speed, 2.0f));
    pInstance->playbackSpeed = speed;

    if (pInstance->bUseClockSync && pInstance->pPresentationClock) {
        IMFRateControl* pRateControl = nullptr;
        HRESULT hr = pInstance->pPresentationClock->QueryInterface(IID_PPV_ARGS(&pRateControl));
        if (SUCCEEDED(hr)) {
            hr = pRateControl->SetRate(FALSE, speed);
            if (FAILED(hr)) {
                PrintHR("Failed to set presentation clock rate", hr);
            }
            pRateControl->Release();
        }
    }

    return S_OK;
}

NATIVEVIDEOPLAYER_API HRESULT GetPlaybackSpeed(const VideoPlayerInstance* pInstance, float* pSpeed) {
    if (!pInstance || !pSpeed)
        return OP_E_INVALID_PARAMETER;

    if (pInstance->pHLSPlayer)
        return pInstance->pHLSPlayer->GetPlaybackSpeed(pSpeed);

    *pSpeed = pInstance->playbackSpeed;
    return S_OK;
}

// ---------------------------------------------------------------------------
// GetVideoMetadata — retrieves all available metadata (issue #5: improved)
// ---------------------------------------------------------------------------
NATIVEVIDEOPLAYER_API HRESULT GetVideoMetadata(const VideoPlayerInstance* pInstance, VideoMetadata* pMetadata) {
    if (!pInstance || !pMetadata)
        return OP_E_INVALID_PARAMETER;

    // HLS path: build basic metadata from engine properties
    if (pInstance->pHLSPlayer) {
        ZeroMemory(pMetadata, sizeof(VideoMetadata));
        pInstance->pHLSPlayer->GetVideoSize(&pMetadata->width, &pMetadata->height);
        pMetadata->hasWidth = pMetadata->width > 0;
        pMetadata->hasHeight = pMetadata->height > 0;
        LONGLONG dur = 0;
        if (SUCCEEDED(pInstance->pHLSPlayer->GetDuration(&dur)) && dur > 0) {
            pMetadata->duration = dur;
            pMetadata->hasDuration = TRUE;
        }
        wcscpy_s(pMetadata->mimeType, L"application/x-mpegURL");
        pMetadata->hasMimeType = TRUE;
        return S_OK;
    }

    if (!pInstance->pSourceReader)
        return OP_E_NOT_INITIALIZED;

    ZeroMemory(pMetadata, sizeof(VideoMetadata));

    HRESULT hr = S_OK;
    IMFMediaSource* pMediaSource = nullptr;
    IMFPresentationDescriptor* pPresentationDescriptor = nullptr;

    hr = pInstance->pSourceReader->GetServiceForStream(
        MF_SOURCE_READER_MEDIASOURCE,
        GUID_NULL,
        IID_PPV_ARGS(&pMediaSource));

    if (SUCCEEDED(hr) && pMediaSource) {
        hr = pMediaSource->CreatePresentationDescriptor(&pPresentationDescriptor);

        if (SUCCEEDED(hr) && pPresentationDescriptor) {
            // Duration
            UINT64 duration = 0;
            if (SUCCEEDED(pPresentationDescriptor->GetUINT64(MF_PD_DURATION, &duration))) {
                pMetadata->duration = static_cast<LONGLONG>(duration);
                pMetadata->hasDuration = TRUE;
            }

            // ---- Title via IMFMetadataProvider (issue #5) ----
            IMFMetadataProvider* pMetaProvider = nullptr;
            hr = MFGetService(pMediaSource, MF_METADATA_PROVIDER_SERVICE,
                              IID_PPV_ARGS(&pMetaProvider));
            if (SUCCEEDED(hr) && pMetaProvider) {
                IMFMetadata* pMeta = nullptr;
                hr = pMetaProvider->GetMFMetadata(pPresentationDescriptor, 0, 0, &pMeta);
                if (SUCCEEDED(hr) && pMeta) {
                    PROPVARIANT valTitle;
                    PropVariantInit(&valTitle);
                    if (SUCCEEDED(pMeta->GetProperty(L"Title", &valTitle)) &&
                        valTitle.vt == VT_LPWSTR && valTitle.pwszVal) {
                        wcsncpy_s(pMetadata->title, valTitle.pwszVal, _TRUNCATE);
                        pMetadata->hasTitle = TRUE;
                    }
                    PropVariantClear(&valTitle);
                    pMeta->Release();
                }
                pMetaProvider->Release();
            }

            // Process each stream for video/audio metadata
            DWORD streamCount = 0;
            hr = pPresentationDescriptor->GetStreamDescriptorCount(&streamCount);

            LONGLONG totalBitrate = 0;
            bool hasBitrateInfo = false;

            if (SUCCEEDED(hr)) {
                for (DWORD i = 0; i < streamCount; i++) {
                    BOOL selected = FALSE;
                    IMFStreamDescriptor* pStreamDescriptor = nullptr;

                    if (SUCCEEDED(pPresentationDescriptor->GetStreamDescriptorByIndex(i, &selected, &pStreamDescriptor))) {
                        IMFMediaTypeHandler* pHandler = nullptr;
                        if (SUCCEEDED(pStreamDescriptor->GetMediaTypeHandler(&pHandler))) {
                            GUID majorType;
                            if (SUCCEEDED(pHandler->GetMajorType(&majorType))) {
                                if (majorType == MFMediaType_Video) {
                                    IMFMediaType* pMediaType = nullptr;
                                    if (SUCCEEDED(pHandler->GetCurrentMediaType(&pMediaType))) {
                                        // Dimensions
                                        UINT32 width = 0, height = 0;
                                        if (SUCCEEDED(MFGetAttributeSize(pMediaType, MF_MT_FRAME_SIZE, &width, &height))) {
                                            pMetadata->width = width;
                                            pMetadata->height = height;
                                            pMetadata->hasWidth = TRUE;
                                            pMetadata->hasHeight = TRUE;
                                        }

                                        // Frame rate
                                        UINT32 numerator = 0, denominator = 1;
                                        if (SUCCEEDED(MFGetAttributeRatio(pMediaType, MF_MT_FRAME_RATE, &numerator, &denominator))) {
                                            if (denominator > 0) {
                                                pMetadata->frameRate = static_cast<float>(numerator) / static_cast<float>(denominator);
                                                pMetadata->hasFrameRate = TRUE;
                                            }
                                        }

                                        // Video bitrate (issue #5)
                                        UINT32 videoBitrate = 0;
                                        if (SUCCEEDED(pMediaType->GetUINT32(MF_MT_AVG_BITRATE, &videoBitrate))) {
                                            totalBitrate += videoBitrate;
                                            hasBitrateInfo = true;
                                        }

                                        // MIME type from codec subtype (issue #5: extended mapping)
                                        GUID subtype;
                                        if (SUCCEEDED(pMediaType->GetGUID(MF_MT_SUBTYPE, &subtype))) {
                                            if (subtype == MFVideoFormat_H264) {
                                                wcscpy_s(pMetadata->mimeType, L"video/h264");
                                            } else if (subtype == MFVideoFormat_HEVC) {
                                                wcscpy_s(pMetadata->mimeType, L"video/hevc");
                                            } else if (subtype == MFVideoFormat_MPEG2) {
                                                wcscpy_s(pMetadata->mimeType, L"video/mpeg2");
                                            } else if (subtype == MFVideoFormat_WMV3) {
                                                wcscpy_s(pMetadata->mimeType, L"video/x-ms-wmv");
                                            } else if (subtype == MFVideoFormat_WMV2) {
                                                wcscpy_s(pMetadata->mimeType, L"video/x-ms-wmv");
                                            } else if (subtype == MFVideoFormat_WMV1) {
                                                wcscpy_s(pMetadata->mimeType, L"video/x-ms-wmv");
                                            } else if (subtype == MFVideoFormat_VP80) {
                                                wcscpy_s(pMetadata->mimeType, L"video/vp8");
                                            } else if (subtype == MFVideoFormat_VP90) {
                                                wcscpy_s(pMetadata->mimeType, L"video/vp9");
                                            } else if (subtype == MFVideoFormat_MJPG) {
                                                wcscpy_s(pMetadata->mimeType, L"video/x-motion-jpeg");
                                            } else if (subtype == MFVideoFormat_MP4V) {
                                                wcscpy_s(pMetadata->mimeType, L"video/mp4v-es");
                                            } else if (subtype == MFVideoFormat_MP43) {
                                                wcscpy_s(pMetadata->mimeType, L"video/x-msmpeg4v3");
                                            } else {
                                                wcscpy_s(pMetadata->mimeType, L"video/unknown");
                                            }
                                            pMetadata->hasMimeType = TRUE;
                                        }

                                        pMediaType->Release();
                                    }
                                }
                                else if (majorType == MFMediaType_Audio) {
                                    IMFMediaType* pMediaType = nullptr;
                                    if (SUCCEEDED(pHandler->GetCurrentMediaType(&pMediaType))) {
                                        UINT32 channels = 0;
                                        if (SUCCEEDED(pMediaType->GetUINT32(MF_MT_AUDIO_NUM_CHANNELS, &channels))) {
                                            pMetadata->audioChannels = channels;
                                            pMetadata->hasAudioChannels = TRUE;
                                        }

                                        UINT32 sampleRate = 0;
                                        if (SUCCEEDED(pMediaType->GetUINT32(MF_MT_AUDIO_SAMPLES_PER_SECOND, &sampleRate))) {
                                            pMetadata->audioSampleRate = sampleRate;
                                            pMetadata->hasAudioSampleRate = TRUE;
                                        }

                                        // Audio bitrate (issue #5)
                                        UINT32 audioBytesPerSec = 0;
                                        if (SUCCEEDED(pMediaType->GetUINT32(MF_MT_AUDIO_AVG_BYTES_PER_SECOND, &audioBytesPerSec))) {
                                            totalBitrate += static_cast<LONGLONG>(audioBytesPerSec) * 8;
                                            hasBitrateInfo = true;
                                        }

                                        pMediaType->Release();
                                    }
                                }
                            }
                            pHandler->Release();
                        }
                        pStreamDescriptor->Release();
                    }
                }
            }

            // Report combined bitrate if we gathered any info
            if (hasBitrateInfo) {
                pMetadata->bitrate = totalBitrate;
                pMetadata->hasBitrate = TRUE;
            }

            pPresentationDescriptor->Release();
        }
        pMediaSource->Release();
    }

    // Fallback: fill in from instance state if the media source did not provide values
    if (!pMetadata->hasWidth || !pMetadata->hasHeight) {
        if (pInstance->videoWidth > 0 && pInstance->videoHeight > 0) {
            pMetadata->width = pInstance->videoWidth;
            pMetadata->height = pInstance->videoHeight;
            pMetadata->hasWidth = TRUE;
            pMetadata->hasHeight = TRUE;
        }
    }

    if (!pMetadata->hasFrameRate) {
        UINT numerator = 0, denominator = 1;
        if (SUCCEEDED(GetVideoFrameRate(pInstance, &numerator, &denominator)) && denominator > 0) {
            pMetadata->frameRate = static_cast<float>(numerator) / static_cast<float>(denominator);
            pMetadata->hasFrameRate = TRUE;
        }
    }

    if (!pMetadata->hasDuration) {
        LONGLONG dur = 0;
        if (SUCCEEDED(GetMediaDuration(pInstance, &dur))) {
            pMetadata->duration = dur;
            pMetadata->hasDuration = TRUE;
        }
    }

    if (!pMetadata->hasAudioChannels && pInstance->bHasAudio && pInstance->pSourceAudioFormat) {
        pMetadata->audioChannels = pInstance->pSourceAudioFormat->nChannels;
        pMetadata->hasAudioChannels = TRUE;
        pMetadata->audioSampleRate = pInstance->pSourceAudioFormat->nSamplesPerSec;
        pMetadata->hasAudioSampleRate = TRUE;
    }

    return S_OK;
}

// ---------------------------------------------------------------------------
// SetOutputSize — reconfigure the source reader to produce scaled frames
// ---------------------------------------------------------------------------
NATIVEVIDEOPLAYER_API HRESULT SetOutputSize(VideoPlayerInstance* pInstance, UINT32 targetWidth, UINT32 targetHeight) {
    if (!pInstance) return OP_E_NOT_INITIALIZED;

    if (pInstance->pHLSPlayer) {
        HRESULT hr = pInstance->pHLSPlayer->SetOutputSize(targetWidth, targetHeight);
        if (SUCCEEDED(hr)) {
            pInstance->pHLSPlayer->GetVideoSize(&pInstance->videoWidth, &pInstance->videoHeight);
        }
        return hr;
    }

    if (!pInstance->pSourceReader)
        return OP_E_NOT_INITIALIZED;

    // 0,0 means "reset to native resolution"
    if (targetWidth == 0 || targetHeight == 0) {
        targetWidth  = pInstance->nativeWidth;
        targetHeight = pInstance->nativeHeight;
    }

    // Don't scale UP beyond the native resolution
    if (targetWidth > pInstance->nativeWidth || targetHeight > pInstance->nativeHeight) {
        targetWidth  = pInstance->nativeWidth;
        targetHeight = pInstance->nativeHeight;
    }

    // Preserve aspect ratio: fit inside the target bounding box
    if (pInstance->nativeWidth > 0 && pInstance->nativeHeight > 0) {
        double srcAspect = static_cast<double>(pInstance->nativeWidth) / pInstance->nativeHeight;
        double dstAspect = static_cast<double>(targetWidth) / targetHeight;
        if (srcAspect > dstAspect) {
            // Width-limited
            targetHeight = static_cast<UINT32>(targetWidth / srcAspect);
        } else {
            // Height-limited
            targetWidth = static_cast<UINT32>(targetHeight * srcAspect);
        }
    }

    // MF requires even dimensions
    targetWidth  = (targetWidth  + 1) & ~1u;
    targetHeight = (targetHeight + 1) & ~1u;

    // Skip if already at this size
    if (targetWidth == pInstance->videoWidth && targetHeight == pInstance->videoHeight)
        return S_OK;

    // Minimum size guard
    if (targetWidth < 2 || targetHeight < 2)
        return E_INVALIDARG;

    // Reconfigure the output media type with the new frame size
    IMFMediaType* pType = nullptr;
    HRESULT hr = MFCreateMediaType(&pType);
    if (FAILED(hr)) return hr;

    hr = pType->SetGUID(MF_MT_MAJOR_TYPE, MFMediaType_Video);
    if (SUCCEEDED(hr))
        hr = pType->SetGUID(MF_MT_SUBTYPE, MFVideoFormat_RGB32);
    if (SUCCEEDED(hr))
        hr = MFSetAttributeSize(pType, MF_MT_FRAME_SIZE, targetWidth, targetHeight);
    if (SUCCEEDED(hr))
        hr = pInstance->pSourceReader->SetCurrentMediaType(
            MF_SOURCE_READER_FIRST_VIDEO_STREAM, nullptr, pType);
    SafeRelease(pType);

    if (FAILED(hr))
        return hr;

    // Verify and update the actual output dimensions
    IMFMediaType* pActual = nullptr;
    hr = pInstance->pSourceReader->GetCurrentMediaType(
        MF_SOURCE_READER_FIRST_VIDEO_STREAM, &pActual);
    if (SUCCEEDED(hr)) {
        MFGetAttributeSize(pActual, MF_MT_FRAME_SIZE,
                           &pInstance->videoWidth, &pInstance->videoHeight);
        SafeRelease(pActual);
    }

    // Invalidate cached sample since dimensions changed
    if (pInstance->pCachedSample) {
        pInstance->pCachedSample->Release();
        pInstance->pCachedSample = nullptr;
    }
    pInstance->bHasInitialFrame = FALSE;

    return S_OK;
}
