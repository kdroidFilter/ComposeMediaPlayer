// NativeVideoPlayer.cpp
#include "NativeVideoPlayer.h"
#include "VideoPlayerInstance.h"
#include "Utils.h"
#include "MediaFoundationManager.h"
#include "AudioManager.h"
#include "HLSPlayer.h"
#include <algorithm>
#include <cstring>
#include <cstdint>
#include <memory>
#include <mfapi.h>
#include <mferror.h>
#include <string>
#include <cctype>
#include <evr.h>
#include <wrl/client.h>
#include <intrin.h>
#include <immintrin.h>

using Microsoft::WRL::ComPtr;
using namespace VideoPlayerUtils;
using namespace MediaFoundation;
using namespace AudioManager;

// ---------------------------------------------------------------------------
// Constants
// ---------------------------------------------------------------------------
static constexpr UINT   kDefaultFrameRateNum    = 30;
static constexpr UINT   kDefaultFrameRateDenom  = 1;
static constexpr double kFrameSkipThreshold     = 3.0; // frame intervals
static constexpr double kFrameAheadMinMs        = 1.0;

// ---------------------------------------------------------------------------
// Debug printing
// ---------------------------------------------------------------------------
#ifdef _DEBUG
  #define PrintHR(msg, hr) fprintf(stderr, "%s (hr=0x%08x)\n", msg, static_cast<unsigned int>(hr))
#else
  #define PrintHR(msg, hr) ((void)0)
#endif


// ---------------------------------------------------------------------------
// VideoPlayerInstance dtor — RAII teardown
// ---------------------------------------------------------------------------
VideoPlayerInstance::~VideoPlayerInstance() {
    CloseMedia(this);
}

// ---------------------------------------------------------------------------
// SIMD alpha fix (MFVideoFormat_RGB32 leaves the alpha byte undefined).
// Runtime-dispatched: AVX2 when available, otherwise scalar.
// ---------------------------------------------------------------------------
static bool DetectAvx2() {
    int info[4] = {};
    __cpuid(info, 0);
    if (info[0] < 7) return false;
    __cpuidex(info, 7, 0);
    return (info[1] & (1 << 5)) != 0; // EBX bit 5 = AVX2
}

static void ForceAlphaOpaque(BYTE* data, size_t pixelCount) {
    static const bool kHasAvx2 = DetectAvx2();
    uint32_t* px = reinterpret_cast<uint32_t*>(data);

    size_t i = 0;
    if (kHasAvx2) {
        const __m256i mask = _mm256_set1_epi32(static_cast<int>(0xFF000000u));
        for (; i + 8 <= pixelCount; i += 8) {
            __m256i v = _mm256_loadu_si256(reinterpret_cast<__m256i*>(px + i));
            v = _mm256_or_si256(v, mask);
            _mm256_storeu_si256(reinterpret_cast<__m256i*>(px + i), v);
        }
    }
    for (; i < pixelCount; ++i) px[i] |= 0xFF000000u;
}

// ---------------------------------------------------------------------------
// URL helpers
// ---------------------------------------------------------------------------
static bool IsNetworkUrl(const wchar_t* url) {
    return _wcsnicmp(url, L"http://", 7) == 0 || _wcsnicmp(url, L"https://", 8) == 0;
}

static bool IsHLSUrl(const wchar_t* url) {
    if (!url) return false;
    std::wstring lower(url);
    for (auto& ch : lower) ch = static_cast<wchar_t>(towlower(ch));
    return lower.find(L".m3u8") != std::wstring::npos;
}

