// AudioManager.cpp – WASAPI audio rendering with resampling for playback speed.
// -----------------------------------------------------------------------------
//  Audio is the timing master (like AVPlayer on macOS). The audio thread feeds
//  decoded PCM to WASAPI as fast as the buffer allows — no wall-clock drift
//  correction, no sleep, no sample dropping. Video compensates via audioLatencyMs.
//
//  This eliminates the class of stutter bugs caused by drift correction
//  sleeping/dropping samples after seek, resume, or speed changes.
// -----------------------------------------------------------------------------

#include "AudioManager.h"
#include "VideoPlayerInstance.h"
#include "Utils.h"
#include "MediaFoundationManager.h"
#include <algorithm>
#include <cmath>
#include <mmreg.h>
#include <mfreadwrite.h>

// WAVE_FORMAT_EXTENSIBLE sub-format GUIDs
static const GUID kSubtypePCM =
    {0x00000001, 0x0000, 0x0010, {0x80, 0x00, 0x00, 0xAA, 0x00, 0x38, 0x9B, 0x71}};
static const GUID kSubtypeIEEEFloat =
    {0x00000003, 0x0000, 0x0010, {0x80, 0x00, 0x00, 0xAA, 0x00, 0x38, 0x9B, 0x71}};

using namespace VideoPlayerUtils;