// ---------------------------------------------------------------------------
// MediaType change handler — extracted to kill duplication.
// ---------------------------------------------------------------------------
static void HandleMediaTypeChanges(VideoPlayerInstance* inst, DWORD flags) {
    if (flags & MF_SOURCE_READERF_NATIVEMEDIATYPECHANGED) {
        ComPtr<IMFMediaType> newType;
        if (SUCCEEDED(MFCreateMediaType(newType.GetAddressOf()))) {
            newType->SetGUID(MF_MT_MAJOR_TYPE, MFMediaType_Video);
            newType->SetGUID(MF_MT_SUBTYPE, MFVideoFormat_RGB32);
            inst->pSourceReader->SetCurrentMediaType(
                MF_SOURCE_READER_FIRST_VIDEO_STREAM, nullptr, newType.Get());
        }
    }
    if (flags & MF_SOURCE_READERF_CURRENTMEDIATYPECHANGED) {
        ComPtr<IMFMediaType> current;
        if (SUCCEEDED(inst->pSourceReader->GetCurrentMediaType(
                MF_SOURCE_READER_FIRST_VIDEO_STREAM, current.GetAddressOf()))) {
            UINT32 newW = 0, newH = 0;
            MFGetAttributeSize(current.Get(), MF_MT_FRAME_SIZE, &newW, &newH);
            if (newW > 0 && newH > 0) {
                inst->videoWidth  = newW;
                inst->videoHeight = newH;
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Copy a decoded frame into a caller-provided buffer.
// ---------------------------------------------------------------------------
static void CopyPlane(const BYTE* src, LONG srcPitch,
                      BYTE* dst, DWORD dstPitch,
                      DWORD rowBytes, UINT32 height) {
    if (static_cast<LONG>(dstPitch) == srcPitch && static_cast<LONG>(rowBytes) == srcPitch) {
        memcpy(dst, src, static_cast<size_t>(rowBytes) * height);
        return;
    }
    const DWORD copyBytes = (std::min)(rowBytes, dstPitch);
    for (UINT32 y = 0; y < height; ++y) {
        memcpy(dst, src, copyBytes);
        src += srcPitch;
        dst += dstPitch;
    }
}

// ---------------------------------------------------------------------------
// HLS fallback for network URLs
// ---------------------------------------------------------------------------
static HRESULT OpenMediaHLS(VideoPlayerInstance* pInstance, const wchar_t* url, BOOL startPlayback) {
    auto* hls = new (std::nothrow) HLSPlayer();
    if (!hls) return E_OUTOFMEMORY;

    HRESULT hr = hls->Initialize(GetD3DDevice(), GetDXGIDeviceManager());
    if (SUCCEEDED(hr)) hr = hls->Open(url);
    if (FAILED(hr)) {
        hls->Release(); // refcount 1 → 0 : dtor calls Close()
        return hr;
    }

    pInstance->pHLSPlayer       = hls;
    pInstance->bIsNetworkSource = true;

    hls->GetVideoSize(&pInstance->videoWidth, &pInstance->videoHeight);
    pInstance->nativeWidth  = pInstance->videoWidth;
    pInstance->nativeHeight = pInstance->videoHeight;

    LONGLONG duration = 0;
    hls->GetDuration(&duration);
    pInstance->bIsLiveStream = (duration == 0);
    pInstance->bHasAudio = true;

    if (startPlayback) {
        hls->SetPlaying(TRUE);
        pInstance->llPlaybackStartTime.store(GetCurrentTimeMs(), std::memory_order_relaxed);
        pInstance->llTotalPauseTime.store(0, std::memory_order_relaxed);
        pInstance->llPauseStart.store(0, std::memory_order_relaxed);
    }
    return S_OK;
}

// ---------------------------------------------------------------------------
// Audio format configuration
// ---------------------------------------------------------------------------
static HRESULT ConfigureAudioType(IMFMediaType* pType, UINT32 channels, UINT32 sampleRate) {
    if (channels == 0)   channels = 2;
    if (sampleRate == 0) sampleRate = 48000;

    const UINT32 bitsPerSample  = 16;
    const UINT32 blockAlign     = channels * (bitsPerSample / 8);
    const UINT32 avgBytesPerSec = sampleRate * blockAlign;

    pType->SetGUID(MF_MT_MAJOR_TYPE, MFMediaType_Audio);
    pType->SetGUID(MF_MT_SUBTYPE, MFAudioFormat_PCM);
    pType->SetUINT32(MF_MT_AUDIO_NUM_CHANNELS, channels);
    pType->SetUINT32(MF_MT_AUDIO_SAMPLES_PER_SECOND, sampleRate);
    pType->SetUINT32(MF_MT_AUDIO_BLOCK_ALIGNMENT, blockAlign);
    pType->SetUINT32(MF_MT_AUDIO_AVG_BYTES_PER_SECOND, avgBytesPerSec);
    pType->SetUINT32(MF_MT_AUDIO_BITS_PER_SAMPLE, bitsPerSample);
    return S_OK;
}

static void QueryNativeAudioParams(IMFSourceReader* reader, UINT32* channels, UINT32* sampleRate) {
    *channels = 0;
    *sampleRate = 0;
    if (!reader) return;

    ComPtr<IMFMediaType> nativeType;
    if (SUCCEEDED(reader->GetNativeMediaType(
            MF_SOURCE_READER_FIRST_AUDIO_STREAM, 0, nativeType.GetAddressOf()))) {
        nativeType->GetUINT32(MF_MT_AUDIO_NUM_CHANNELS, channels);
        nativeType->GetUINT32(MF_MT_AUDIO_SAMPLES_PER_SECOND, sampleRate);
    }
}

// ---------------------------------------------------------------------------
// Compute the presentation reference time used to decide whether a decoded
// frame should be displayed now, skipped, or cached for later.
// ---------------------------------------------------------------------------
static double ComputeReferenceMs(const VideoPlayerInstance* inst) {
    if (inst->bHasAudio) {
        const double audioFedMs = inst->llCurrentPosition.load(std::memory_order_relaxed) / 10000.0;
        const double latencyMs  = inst->audioLatencyMs.load(std::memory_order_relaxed);
        return audioFedMs - latencyMs;
    }
    const LONGLONG now = static_cast<LONGLONG>(GetCurrentTimeMs());
    const LONGLONG start = static_cast<LONGLONG>(inst->llPlaybackStartTime.load(std::memory_order_relaxed));
    const LONGLONG pauseTotal = static_cast<LONGLONG>(inst->llTotalPauseTime.load(std::memory_order_relaxed));
    return static_cast<double>(now - start - pauseTotal) * inst->playbackSpeed.load(std::memory_order_relaxed);
}

// ---------------------------------------------------------------------------
// Read the next video frame. Returns a sample ready to be displayed or
// nullptr when the frame is not yet due (caller should try again later).
// No blocking sleeps: early frames are cached to avoid stalling the JNI
// render thread.
// ---------------------------------------------------------------------------
static HRESULT AcquireNextSample(VideoPlayerInstance* inst, IMFSample** ppOut) {
    *ppOut = nullptr;

    const bool isPaused = (inst->llPauseStart.load(std::memory_order_relaxed) != 0);
    ComPtr<IMFSample> sample;
    LONGLONG ts = 0;

    UINT frNum = kDefaultFrameRateNum, frDenom = kDefaultFrameRateDenom;
    GetVideoFrameRate(inst, &frNum, &frDenom);
    if (frNum == 0) { frNum = kDefaultFrameRateNum; frDenom = kDefaultFrameRateDenom; }
    const double frameIntervalMs = 1000.0 * frDenom / frNum;
    const double lateThresholdMs = -frameIntervalMs * kFrameSkipThreshold;

    // 1) Cached-sample path: a previously-read frame that was "too early".
    if (inst->pCachedSample) {
        if (isPaused) {
            inst->pCachedSample.CopyTo(sample.GetAddressOf());
            ts = inst->llCachedTimestamp;
        } else {
            const double frameTimeMs = inst->llCachedTimestamp / 10000.0;
            const double refMs = ComputeReferenceMs(inst);
            const ULONGLONG nowMs = GetCurrentTimeMs();
            const ULONGLONG heldMs = (inst->llCachedInsertedAtMs != 0)
                ? (nowMs - inst->llCachedInsertedAtMs) : 0;
            // Deliver if due, OR if the sample has been sitting too long —
            // avoids an indefinite freeze when the audio clock stalls or
            // drifts (which would otherwise leave refMs permanently behind).
            if (frameTimeMs - refMs > kFrameAheadMinMs && heldMs < 300) {
                return S_OK; // still too early, wait
            }
            sample = std::move(inst->pCachedSample);
            inst->pCachedSample.Reset();
            inst->llCachedInsertedAtMs = 0;
            ts = inst->llCachedTimestamp;
        }
    }

    // 2) Fresh-read path: drop anything late, return the first in-window
    //    frame (or cache the first too-early one). We never hand a late
    //    sample to the caller — stale frames are pure waste, the picture
    //    should jump to "now", not replay what was missed.
    if (!sample) {
        constexpr int kMaxReadIterations = 64;
        constexpr ULONGLONG kMaxReadBudgetMs = 25;
        const ULONGLONG budgetStart = GetCurrentTimeMs();

        for (int iter = 0; iter < kMaxReadIterations; ++iter) {
            DWORD streamIndex = 0, flags = 0;
            LONGLONG sampleTs = 0;
            ComPtr<IMFSample> s;
            HRESULT hr = inst->pSourceReader->ReadSample(
                MF_SOURCE_READER_FIRST_VIDEO_STREAM, 0,
                &streamIndex, &flags, &sampleTs, s.GetAddressOf());
            if (FAILED(hr)) return hr;

            if (flags & MF_SOURCE_READERF_ENDOFSTREAM) {
                inst->bEOF.store(true);
                return S_FALSE;
            }

            HandleMediaTypeChanges(inst, flags);

            if (!s) {
                // Decoder starved — yield back to the caller.
                return S_OK;
            }

            // Paused path: cache the first frame for initial display.
            if (isPaused) {
                if (!inst->bHasInitialFrame) {
                    s.CopyTo(inst->pCachedSample.ReleaseAndGetAddressOf());
                    inst->llCachedTimestamp = sampleTs;
                    inst->llCachedInsertedAtMs = GetCurrentTimeMs();
                    inst->bHasInitialFrame = true;
                }
                sample = std::move(s);
                ts = sampleTs;
                break;
            }

            inst->bHasInitialFrame = true;
            if (!inst->bHasAudio) {
                inst->llCurrentPosition.store(sampleTs, std::memory_order_relaxed);
            }

            // No timestamp → hand it over unconditionally.
            if (sampleTs <= 0) {
                sample = std::move(s);
                ts = sampleTs;
                break;
            }

            const double frameTimeMs = sampleTs / 10000.0;
            const double refMs = ComputeReferenceMs(inst);
            const double diffMs = frameTimeMs - refMs;

            if (diffMs < lateThresholdMs) {
                // Stale — discard and keep reading. Do not cache, do not
                // deliver: we want to display what's happening NOW, not
                // replay pre-seek keyframes or frames skipped during a
                // UI stall.
                if (iter >= 3 && GetCurrentTimeMs() - budgetStart > kMaxReadBudgetMs) {
                    // Budget exhausted; yield so the caller can do something
                    // else. Next call resumes draining from here.
                    return S_OK;
                }
                continue;
            }

            if (diffMs > frameIntervalMs) {
                // Too early — cache so the next call on the normal render
                // cadence can deliver it.
                s.CopyTo(inst->pCachedSample.ReleaseAndGetAddressOf());
                inst->llCachedTimestamp = sampleTs;
                inst->llCachedInsertedAtMs = GetCurrentTimeMs();
                return S_OK;
            }

            // In display window — deliver.
            sample = std::move(s);
            ts = sampleTs;
            break;
        }
    }

    if (!sample) return S_OK;
    sample.CopyTo(ppOut);
    return S_OK;
}

// ====================================================================
// Exported API
// ====================================================================

NATIVEVIDEOPLAYER_API int GetNativeVersion() { return NATIVE_VIDEO_PLAYER_VERSION; }

NATIVEVIDEOPLAYER_API HRESULT InitMediaFoundation() { return Initialize(); }

NATIVEVIDEOPLAYER_API HRESULT CreateVideoPlayerInstance(VideoPlayerInstance** ppInstance) {
    if (!ppInstance) return E_INVALIDARG;

    if (!IsInitialized()) {
        HRESULT hr = Initialize();
        if (FAILED(hr)) return hr;
    }

    auto inst = std::unique_ptr<VideoPlayerInstance>(new (std::nothrow) VideoPlayerInstance());
    if (!inst) return E_OUTOFMEMORY;

    inst->bUseClockSync = true;
    IncrementInstanceCount();
    *ppInstance = inst.release();
    return S_OK;
}

NATIVEVIDEOPLAYER_API void DestroyVideoPlayerInstance(VideoPlayerInstance* pInstance) {
    if (!pInstance) return;
    delete pInstance; // dtor calls CloseMedia
    DecrementInstanceCount();
}

NATIVEVIDEOPLAYER_API HRESULT OpenMedia(VideoPlayerInstance* pInstance, const wchar_t* url, BOOL startPlayback) {
    if (!pInstance || !url) return OP_E_INVALID_PARAMETER;
    if (!IsInitialized()) return OP_E_NOT_INITIALIZED;

    CloseMedia(pInstance);
    pInstance->bEOF.store(false);
    pInstance->videoWidth = pInstance->videoHeight = 0;
    pInstance->bHasAudio = false;
    pInstance->bHasInitialFrame = false;
    pInstance->pCachedSample.Reset();

    const bool isNetwork = IsNetworkUrl(url);
    pInstance->bIsNetworkSource = isNetwork;
    pInstance->bIsLiveStream = false;

    if (isNetwork && IsHLSUrl(url))
        return OpenMediaHLS(pInstance, url, startPlayback);

    // ---- Configure and open source reader ----
    ComPtr<IMFAttributes> attrs;
    HRESULT hr = MFCreateAttributes(attrs.GetAddressOf(), 6);
    if (FAILED(hr)) return hr;

    attrs->SetUINT32(MF_READWRITE_ENABLE_HARDWARE_TRANSFORMS, TRUE);
    attrs->SetUINT32(MF_SOURCE_READER_DISABLE_DXVA, FALSE);
    attrs->SetUnknown(MF_SOURCE_READER_D3D_MANAGER, GetDXGIDeviceManager());
    attrs->SetUINT32(MF_SOURCE_READER_ENABLE_ADVANCED_VIDEO_PROCESSING, TRUE);
    if (isNetwork) attrs->SetUINT32(MF_LOW_LATENCY, TRUE);

    hr = MFCreateSourceReaderFromURL(url, attrs.Get(), pInstance->pSourceReader.ReleaseAndGetAddressOf());
    if (FAILED(hr)) {
        if (isNetwork && hr == static_cast<HRESULT>(0xC00D36C4))
            return OpenMediaHLS(pInstance, url, startPlayback);
        return hr;
    }

    // ---- Video stream: RGB32 ----
    hr = pInstance->pSourceReader->SetStreamSelection(MF_SOURCE_READER_ALL_STREAMS, FALSE);
    if (SUCCEEDED(hr))
        hr = pInstance->pSourceReader->SetStreamSelection(MF_SOURCE_READER_FIRST_VIDEO_STREAM, TRUE);
    if (FAILED(hr)) return hr;

    {
        ComPtr<IMFMediaType> type;
        hr = MFCreateMediaType(type.GetAddressOf());
        if (SUCCEEDED(hr)) {
            type->SetGUID(MF_MT_MAJOR_TYPE, MFMediaType_Video);
            type->SetGUID(MF_MT_SUBTYPE, MFVideoFormat_RGB32);
            hr = pInstance->pSourceReader->SetCurrentMediaType(
                MF_SOURCE_READER_FIRST_VIDEO_STREAM, nullptr, type.Get());
        }
        if (FAILED(hr)) return hr;
    }

    {
        ComPtr<IMFMediaType> current;
        if (SUCCEEDED(pInstance->pSourceReader->GetCurrentMediaType(
                MF_SOURCE_READER_FIRST_VIDEO_STREAM, current.GetAddressOf()))) {
            MFGetAttributeSize(current.Get(), MF_MT_FRAME_SIZE,
                               &pInstance->videoWidth, &pInstance->videoHeight);
            pInstance->nativeWidth  = pInstance->videoWidth;
            pInstance->nativeHeight = pInstance->videoHeight;
        }
    }

    // ---- Audio stream (best effort) ----
    if (SUCCEEDED(pInstance->pSourceReader->SetStreamSelection(
            MF_SOURCE_READER_FIRST_AUDIO_STREAM, TRUE))) {

        UINT32 nativeCh = 0, nativeSr = 0;
        QueryNativeAudioParams(pInstance->pSourceReader.Get(), &nativeCh, &nativeSr);

        auto tryAudioFormat = [&](UINT32 ch, UINT32 sr) -> bool {
            ComPtr<IMFMediaType> wanted;
            if (FAILED(MFCreateMediaType(wanted.GetAddressOf()))) return false;
            ConfigureAudioType(wanted.Get(), ch, sr);
            if (FAILED(pInstance->pSourceReader->SetCurrentMediaType(
                    MF_SOURCE_READER_FIRST_AUDIO_STREAM, nullptr, wanted.Get()))) return false;

            ComPtr<IMFMediaType> actual;
            if (FAILED(pInstance->pSourceReader->GetCurrentMediaType(
                    MF_SOURCE_READER_FIRST_AUDIO_STREAM, actual.GetAddressOf())) || !actual)
                return false;

            WAVEFORMATEX* pWfx = nullptr;
            UINT32 size = 0;
            if (FAILED(MFCreateWaveFormatExFromMFMediaType(actual.Get(), &pWfx, &size)) || !pWfx)
                return false;

            HRESULT hrInit = InitWASAPI(pInstance, pWfx);
            if (FAILED(hrInit)) {
                PrintHR("InitWASAPI failed", hrInit);
                CoTaskMemFree(pWfx);
                return false;
            }
            if (pInstance->pSourceAudioFormat) CoTaskMemFree(pInstance->pSourceAudioFormat);
            pInstance->pSourceAudioFormat = pWfx;
            pInstance->bHasAudio = true;
            return true;
        };

        if (!tryAudioFormat(nativeCh, nativeSr)) {
            if (nativeCh != 2 || nativeSr != 48000) tryAudioFormat(2, 48000);
        }

        // Dedicated audio SourceReader so the audio thread is never blocked
        // by the video decoding path (ReadSample serializes within a reader).
        if (pInstance->bHasAudio) {
            ComPtr<IMFAttributes> audioAttrs;
            if (SUCCEEDED(MFCreateAttributes(audioAttrs.GetAddressOf(), 2))) {
                if (isNetwork) audioAttrs->SetUINT32(MF_LOW_LATENCY, TRUE);
                HRESULT hrA = MFCreateSourceReaderFromURL(
                    url, audioAttrs.Get(), pInstance->pSourceReaderAudio.ReleaseAndGetAddressOf());
                if (SUCCEEDED(hrA) && pInstance->pSourceReaderAudio) {
                    pInstance->pSourceReaderAudio->SetStreamSelection(MF_SOURCE_READER_ALL_STREAMS, FALSE);
                    pInstance->pSourceReaderAudio->SetStreamSelection(MF_SOURCE_READER_FIRST_AUDIO_STREAM, TRUE);

                    const UINT32 usedCh = pInstance->pSourceAudioFormat->nChannels;
                    const UINT32 usedSr = pInstance->pSourceAudioFormat->nSamplesPerSec;
                    ComPtr<IMFMediaType> wanted;
                    if (SUCCEEDED(MFCreateMediaType(wanted.GetAddressOf()))) {
                        ConfigureAudioType(wanted.Get(), usedCh, usedSr);
                        pInstance->pSourceReaderAudio->SetCurrentMediaType(
                            MF_SOURCE_READER_FIRST_AUDIO_STREAM, nullptr, wanted.Get());
                    }
                } else {
                    PrintHR("Failed to create audio source reader", hrA);
                }
            }
        }
    }

    // ---- Presentation clock ----
    if (pInstance->bUseClockSync) {
        if (SUCCEEDED(pInstance->pSourceReader->GetServiceForStream(
                MF_SOURCE_READER_MEDIASOURCE, GUID_NULL,
                IID_PPV_ARGS(pInstance->pMediaSource.ReleaseAndGetAddressOf())))) {

            if (SUCCEEDED(MFCreatePresentationClock(pInstance->pPresentationClock.ReleaseAndGetAddressOf()))) {
                ComPtr<IMFPresentationTimeSource> timeSource;
                if (SUCCEEDED(MFCreateSystemTimeSource(timeSource.GetAddressOf()))) {
                    pInstance->pPresentationClock->SetTimeSource(timeSource.Get());

                    ComPtr<IMFRateControl> rateControl;
                    if (SUCCEEDED(pInstance->pPresentationClock.As(&rateControl)))
                        rateControl->SetRate(FALSE, 1.0f);

                    if (startPlayback) pInstance->pPresentationClock->Start(0);
                    else               pInstance->pPresentationClock->Pause();
                }
            }
        }
    }

    // ---- Timing init + audio thread start ----
    if (startPlayback) {
        pInstance->llPlaybackStartTime.store(GetCurrentTimeMs(), std::memory_order_relaxed);
        pInstance->llTotalPauseTime.store(0, std::memory_order_relaxed);
        pInstance->llPauseStart.store(0, std::memory_order_relaxed);

        if (pInstance->bHasAudio && pInstance->bAudioInitialized) {
            PreFillAudioBuffer(pInstance);
            if (pInstance->pSourceReaderAudio) StartAudioThread(pInstance);
        }
    } else if (pInstance->bHasAudio && pInstance->bAudioInitialized && pInstance->pSourceReaderAudio) {
        // Start the thread but leave it suspended until SetPlaybackState(TRUE).
        StartAudioThread(pInstance);
        SignalPause(pInstance);
    }

    return S_OK;
}

// ---------------------------------------------------------------------------
NATIVEVIDEOPLAYER_API HRESULT ReadVideoFrame(VideoPlayerInstance* pInstance, BYTE** pData, DWORD* pDataSize) {
    if (!pInstance || !pData || !pDataSize) return OP_E_NOT_INITIALIZED;

    if (pInstance->pHLSPlayer)
        return pInstance->pHLSPlayer->ReadFrame(pData, pDataSize);

    if (!pInstance->pSourceReader) return OP_E_NOT_INITIALIZED;
    if (pInstance->pLockedBuffer) UnlockVideoFrame(pInstance);

    if (pInstance->bEOF.load()) { *pData = nullptr; *pDataSize = 0; return S_FALSE; }

    ComPtr<IMFSample> sample;
    HRESULT hr = AcquireNextSample(pInstance, sample.GetAddressOf());
    if (hr == S_FALSE) { *pData = nullptr; *pDataSize = 0; return S_FALSE; }
    if (FAILED(hr)) return hr;
    if (!sample)    { *pData = nullptr; *pDataSize = 0; return S_OK; }

    ComPtr<IMFMediaBuffer> buffer;
    DWORD bufferCount = 0;
    if (SUCCEEDED(sample->GetBufferCount(&bufferCount)) && bufferCount == 1) {
        hr = sample->GetBufferByIndex(0, buffer.GetAddressOf());
    } else {
        hr = sample->ConvertToContiguousBuffer(buffer.GetAddressOf());
    }
    if (FAILED(hr)) { PrintHR("GetBuffer failed", hr); return hr; }

    BYTE* bytes = nullptr;
    DWORD maxSz = 0, curSz = 0;
    hr = buffer->Lock(&bytes, &maxSz, &curSz);
    if (FAILED(hr)) { PrintHR("Lock failed", hr); return hr; }

    ForceAlphaOpaque(bytes, curSz / 4);

    pInstance->pLockedBuffer   = buffer;
    pInstance->pLockedBytes    = bytes;
    pInstance->lockedMaxSize   = maxSz;
    pInstance->lockedCurrSize  = curSz;
    *pData = bytes;
    *pDataSize = curSz;
    return S_OK;
}

NATIVEVIDEOPLAYER_API HRESULT UnlockVideoFrame(VideoPlayerInstance* pInstance) {
    if (!pInstance) return E_INVALIDARG;
    if (pInstance->pHLSPlayer) { pInstance->pHLSPlayer->UnlockFrame(); return S_OK; }

    if (pInstance->pLockedBuffer) {
        pInstance->pLockedBuffer->Unlock();
        pInstance->pLockedBuffer.Reset();
    }
    pInstance->pLockedBytes = nullptr;
    pInstance->lockedMaxSize = pInstance->lockedCurrSize = 0;
    return S_OK;
}

// ---------------------------------------------------------------------------
NATIVEVIDEOPLAYER_API HRESULT ReadVideoFrameInto(VideoPlayerInstance* pInstance,
                                                  BYTE* pDst, DWORD dstRowBytes, DWORD dstCapacity,
                                                  LONGLONG* pTimestamp) {
    if (!pInstance || !pDst || dstRowBytes == 0 || dstCapacity == 0)
        return OP_E_INVALID_PARAMETER;
    if (!pInstance->pSourceReader) return OP_E_NOT_INITIALIZED;
    if (pInstance->pLockedBuffer) UnlockVideoFrame(pInstance);

    if (pInstance->bEOF.load()) {
        if (pTimestamp) *pTimestamp = pInstance->llCurrentPosition.load(std::memory_order_relaxed);
        return S_FALSE;
    }

    ComPtr<IMFSample> sample;
    HRESULT hr = AcquireNextSample(pInstance, sample.GetAddressOf());
    if (hr == S_FALSE) {
        if (pTimestamp) *pTimestamp = pInstance->llCurrentPosition.load(std::memory_order_relaxed);
        return S_FALSE;
    }
    if (FAILED(hr)) return hr;
    if (!sample) {
        if (pTimestamp) *pTimestamp = pInstance->llCurrentPosition.load(std::memory_order_relaxed);
        return S_OK;
    }

    if (pTimestamp) *pTimestamp = pInstance->llCurrentPosition.load(std::memory_order_relaxed);

    const UINT32 width  = pInstance->videoWidth;
    const UINT32 height = pInstance->videoHeight;
    if (width == 0 || height == 0) return S_FALSE;

    const DWORD requiredDst = dstRowBytes * height;
    if (dstCapacity < requiredDst) return OP_E_INVALID_PARAMETER;

    ComPtr<IMFMediaBuffer> buffer;
    hr = sample->ConvertToContiguousBuffer(buffer.GetAddressOf());
    if (FAILED(hr)) return hr;

    const DWORD srcRowBytes = width * 4;
    bool copied = false;

    // Preferred path: IMF2DBuffer2.
    {
        ComPtr<IMF2DBuffer2> b2;
        if (SUCCEEDED(buffer.As(&b2))) {
            BYTE* scan0 = nullptr;
            LONG  pitch = 0;
            BYTE* bufStart = nullptr;
            DWORD cbLen = 0;
            if (SUCCEEDED(b2->Lock2DSize(MF2DBuffer_LockFlags_Read, &scan0, &pitch, &bufStart, &cbLen))) {
                CopyPlane(scan0, pitch, pDst, dstRowBytes, srcRowBytes, height);
                b2->Unlock2D();
                copied = true;
            }
        }
    }

    // Fallback: IMF2DBuffer.
    if (!copied) {
        ComPtr<IMF2DBuffer> b2;
        if (SUCCEEDED(buffer.As(&b2))) {
            BYTE* scan0 = nullptr;
            LONG  pitch = 0;
            if (SUCCEEDED(b2->Lock2D(&scan0, &pitch))) {
                CopyPlane(scan0, pitch, pDst, dstRowBytes, srcRowBytes, height);
                b2->Unlock2D();
                copied = true;
            }
        }
    }

    // Final fallback: linear Lock.
    if (!copied) {
        BYTE* bytes = nullptr;
        DWORD maxSz = 0, curSz = 0;
        if (SUCCEEDED(buffer->Lock(&bytes, &maxSz, &curSz))) {
            if (curSz >= srcRowBytes * height)
                MFCopyImage(pDst, dstRowBytes, bytes, srcRowBytes, srcRowBytes, height);
            buffer->Unlock();
        }
    }

    ForceAlphaOpaque(pDst, (dstRowBytes * height) / 4);
    return S_OK;
}

NATIVEVIDEOPLAYER_API BOOL IsEOF(const VideoPlayerInstance* pInstance) {
    if (!pInstance) return FALSE;
    if (pInstance->pHLSPlayer) return pInstance->pHLSPlayer->IsEOF();
    return pInstance->bEOF.load();
}

NATIVEVIDEOPLAYER_API void GetVideoSize(const VideoPlayerInstance* pInstance, UINT32* pWidth, UINT32* pHeight) {
    if (!pInstance) return;
    if (pInstance->pHLSPlayer) { pInstance->pHLSPlayer->GetVideoSize(pWidth, pHeight); return; }
    if (pWidth)  *pWidth  = pInstance->videoWidth;
    if (pHeight) *pHeight = pInstance->videoHeight;
}

NATIVEVIDEOPLAYER_API HRESULT GetVideoFrameRate(const VideoPlayerInstance* pInstance, UINT* pNum, UINT* pDenom) {
    if (!pInstance || !pNum || !pDenom) return OP_E_NOT_INITIALIZED;

    if (pInstance->pHLSPlayer) { *pNum = 30; *pDenom = 1; return S_OK; }
    if (!pInstance->pSourceReader) return OP_E_NOT_INITIALIZED;

    ComPtr<IMFMediaType> type;
    HRESULT hr = pInstance->pSourceReader->GetCurrentMediaType(
        MF_SOURCE_READER_FIRST_VIDEO_STREAM, type.GetAddressOf());
    if (SUCCEEDED(hr))
        hr = MFGetAttributeRatio(type.Get(), MF_MT_FRAME_RATE, pNum, pDenom);
    return hr;
}

// ---------------------------------------------------------------------------
// SeekMedia — robust seek with full reader / WASAPI synchronization.
//
// Contract with the caller: no other thread may call ReadVideoFrame (video
// reader) while SeekMedia is running. The Kotlin side cancels its producer
// coroutine before invoking this. The audio reader is protected internally
// by csAudioFeed.
//
// Flow:
//   1. Snapshot wasPlaying under csClockSync (consistent with timing fields).
//   2. Raise bSeekInProgress so the audio thread discards any sample it is
//      currently decoding and stops feeding WASAPI.
//   3. Stop the presentation clock.
//   4. Seek the video reader.
//   5. Under csAudioFeed: Stop WASAPI, seek audio reader, Reset WASAPI buffer.
//      Holding csAudioFeed for all three ops guarantees the audio thread
//      (which also takes this lock around ReadSample and GetBuffer) cannot
//      interleave a stale sample into the freshly reset buffer.
//   6. Reset timing / audio state atomically.
//   7. If wasPlaying: pre-fill WASAPI, Start WASAPI (under lock), Start clock.
//      If paused: leave WASAPI stopped, clock stopped, player quiet.
//   8. Clear bSeekInProgress (release barrier) and SignalResume if playing.
// ---------------------------------------------------------------------------
NATIVEVIDEOPLAYER_API HRESULT SeekMedia(VideoPlayerInstance* pInstance, LONGLONG llPosition) {
    if (!pInstance) return OP_E_NOT_INITIALIZED;
    if (pInstance->pHLSPlayer) return pInstance->pHLSPlayer->Seek(llPosition);
    if (!pInstance->pSourceReader) return OP_E_NOT_INITIALIZED;

    if (llPosition < 0) llPosition = 0;

    // 1. Snapshot current playing state.
    bool wasPlaying;
    {
        ScopedLock lock(pInstance->csClockSync);
        wasPlaying = (pInstance->llPauseStart.load(std::memory_order_relaxed) == 0)
                  && (pInstance->llPlaybackStartTime.load(std::memory_order_relaxed) != 0);
    }

    // 2. Announce seek. Audio thread will:
    //    - break out of its feed loop on next inner-loop iteration,
    //    - drop any post-ReadSample sample as stale.
    pInstance->bSeekInProgress.store(true, std::memory_order_release);

    // Defensive cleanups.
    if (pInstance->pLockedBuffer) UnlockVideoFrame(pInstance);
    pInstance->pCachedSample.Reset();
    pInstance->bHasInitialFrame = false;

    // 3. Stop presentation clock.
    if (pInstance->bUseClockSync && pInstance->pPresentationClock)
        pInstance->pPresentationClock->Stop();

    // 4. Seek video reader (no concurrent ReadVideoFrame thanks to Kotlin contract).
    PROPVARIANT var;
    PropVariantInit(&var);
    var.vt = VT_I8;
    var.hVal.QuadPart = llPosition;
    HRESULT hr = pInstance->pSourceReader->SetCurrentPosition(GUID_NULL, var);
    if (FAILED(hr)) {
        pInstance->bSeekInProgress.store(false, std::memory_order_release);
        PropVariantClear(&var);
        if (wasPlaying) SignalResume(pInstance);
        return hr;
    }

    // Catch-up is now handled inside AcquireNextSample's internal loop; no
    // separate fast-forward is needed here.

    // 5. Atomic audio-side seek: Stop + SetCurrentPosition + Reset under one lock.
    if (pInstance->bHasAudio) {
        ScopedLock lock(pInstance->csAudioFeed);
        if (pInstance->pAudioClient) pInstance->pAudioClient->Stop();
        if (pInstance->pSourceReaderAudio)
            pInstance->pSourceReaderAudio->SetCurrentPosition(GUID_NULL, var);
        if (pInstance->pAudioClient) pInstance->pAudioClient->Reset();
    }
    PropVariantClear(&var);

    // 6. Reset state.
    pInstance->bEOF.store(false, std::memory_order_relaxed);
    pInstance->resampleFracPos = 0.0;
    pInstance->audioLatencyMs.store(0.0, std::memory_order_relaxed);
    pInstance->llCurrentPosition.store(llPosition, std::memory_order_relaxed);

    {
        ScopedLock lock(pInstance->csClockSync);
        const float speed = pInstance->playbackSpeed.load(std::memory_order_relaxed);
        const ULONGLONG now = GetCurrentTimeMs();
        const double posMs = llPosition / 10000.0;
        const double adjMs = posMs / static_cast<double>(speed);
        const ULONGLONG startT = (static_cast<ULONGLONG>(adjMs) >= now)
            ? 0ULL : (now - static_cast<ULONGLONG>(adjMs));
        pInstance->llPlaybackStartTime.store(startT, std::memory_order_relaxed);
        pInstance->llTotalPauseTime.store(0, std::memory_order_relaxed);
        pInstance->llPauseStart.store(wasPlaying ? 0 : now, std::memory_order_relaxed);
    }

    // 7. Resume or stay paused.
    if (wasPlaying) {
        if (pInstance->bHasAudio && pInstance->bAudioInitialized) {
            // Pre-fill runs under csAudioFeed (recursive CS, safe to re-enter).
            PreFillAudioBuffer(pInstance);
        }

        if (pInstance->bHasAudio && pInstance->pAudioClient) {
            ScopedLock lock(pInstance->csAudioFeed);
            pInstance->pAudioClient->Start();
        }
        if (pInstance->bUseClockSync && pInstance->pPresentationClock)
            pInstance->pPresentationClock->Start(llPosition);
    }

    // 8. Release barrier.
    pInstance->bSeekInProgress.store(false, std::memory_order_release);
    if (wasPlaying) SignalResume(pInstance);
    return S_OK;
}

NATIVEVIDEOPLAYER_API HRESULT GetMediaDuration(const VideoPlayerInstance* pInstance, LONGLONG* pDuration) {
    if (!pInstance || !pDuration) return OP_E_NOT_INITIALIZED;
    if (pInstance->pHLSPlayer) return pInstance->pHLSPlayer->GetDuration(pDuration);
    if (!pInstance->pSourceReader) return OP_E_NOT_INITIALIZED;

    *pDuration = 0;

    ComPtr<IMFMediaSource> source;
    HRESULT hr = pInstance->pSourceReader->GetServiceForStream(
        MF_SOURCE_READER_MEDIASOURCE, GUID_NULL, IID_PPV_ARGS(source.GetAddressOf()));
    if (SUCCEEDED(hr)) {
        ComPtr<IMFPresentationDescriptor> pd;
        if (SUCCEEDED(source->CreatePresentationDescriptor(pd.GetAddressOf()))) {
            if (FAILED(pd->GetUINT64(MF_PD_DURATION, reinterpret_cast<UINT64*>(pDuration))))
                *pDuration = 0;
        }
    }
    return S_OK;
}

NATIVEVIDEOPLAYER_API HRESULT GetMediaPosition(const VideoPlayerInstance* pInstance, LONGLONG* pPosition) {
    if (!pInstance || !pPosition) return OP_E_NOT_INITIALIZED;
    if (pInstance->pHLSPlayer) return pInstance->pHLSPlayer->GetPosition(pPosition);
    *pPosition = pInstance->llCurrentPosition.load(std::memory_order_relaxed);
    return S_OK;
}

NATIVEVIDEOPLAYER_API HRESULT SetPlaybackState(VideoPlayerInstance* pInstance, BOOL bPlaying, BOOL bStop) {
    if (!pInstance) return OP_E_NOT_INITIALIZED;
    if (pInstance->pHLSPlayer) return pInstance->pHLSPlayer->SetPlaying(bPlaying, bStop);

    HRESULT hr = S_OK;

    if (bStop && !bPlaying) {
        if (pInstance->llPlaybackStartTime.load(std::memory_order_relaxed) != 0) {
            pInstance->llTotalPauseTime.store(0, std::memory_order_relaxed);
            pInstance->llPauseStart.store(0, std::memory_order_relaxed);
            pInstance->llPlaybackStartTime.store(0, std::memory_order_relaxed);

            if (pInstance->bUseClockSync && pInstance->pPresentationClock)
                pInstance->pPresentationClock->Stop();

            if (pInstance->bAudioThreadRunning.load()) StopAudioThread(pInstance);

            pInstance->bHasInitialFrame = false;
            pInstance->pCachedSample.Reset();
        }
    } else if (bPlaying) {
        if (pInstance->llPlaybackStartTime.load(std::memory_order_relaxed) == 0) {
            pInstance->llPlaybackStartTime.store(GetCurrentTimeMs(), std::memory_order_relaxed);
        } else {
            const ULONGLONG ps = pInstance->llPauseStart.load(std::memory_order_relaxed);
            if (ps != 0) {
                pInstance->llTotalPauseTime.fetch_add(GetCurrentTimeMs() - ps, std::memory_order_relaxed);
                pInstance->llPauseStart.store(0, std::memory_order_relaxed);
            }
        }

        pInstance->bHasInitialFrame = false;

        if (pInstance->pAudioClient && pInstance->bAudioInitialized) {
            ScopedLock lock(pInstance->csAudioFeed);
            hr = pInstance->pAudioClient->Start();
            if (FAILED(hr)) PrintHR("Failed to start audio client", hr);
        }

        if (pInstance->bHasAudio && pInstance->bAudioInitialized && pInstance->pSourceReaderAudio) {
            if (!pInstance->bAudioThreadRunning.load() || !pInstance->hAudioThread) {
                HRESULT hrT = StartAudioThread(pInstance);
                if (FAILED(hrT)) PrintHR("Failed to start audio thread", hrT);
            }
        }

        if (pInstance->bUseClockSync && pInstance->pPresentationClock) {
            hr = pInstance->pPresentationClock->Start(
                pInstance->llCurrentPosition.load(std::memory_order_relaxed));
            if (FAILED(hr)) PrintHR("Failed to start presentation clock", hr);
        }

        SignalResume(pInstance);
    } else {
        if (pInstance->llPauseStart.load(std::memory_order_relaxed) == 0)
            pInstance->llPauseStart.store(GetCurrentTimeMs(), std::memory_order_relaxed);

        pInstance->bHasInitialFrame = false;

        if (pInstance->pAudioClient && pInstance->bAudioInitialized) {
            ScopedLock lock(pInstance->csAudioFeed);
            pInstance->pAudioClient->Stop();
        }
        if (pInstance->bUseClockSync && pInstance->pPresentationClock)
            pInstance->pPresentationClock->Pause();

        SignalPause(pInstance);
    }
    return hr;
}

NATIVEVIDEOPLAYER_API HRESULT ShutdownMediaFoundation() { return Shutdown(); }

NATIVEVIDEOPLAYER_API void CloseMedia(VideoPlayerInstance* pInstance) {
    if (!pInstance) return;

    if (pInstance->pHLSPlayer) {
        pInstance->pHLSPlayer->Release(); // dtor handles Close()
        pInstance->pHLSPlayer = nullptr;
    }

    StopAudioThread(pInstance);

    if (pInstance->pLockedBuffer) UnlockVideoFrame(pInstance);
    pInstance->pCachedSample.Reset();
    pInstance->bHasInitialFrame = false;

    if (pInstance->pAudioClient) {
        pInstance->pAudioClient->Stop();
        pInstance->pAudioClient.Reset();
    }

    if (pInstance->pPresentationClock) {
        pInstance->pPresentationClock->Stop();
        pInstance->pPresentationClock.Reset();
    }

    pInstance->pMediaSource.Reset();
    pInstance->pRenderClient.Reset();
    pInstance->pDevice.Reset();
    pInstance->pAudioEndpointVolume.Reset();
    pInstance->pSourceReader.Reset();
    pInstance->pSourceReaderAudio.Reset();

    if (pInstance->pSourceAudioFormat) {
        CoTaskMemFree(pInstance->pSourceAudioFormat);
        pInstance->pSourceAudioFormat = nullptr;
    }

    pInstance->hAudioSamplesReadyEvent.Reset();
    pInstance->hAudioResumeEvent.Reset();

    pInstance->bEOF.store(false);
    pInstance->videoWidth = pInstance->videoHeight = 0;
    pInstance->bHasAudio = false;
    pInstance->bAudioInitialized = false;
    pInstance->llPlaybackStartTime.store(0, std::memory_order_relaxed);
    pInstance->llTotalPauseTime.store(0, std::memory_order_relaxed);
    pInstance->llPauseStart.store(0, std::memory_order_relaxed);
    pInstance->llCurrentPosition.store(0, std::memory_order_relaxed);
    pInstance->bSeekInProgress.store(false, std::memory_order_relaxed);
    pInstance->playbackSpeed.store(1.0f, std::memory_order_relaxed);
    pInstance->resampleFracPos = 0.0;
    pInstance->audioLatencyMs.store(0.0, std::memory_order_relaxed);
    pInstance->bIsNetworkSource = false;
    pInstance->bIsLiveStream = false;
}

NATIVEVIDEOPLAYER_API HRESULT SetAudioVolume(VideoPlayerInstance* pInstance, float volume) {
    if (pInstance && pInstance->pHLSPlayer) return pInstance->pHLSPlayer->SetVolume(volume);
    return SetVolume(pInstance, volume);
}

NATIVEVIDEOPLAYER_API HRESULT GetAudioVolume(const VideoPlayerInstance* pInstance, float* volume) {
    if (pInstance && pInstance->pHLSPlayer) return pInstance->pHLSPlayer->GetVolume(volume);
    return GetVolume(pInstance, volume);
}

NATIVEVIDEOPLAYER_API HRESULT SetPlaybackSpeed(VideoPlayerInstance* pInstance, float speed) {
    if (!pInstance) return OP_E_NOT_INITIALIZED;
    if (pInstance->pHLSPlayer) return pInstance->pHLSPlayer->SetPlaybackSpeed(speed);

    speed = std::clamp(speed, 0.5f, 2.0f);

    if (pInstance->bUseClockSync
        && pInstance->llPlaybackStartTime.load(std::memory_order_relaxed) != 0) {
        const float oldSpeed = pInstance->playbackSpeed.load(std::memory_order_relaxed);
        ScopedLock lock(pInstance->csClockSync);
        const ULONGLONG now = GetCurrentTimeMs();
        const ULONGLONG startT = pInstance->llPlaybackStartTime.load(std::memory_order_relaxed);
        const ULONGLONG pauseT = pInstance->llTotalPauseTime.load(std::memory_order_relaxed);
        const LONGLONG elapsedMs = static_cast<LONGLONG>(now - startT - pauseT);
        const double currentPosMs = elapsedMs * static_cast<double>(oldSpeed);
        pInstance->llPlaybackStartTime.store(
            now - pauseT - static_cast<ULONGLONG>(currentPosMs / speed),
            std::memory_order_relaxed);
    }

    pInstance->playbackSpeed.store(speed, std::memory_order_relaxed);
    pInstance->resampleFracPos = 0.0;

    if (pInstance->bUseClockSync && pInstance->pPresentationClock) {
        ComPtr<IMFRateControl> rateControl;
        if (SUCCEEDED(pInstance->pPresentationClock.As(&rateControl)))
            rateControl->SetRate(FALSE, speed);
    }
    return S_OK;
}

NATIVEVIDEOPLAYER_API HRESULT GetPlaybackSpeed(const VideoPlayerInstance* pInstance, float* pSpeed) {
    if (!pInstance || !pSpeed) return OP_E_INVALID_PARAMETER;
    if (pInstance->pHLSPlayer) return pInstance->pHLSPlayer->GetPlaybackSpeed(pSpeed);
    *pSpeed = pInstance->playbackSpeed.load(std::memory_order_relaxed);
    return S_OK;
}

// ---------------------------------------------------------------------------
// Metadata
// ---------------------------------------------------------------------------
static const wchar_t* MimeTypeForSubtype(const GUID& s) {
    if (s == MFVideoFormat_H264)  return L"video/h264";
    if (s == MFVideoFormat_HEVC)  return L"video/hevc";
    if (s == MFVideoFormat_MPEG2) return L"video/mpeg2";
    if (s == MFVideoFormat_WMV3 || s == MFVideoFormat_WMV2 || s == MFVideoFormat_WMV1)
        return L"video/x-ms-wmv";
    if (s == MFVideoFormat_VP80)  return L"video/vp8";
    if (s == MFVideoFormat_VP90)  return L"video/vp9";
    if (s == MFVideoFormat_MJPG)  return L"video/x-motion-jpeg";
    if (s == MFVideoFormat_MP4V)  return L"video/mp4v-es";
    if (s == MFVideoFormat_MP43)  return L"video/x-msmpeg4v3";
    return L"video/unknown";
}

NATIVEVIDEOPLAYER_API HRESULT GetVideoMetadata(const VideoPlayerInstance* pInstance, VideoMetadata* pMetadata) {
    if (!pInstance || !pMetadata) return OP_E_INVALID_PARAMETER;

    if (pInstance->pHLSPlayer) {
        ZeroMemory(pMetadata, sizeof(VideoMetadata));
        pInstance->pHLSPlayer->GetVideoSize(&pMetadata->width, &pMetadata->height);
        pMetadata->hasWidth  = pMetadata->width  > 0;
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

    if (!pInstance->pSourceReader) return OP_E_NOT_INITIALIZED;

    ZeroMemory(pMetadata, sizeof(VideoMetadata));

    ComPtr<IMFMediaSource> source;
    HRESULT hr = pInstance->pSourceReader->GetServiceForStream(
        MF_SOURCE_READER_MEDIASOURCE, GUID_NULL, IID_PPV_ARGS(source.GetAddressOf()));

    if (SUCCEEDED(hr) && source) {
        ComPtr<IMFPresentationDescriptor> pd;
        if (SUCCEEDED(source->CreatePresentationDescriptor(pd.GetAddressOf()))) {
            UINT64 duration = 0;
            if (SUCCEEDED(pd->GetUINT64(MF_PD_DURATION, &duration))) {
                pMetadata->duration = static_cast<LONGLONG>(duration);
                pMetadata->hasDuration = TRUE;
            }

            // Title
            ComPtr<IMFMetadataProvider> metaProvider;
            if (SUCCEEDED(MFGetService(source.Get(), MF_METADATA_PROVIDER_SERVICE,
                                        IID_PPV_ARGS(metaProvider.GetAddressOf())))) {
                ComPtr<IMFMetadata> meta;
                if (SUCCEEDED(metaProvider->GetMFMetadata(pd.Get(), 0, 0, meta.GetAddressOf())) && meta) {
                    PROPVARIANT valTitle;
                    PropVariantInit(&valTitle);
                    if (SUCCEEDED(meta->GetProperty(L"Title", &valTitle))
                        && valTitle.vt == VT_LPWSTR && valTitle.pwszVal) {
                        wcsncpy_s(pMetadata->title, valTitle.pwszVal, _TRUNCATE);
                        pMetadata->hasTitle = TRUE;
                    }
                    PropVariantClear(&valTitle);
                }
            }

            // Streams
            DWORD streamCount = 0;
            pd->GetStreamDescriptorCount(&streamCount);
            LONGLONG totalBitrate = 0;
            bool hasBitrate = false;

            for (DWORD i = 0; i < streamCount; ++i) {
                BOOL selected = FALSE;
                ComPtr<IMFStreamDescriptor> sd;
                if (FAILED(pd->GetStreamDescriptorByIndex(i, &selected, sd.GetAddressOf()))) continue;

                ComPtr<IMFMediaTypeHandler> handler;
                if (FAILED(sd->GetMediaTypeHandler(handler.GetAddressOf()))) continue;

                GUID major{};
                if (FAILED(handler->GetMajorType(&major))) continue;

                ComPtr<IMFMediaType> mt;
                if (FAILED(handler->GetCurrentMediaType(mt.GetAddressOf()))) continue;

                if (major == MFMediaType_Video) {
                    UINT32 w = 0, h = 0;
                    if (SUCCEEDED(MFGetAttributeSize(mt.Get(), MF_MT_FRAME_SIZE, &w, &h))) {
                        pMetadata->width = w; pMetadata->height = h;
                        pMetadata->hasWidth = TRUE; pMetadata->hasHeight = TRUE;
                    }
                    UINT32 num = 0, den = 1;
                    if (SUCCEEDED(MFGetAttributeRatio(mt.Get(), MF_MT_FRAME_RATE, &num, &den)) && den > 0) {
                        pMetadata->frameRate = static_cast<float>(num) / den;
                        pMetadata->hasFrameRate = TRUE;
                    }
                    UINT32 vb = 0;
                    if (SUCCEEDED(mt->GetUINT32(MF_MT_AVG_BITRATE, &vb))) {
                        totalBitrate += vb;
                        hasBitrate = true;
                    }
                    GUID sub{};
                    if (SUCCEEDED(mt->GetGUID(MF_MT_SUBTYPE, &sub))) {
                        wcscpy_s(pMetadata->mimeType, MimeTypeForSubtype(sub));
                        pMetadata->hasMimeType = TRUE;
                    }
                } else if (major == MFMediaType_Audio) {
                    UINT32 ch = 0;
                    if (SUCCEEDED(mt->GetUINT32(MF_MT_AUDIO_NUM_CHANNELS, &ch))) {
                        pMetadata->audioChannels = ch;
                        pMetadata->hasAudioChannels = TRUE;
                    }
                    UINT32 sr = 0;
                    if (SUCCEEDED(mt->GetUINT32(MF_MT_AUDIO_SAMPLES_PER_SECOND, &sr))) {
                        pMetadata->audioSampleRate = sr;
                        pMetadata->hasAudioSampleRate = TRUE;
                    }
                    UINT32 abps = 0;
                    if (SUCCEEDED(mt->GetUINT32(MF_MT_AUDIO_AVG_BYTES_PER_SECOND, &abps))) {
                        totalBitrate += static_cast<LONGLONG>(abps) * 8;
                        hasBitrate = true;
                    }
                }
            }

            if (hasBitrate) {
                pMetadata->bitrate = totalBitrate;
                pMetadata->hasBitrate = TRUE;
            }
        }
    }

    // Fallbacks
    if (!pMetadata->hasWidth || !pMetadata->hasHeight) {
        if (pInstance->videoWidth > 0 && pInstance->videoHeight > 0) {
            pMetadata->width = pInstance->videoWidth;
            pMetadata->height = pInstance->videoHeight;
            pMetadata->hasWidth = TRUE; pMetadata->hasHeight = TRUE;
        }
    }
    if (!pMetadata->hasFrameRate) {
        UINT num = 0, den = 1;
        if (SUCCEEDED(GetVideoFrameRate(pInstance, &num, &den)) && den > 0) {
            pMetadata->frameRate = static_cast<float>(num) / den;
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
NATIVEVIDEOPLAYER_API HRESULT SetOutputSize(VideoPlayerInstance* pInstance, UINT32 targetWidth, UINT32 targetHeight) {
    if (!pInstance) return OP_E_NOT_INITIALIZED;

    if (pInstance->pHLSPlayer) {
        HRESULT hr = pInstance->pHLSPlayer->SetOutputSize(targetWidth, targetHeight);
        if (SUCCEEDED(hr))
            pInstance->pHLSPlayer->GetVideoSize(&pInstance->videoWidth, &pInstance->videoHeight);
        return hr;
    }
    if (!pInstance->pSourceReader) return OP_E_NOT_INITIALIZED;

    if (targetWidth == 0 || targetHeight == 0) {
        targetWidth  = pInstance->nativeWidth;
        targetHeight = pInstance->nativeHeight;
    }
    if (targetWidth > pInstance->nativeWidth || targetHeight > pInstance->nativeHeight) {
        targetWidth  = pInstance->nativeWidth;
        targetHeight = pInstance->nativeHeight;
    }

    if (pInstance->nativeWidth > 0 && pInstance->nativeHeight > 0) {
        const double srcAspect = static_cast<double>(pInstance->nativeWidth) / pInstance->nativeHeight;
        const double dstAspect = static_cast<double>(targetWidth) / targetHeight;
        if (srcAspect > dstAspect) targetHeight = static_cast<UINT32>(targetWidth / srcAspect);
        else                       targetWidth  = static_cast<UINT32>(targetHeight * srcAspect);
    }

    targetWidth  = (targetWidth  + 1) & ~1u;
    targetHeight = (targetHeight + 1) & ~1u;

    if (targetWidth == pInstance->videoWidth && targetHeight == pInstance->videoHeight) return S_OK;
    if (targetWidth < 2 || targetHeight < 2) return E_INVALIDARG;

    ComPtr<IMFMediaType> type;
    HRESULT hr = MFCreateMediaType(type.GetAddressOf());
    if (FAILED(hr)) return hr;

    type->SetGUID(MF_MT_MAJOR_TYPE, MFMediaType_Video);
    type->SetGUID(MF_MT_SUBTYPE, MFVideoFormat_RGB32);
    MFSetAttributeSize(type.Get(), MF_MT_FRAME_SIZE, targetWidth, targetHeight);
    hr = pInstance->pSourceReader->SetCurrentMediaType(
        MF_SOURCE_READER_FIRST_VIDEO_STREAM, nullptr, type.Get());
    if (FAILED(hr)) return hr;

    ComPtr<IMFMediaType> actual;
    if (SUCCEEDED(pInstance->pSourceReader->GetCurrentMediaType(
            MF_SOURCE_READER_FIRST_VIDEO_STREAM, actual.GetAddressOf()))) {
        MFGetAttributeSize(actual.Get(), MF_MT_FRAME_SIZE,
                           &pInstance->videoWidth, &pInstance->videoHeight);
    }

    pInstance->pCachedSample.Reset();
    pInstance->bHasInitialFrame = false;
    return S_OK;
}