namespace AudioManager {

// ---------------------- Helper constants ----------------------
constexpr REFERENCE_TIME kTargetBufferDuration100ns = 2'000'000; // 200 ms

// ---------------------------------------------------------------------------
static void ResolveFormatTag(const WAVEFORMATEX* fmt, WORD* outTag, WORD* outBps) {
    *outTag = fmt->wFormatTag;
    *outBps = fmt->wBitsPerSample;
    if (*outTag == WAVE_FORMAT_EXTENSIBLE && fmt->cbSize >= 22) {
        auto* ext = reinterpret_cast<const WAVEFORMATEXTENSIBLE*>(fmt);
        if (ext->SubFormat == kSubtypePCM)            *outTag = WAVE_FORMAT_PCM;
        else if (ext->SubFormat == kSubtypeIEEEFloat)  *outTag = WAVE_FORMAT_IEEE_FLOAT;
    }
}

// ---------------------------------------------------------------------------
static void ApplyVolume(BYTE* data, UINT32 frames, UINT32 blockAlign,
                        float vol, WORD formatTag, WORD bitsPerSample) {
    if (vol >= 0.999f) return;

    if (formatTag == WAVE_FORMAT_PCM && bitsPerSample == 16) {
        auto* s = reinterpret_cast<int16_t*>(data);
        size_t n = (frames * blockAlign) / sizeof(int16_t);
        for (size_t i = 0; i < n; ++i)
            s[i] = static_cast<int16_t>(s[i] * vol);
    } else if (formatTag == WAVE_FORMAT_PCM && bitsPerSample == 24) {
        size_t totalBytes = frames * blockAlign;
        for (size_t i = 0; i + 2 < totalBytes; i += 3) {
            int32_t sample = static_cast<int8_t>(data[i + 2]);
            sample = (sample << 8) | data[i + 1];
            sample = (sample << 8) | data[i];
            sample = static_cast<int32_t>(sample * vol);
            data[i]     = static_cast<BYTE>(sample & 0xFF);
            data[i + 1] = static_cast<BYTE>((sample >> 8) & 0xFF);
            data[i + 2] = static_cast<BYTE>((sample >> 16) & 0xFF);
        }
    } else if (formatTag == WAVE_FORMAT_IEEE_FLOAT && bitsPerSample == 32) {
        auto* s = reinterpret_cast<float*>(data);
        size_t n = (frames * blockAlign) / sizeof(float);
        for (size_t i = 0; i < n; ++i) s[i] *= vol;
    }
}

// ------------------------------------------------------------------------------------
//  InitWASAPI
// ------------------------------------------------------------------------------------
HRESULT InitWASAPI(VideoPlayerInstance* inst, const WAVEFORMATEX* srcFmt)
{
    if (!inst) return E_INVALIDARG;
    if (inst->pAudioClient && inst->pRenderClient) {
        inst->bAudioInitialized = TRUE;
        return S_OK;
    }

    HRESULT hr = S_OK;
    WAVEFORMATEX* deviceMixFmt = nullptr;

    IMMDeviceEnumerator* enumerator = MediaFoundation::GetDeviceEnumerator();
    if (!enumerator) return E_FAIL;

    hr = enumerator->GetDefaultAudioEndpoint(eRender, eConsole, &inst->pDevice);
    if (FAILED(hr)) goto fail;

    hr = inst->pDevice->Activate(__uuidof(IAudioClient), CLSCTX_ALL, nullptr,
                                 reinterpret_cast<void**>(&inst->pAudioClient));
    if (FAILED(hr)) goto fail;

    hr = inst->pDevice->Activate(__uuidof(IAudioEndpointVolume), CLSCTX_ALL, nullptr,
                                 reinterpret_cast<void**>(&inst->pAudioEndpointVolume));
    if (FAILED(hr)) goto fail;

    if (!srcFmt) {
        hr = inst->pAudioClient->GetMixFormat(&deviceMixFmt);
        if (FAILED(hr)) goto fail;
        srcFmt = deviceMixFmt;
    }
    inst->pSourceAudioFormat = reinterpret_cast<WAVEFORMATEX*>(
        CoTaskMemAlloc(srcFmt->cbSize + sizeof(WAVEFORMATEX)));
    if (!inst->pSourceAudioFormat) { hr = E_OUTOFMEMORY; goto fail; }
    memcpy(inst->pSourceAudioFormat, srcFmt, srcFmt->cbSize + sizeof(WAVEFORMATEX));

    if (!inst->hAudioSamplesReadyEvent) {
        inst->hAudioSamplesReadyEvent = CreateEvent(nullptr, FALSE, FALSE, nullptr);
        if (!inst->hAudioSamplesReadyEvent) {
            hr = HRESULT_FROM_WIN32(GetLastError());
            goto fail;
        }
    }

    hr = inst->pAudioClient->Initialize(AUDCLNT_SHAREMODE_SHARED,
                                        AUDCLNT_STREAMFLAGS_EVENTCALLBACK,
                                        kTargetBufferDuration100ns, 0, srcFmt, nullptr);
    if (FAILED(hr)) goto fail;

    hr = inst->pAudioClient->SetEventHandle(inst->hAudioSamplesReadyEvent);
    if (FAILED(hr)) goto fail;

    hr = inst->pAudioClient->GetService(__uuidof(IAudioRenderClient),
                                        reinterpret_cast<void**>(&inst->pRenderClient));
    if (FAILED(hr)) goto fail;

    inst->bAudioInitialized = TRUE;
    if (deviceMixFmt) CoTaskMemFree(deviceMixFmt);
    return S_OK;

fail:
    if (inst->pRenderClient)        { inst->pRenderClient->Release();        inst->pRenderClient = nullptr; }
    if (inst->pAudioClient)         { inst->pAudioClient->Release();         inst->pAudioClient = nullptr; }
    if (inst->pAudioEndpointVolume) { inst->pAudioEndpointVolume->Release(); inst->pAudioEndpointVolume = nullptr; }
    if (inst->pDevice)              { inst->pDevice->Release();              inst->pDevice = nullptr; }
    if (inst->pSourceAudioFormat)   { CoTaskMemFree(inst->pSourceAudioFormat); inst->pSourceAudioFormat = nullptr; }
    if (inst->hAudioSamplesReadyEvent) { CloseHandle(inst->hAudioSamplesReadyEvent); inst->hAudioSamplesReadyEvent = nullptr; }
    if (deviceMixFmt) CoTaskMemFree(deviceMixFmt);
    inst->bAudioInitialized = FALSE;
    return hr;
}

// ---------------------------------------------------------------------------
//  FeedSamplesToWASAPI — reads audio from MF and feeds to WASAPI render buffer.
//  Used by both AudioThreadProc (main loop) and PreFillAudioBuffer (seek).
//  Returns the number of output frames written, or -1 on EOF/error.
// ---------------------------------------------------------------------------
static int FeedOneSample(VideoPlayerInstance* inst, IMFSourceReader* audioReader,
                         UINT32 engineBufferFrames, UINT32 blockAlign, UINT32 channels,
                         WORD formatTag, WORD bitsPerSample, float speed)
{
    // How many frames can we write?
    UINT32 framesPadding = 0;
    if (FAILED(inst->pAudioClient->GetCurrentPadding(&framesPadding)))
        return -1;
    UINT32 framesFree = engineBufferFrames - framesPadding;
    if (framesFree == 0) return 0; // buffer full, try later

    // Update latency for video-side compensation
    const UINT32 sampleRate = inst->pSourceAudioFormat ? inst->pSourceAudioFormat->nSamplesPerSec : 48000;
    inst->audioLatencyMs.store(
        static_cast<double>(framesPadding) * 1000.0 / sampleRate,
        std::memory_order_relaxed);

    // Read one decoded audio sample
    IMFSample* mfSample = nullptr;
    DWORD      flags    = 0;
    LONGLONG   ts100n   = 0;
    HRESULT hr = audioReader->ReadSample(
        MF_SOURCE_READER_FIRST_AUDIO_STREAM,
        0, nullptr, &flags, &ts100n, &mfSample);
    if (FAILED(hr)) return -1;
    if (!mfSample) return 0; // decoder starved
    if (flags & MF_SOURCE_READERF_ENDOFSTREAM) {
        mfSample->Release();
        return -1;
    }

    // Update position from audio PTS (audio is the timing master)
    if (ts100n > 0) {
        inst->llCurrentPosition = ts100n;
    }

    // Lock sample buffer
    IMFMediaBuffer* mediaBuf = nullptr;
    if (FAILED(mfSample->ConvertToContiguousBuffer(&mediaBuf)) || !mediaBuf) {
        mfSample->Release();
        return 0;
    }

    BYTE*  srcData = nullptr;
    DWORD  srcSize = 0, srcMax = 0;
    if (FAILED(mediaBuf->Lock(&srcData, &srcMax, &srcSize))) {
        mediaBuf->Release();
        mfSample->Release();
        return 0;
    }

    const UINT32 srcFrames = srcSize / blockAlign;
    const bool needsResample = std::abs(speed - 1.0f) >= 0.01f;

    UINT32 totalOutputFrames = srcFrames;
    if (needsResample && speed > 0.0f)
        totalOutputFrames = static_cast<UINT32>(std::ceil(srcFrames / speed));

    UINT32 outputDone = 0;
    double fracPos = inst->resampleFracPos;

    while (outputDone < totalOutputFrames && inst->bAudioThreadRunning) {
        // Abort if seek started
        {
            EnterCriticalSection(&inst->csClockSync);
            bool seeking = inst->bSeekInProgress;
            LeaveCriticalSection(&inst->csClockSync);
            if (seeking) break;
        }

        UINT32 wantFrames = std::min(totalOutputFrames - outputDone, framesFree);
        if (wantFrames == 0) {
            // Buffer full — wait briefly for WASAPI to consume
            WaitForSingleObject(inst->hAudioSamplesReadyEvent, 5);
            if (FAILED(inst->pAudioClient->GetCurrentPadding(&framesPadding))) break;
            framesFree = engineBufferFrames - framesPadding;
            continue;
        }

        EnterCriticalSection(&inst->csAudioFeed);

        BYTE* dstData = nullptr;
        HRESULT hrBuf = inst->pRenderClient->GetBuffer(wantFrames, &dstData);
        if (FAILED(hrBuf) || !dstData) {
            LeaveCriticalSection(&inst->csAudioFeed);
            break;
        }

        if (needsResample) {
            double localFrac = fracPos + outputDone * static_cast<double>(speed);
            UINT32 actualWritten = 0;
            for (UINT32 i = 0; i < wantFrames; ++i) {
                if (localFrac >= srcFrames) {
                    memset(dstData + i * blockAlign, 0, (wantFrames - i) * blockAlign);
                    actualWritten = wantFrames;
                    break;
                }
                UINT32 idx0 = static_cast<UINT32>(localFrac);
                UINT32 idx1 = std::min(idx0 + 1, srcFrames - 1);
                float frac = static_cast<float>(localFrac - idx0);

                if (formatTag == WAVE_FORMAT_IEEE_FLOAT && bitsPerSample == 32) {
                    const float* s = reinterpret_cast<const float*>(srcData);
                    float* d = reinterpret_cast<float*>(dstData + i * blockAlign);
                    for (UINT32 ch = 0; ch < channels; ++ch)
                        d[ch] = s[idx0 * channels + ch] * (1.0f - frac)
                              + s[idx1 * channels + ch] * frac;
                } else if (formatTag == WAVE_FORMAT_PCM && bitsPerSample == 16) {
                    const int16_t* s = reinterpret_cast<const int16_t*>(srcData);
                    int16_t* d = reinterpret_cast<int16_t*>(dstData + i * blockAlign);
                    for (UINT32 ch = 0; ch < channels; ++ch)
                        d[ch] = static_cast<int16_t>(
                            s[idx0 * channels + ch] * (1.0f - frac)
                          + s[idx1 * channels + ch] * frac);
                } else {
                    memcpy(dstData + i * blockAlign, srcData + idx0 * blockAlign, blockAlign);
                }
                localFrac += speed;
                ++actualWritten;
            }
            wantFrames = actualWritten;
        } else {
            memcpy(dstData, srcData + outputDone * blockAlign, wantFrames * blockAlign);
        }

        const float vol = inst->instanceVolume.load(std::memory_order_relaxed);
        ApplyVolume(dstData, wantFrames, blockAlign, vol, formatTag, bitsPerSample);

        inst->pRenderClient->ReleaseBuffer(wantFrames, 0);
        LeaveCriticalSection(&inst->csAudioFeed);

        outputDone += wantFrames;

        if (FAILED(inst->pAudioClient->GetCurrentPadding(&framesPadding))) break;
        framesFree = engineBufferFrames - framesPadding;
    }

    // Save fractional position for next sample
    if (needsResample) {
        double endPos = fracPos + outputDone * static_cast<double>(speed);
        inst->resampleFracPos = endPos - srcFrames;
        if (inst->resampleFracPos < 0.0) inst->resampleFracPos = 0.0;
    } else {
        inst->resampleFracPos = 0.0;
    }

    mediaBuf->Unlock();
    mediaBuf->Release();
    mfSample->Release();
    return static_cast<int>(outputDone);
}

// ---------------------------------------------------------------------------
//  PreFillAudioBuffer — fills WASAPI buffer BEFORE Start() so there's no
//  gap at the beginning of playback / after seek.
// ---------------------------------------------------------------------------
HRESULT PreFillAudioBuffer(VideoPlayerInstance* inst)
{
    if (!inst || !inst->pAudioClient || !inst->pRenderClient)
        return E_INVALIDARG;

    IMFSourceReader* audioReader = inst->pSourceReaderAudio
                                 ? inst->pSourceReaderAudio
                                 : inst->pSourceReader;
    if (!audioReader) return E_FAIL;

    UINT32 engineBufferFrames = 0;
    if (FAILED(inst->pAudioClient->GetBufferSize(&engineBufferFrames)))
        return E_FAIL;

    const UINT32 blockAlign = inst->pSourceAudioFormat ? inst->pSourceAudioFormat->nBlockAlign : 4;
    const UINT32 channels   = inst->pSourceAudioFormat ? inst->pSourceAudioFormat->nChannels : 2;

    WORD formatTag = WAVE_FORMAT_PCM, bitsPerSample = 16;
    if (inst->pSourceAudioFormat)
        ResolveFormatTag(inst->pSourceAudioFormat, &formatTag, &bitsPerSample);

    float speed = inst->playbackSpeed.load(std::memory_order_relaxed);
    inst->resampleFracPos = 0.0;

    // Fill until the buffer is at least half full
    UINT32 targetFrames = engineBufferFrames / 2;
    UINT32 totalFed = 0;
    for (int attempts = 0; attempts < 20 && totalFed < targetFrames; ++attempts) {
        int fed = FeedOneSample(inst, audioReader, engineBufferFrames,
                                blockAlign, channels, formatTag, bitsPerSample, speed);
        if (fed < 0) break; // EOF or error
        if (fed == 0) continue;
        totalFed += fed;
    }

    return S_OK;
}

// ---------------------------------------------------------------------------
//  AudioThreadProc — simple feed loop, no drift correction.
//  Audio is the timing master: it feeds WASAPI as fast as the buffer allows.
//  WASAPI's hardware clock determines the actual playback rate.
//  Video compensates via audioLatencyMs.
// ---------------------------------------------------------------------------
DWORD WINAPI AudioThreadProc(LPVOID lpParam)
{
    auto* inst = static_cast<VideoPlayerInstance*>(lpParam);
    if (!inst || !inst->pAudioClient || !inst->pRenderClient)
        return 0;

    IMFSourceReader* audioReader = inst->pSourceReaderAudio
                                 ? inst->pSourceReaderAudio
                                 : inst->pSourceReader;
    if (!audioReader) return 0;

    UINT32 engineBufferFrames = 0;
    if (FAILED(inst->pAudioClient->GetBufferSize(&engineBufferFrames)))
        return 0;

    if (inst->hAudioReadyEvent)
        WaitForSingleObject(inst->hAudioReadyEvent, INFINITE);

    const UINT32 blockAlign = inst->pSourceAudioFormat ? inst->pSourceAudioFormat->nBlockAlign : 4;
    const UINT32 channels   = inst->pSourceAudioFormat ? inst->pSourceAudioFormat->nChannels : 2;

    WORD formatTag = WAVE_FORMAT_PCM, bitsPerSample = 16;
    if (inst->pSourceAudioFormat)
        ResolveFormatTag(inst->pSourceAudioFormat, &formatTag, &bitsPerSample);

    inst->resampleFracPos = 0.0;

    while (inst->bAudioThreadRunning) {
        // Wait for WASAPI to signal buffer space (or 10ms timeout)
        WaitForSingleObject(inst->hAudioSamplesReadyEvent, 10);

        // Pause / seek: spin until resumed
        {
            EnterCriticalSection(&inst->csClockSync);
            bool suspended = inst->bSeekInProgress || inst->llPauseStart != 0;
            LeaveCriticalSection(&inst->csClockSync);
            if (suspended) {
                PreciseSleepHighRes(5);
                continue;
            }
        }

        float speed = inst->playbackSpeed.load(std::memory_order_relaxed);
        int result = FeedOneSample(inst, audioReader, engineBufferFrames,
                                   blockAlign, channels, formatTag, bitsPerSample, speed);
        if (result < 0) break; // EOF or fatal error
    }

    EnterCriticalSection(&inst->csAudioFeed);
    inst->pAudioClient->Stop();
    LeaveCriticalSection(&inst->csAudioFeed);
    inst->audioLatencyMs.store(0.0, std::memory_order_relaxed);
    return 0;
}

// -------------------------------------------------------------
//  Thread management
// -------------------------------------------------------------
HRESULT StartAudioThread(VideoPlayerInstance* inst)
{
    if (!inst || !inst->bHasAudio || !inst->bAudioInitialized)
        return E_INVALIDARG;

    if (inst->hAudioThread) {
        WaitForSingleObject(inst->hAudioThread, 5000);
        CloseHandle(inst->hAudioThread);
        inst->hAudioThread = nullptr;
    }

    inst->bAudioThreadRunning = TRUE;
    inst->hAudioThread = CreateThread(nullptr, 0, AudioThreadProc, inst, 0, nullptr);
    if (!inst->hAudioThread) {
        inst->bAudioThreadRunning = FALSE;
        return HRESULT_FROM_WIN32(GetLastError());
    }

    if (inst->hAudioReadyEvent) SetEvent(inst->hAudioReadyEvent);
    return S_OK;
}

void StopAudioThread(VideoPlayerInstance* inst)
{
    if (!inst) return;

    inst->bAudioThreadRunning = FALSE;
    if (inst->hAudioReadyEvent) SetEvent(inst->hAudioReadyEvent);
    if (inst->hAudioSamplesReadyEvent) SetEvent(inst->hAudioSamplesReadyEvent);

    if (inst->hAudioThread) {
        WaitForSingleObject(inst->hAudioThread, 5000);
        CloseHandle(inst->hAudioThread);
        inst->hAudioThread = nullptr;
    }

    if (inst->pAudioClient) {
        EnterCriticalSection(&inst->csAudioFeed);
        inst->pAudioClient->Stop();
        LeaveCriticalSection(&inst->csAudioFeed);
    }
    inst->audioLatencyMs.store(0.0, std::memory_order_relaxed);
}

// -----------------------------------------
//  Volume helpers
// -----------------------------------------
HRESULT SetVolume(VideoPlayerInstance* inst, float vol)
{
    if (!inst) return E_INVALIDARG;
    inst->instanceVolume.store(std::clamp(vol, 0.0f, 1.0f), std::memory_order_relaxed);
    return S_OK;
}

HRESULT GetVolume(const VideoPlayerInstance* inst, float* out)
{
    if (!inst || !out) return E_INVALIDARG;
    *out = inst->instanceVolume.load(std::memory_order_relaxed);
    return S_OK;
}

} // namespace AudioManager
