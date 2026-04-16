// AudioManager.cpp — WASAPI audio rendering with linear interpolation for
// playback-speed changes.
//
// Audio is the timing master (like AVPlayer on macOS). The audio thread
// feeds decoded PCM to WASAPI as fast as the buffer allows; no wall-clock
// drift correction. Video compensates via audioLatencyMs.
//
// Suspension strategy: while paused or seeking, the thread waits on a
// manual-reset event (hAudioResumeEvent) — no busy loop, no CPU burn.
// MMCSS registration ("Pro Audio") boosts thread priority and reduces
// glitches under load.

#include "AudioManager.h"
#include "VideoPlayerInstance.h"
#include "Utils.h"
#include "MediaFoundationManager.h"
#include <algorithm>
#include <cmath>
#include <mmreg.h>
#include <mfreadwrite.h>
#include <avrt.h>
#include <wrl/client.h>

using Microsoft::WRL::ComPtr;

// WAVE_FORMAT_EXTENSIBLE sub-format GUIDs
static const GUID kSubtypePCM =
    {0x00000001, 0x0000, 0x0010, {0x80, 0x00, 0x00, 0xAA, 0x00, 0x38, 0x9B, 0x71}};
static const GUID kSubtypeIEEEFloat =
    {0x00000003, 0x0000, 0x0010, {0x80, 0x00, 0x00, 0xAA, 0x00, 0x38, 0x9B, 0x71}};

namespace AudioManager {
namespace {

constexpr REFERENCE_TIME kTargetBufferDuration100ns = 2'000'000; // 200 ms

void ResolveFormatTag(const WAVEFORMATEX* fmt, WORD* outTag, WORD* outBps) {
    *outTag = fmt->wFormatTag;
    *outBps = fmt->wBitsPerSample;
    if (*outTag == WAVE_FORMAT_EXTENSIBLE && fmt->cbSize >= 22) {
        auto* ext = reinterpret_cast<const WAVEFORMATEXTENSIBLE*>(fmt);
        if (ext->SubFormat == kSubtypePCM)           *outTag = WAVE_FORMAT_PCM;
        else if (ext->SubFormat == kSubtypeIEEEFloat) *outTag = WAVE_FORMAT_IEEE_FLOAT;
    }
}

void ApplyVolume(BYTE* data, UINT32 frames, UINT32 blockAlign,
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

// RAII helper for MMCSS Pro Audio registration.
class MmcssRegistration {
public:
    MmcssRegistration() {
        DWORD taskIndex = 0;
        handle_ = AvSetMmThreadCharacteristicsW(L"Pro Audio", &taskIndex);
    }
    ~MmcssRegistration() {
        if (handle_) AvRevertMmThreadCharacteristics(handle_);
    }
    MmcssRegistration(const MmcssRegistration&) = delete;
    MmcssRegistration& operator=(const MmcssRegistration&) = delete;
private:
    HANDLE handle_ = nullptr;
};

// Feeds a single MF audio sample into the WASAPI render buffer. Returns the
// number of output frames written, or -1 on EOF/fatal error.
int FeedOneSample(VideoPlayerInstance* inst, IMFSourceReader* audioReader,
                  UINT32 engineBufferFrames, UINT32 blockAlign, UINT32 channels,
                  WORD formatTag, WORD bitsPerSample, float speed)
{
    UINT32 framesPadding = 0;
    if (FAILED(inst->pAudioClient->GetCurrentPadding(&framesPadding))) return -1;
    UINT32 framesFree = engineBufferFrames - framesPadding;
    if (framesFree == 0) return 0;

    const UINT32 sampleRate = inst->pSourceAudioFormat
        ? inst->pSourceAudioFormat->nSamplesPerSec : 48000;
    inst->audioLatencyMs.store(
        static_cast<double>(framesPadding) * 1000.0 / sampleRate,
        std::memory_order_relaxed);

    ComPtr<IMFSample> mfSample;
    DWORD    flags  = 0;
    LONGLONG ts100n = 0;
    {
        // Hold csAudioFeed during ReadSample so SeekMedia's SetCurrentPosition
        // on the same reader never interleaves with this call.
        VideoPlayerUtils::ScopedLock lock(inst->csAudioFeed);
        HRESULT hr = audioReader->ReadSample(
            MF_SOURCE_READER_FIRST_AUDIO_STREAM,
            0, nullptr, &flags, &ts100n, mfSample.GetAddressOf());
        if (FAILED(hr)) return -1;
    }
    if (!mfSample) return 0;
    if (flags & MF_SOURCE_READERF_ENDOFSTREAM) return -1;

    // If a seek started while ReadSample was executing, the returned sample
    // may be from the old position — drop it.
    if (inst->bSeekInProgress.load(std::memory_order_acquire)) return 0;

    if (ts100n > 0) {
        inst->llCurrentPosition.store(ts100n, std::memory_order_relaxed);
    }

    ComPtr<IMFMediaBuffer> mediaBuf;
    if (FAILED(mfSample->ConvertToContiguousBuffer(mediaBuf.GetAddressOf()))) return 0;

    BYTE*  srcData = nullptr;
    DWORD  srcSize = 0, srcMax = 0;
    if (FAILED(mediaBuf->Lock(&srcData, &srcMax, &srcSize))) return 0;

    const UINT32 srcFrames = srcSize / blockAlign;
    const bool needsResample = std::abs(speed - 1.0f) >= 0.01f;

    UINT32 totalOutputFrames = srcFrames;
    if (needsResample && speed > 0.0f)
        totalOutputFrames = static_cast<UINT32>(std::ceil(srcFrames / speed));

    UINT32 outputDone = 0;
    const double fracPos = inst->resampleFracPos;

    while (outputDone < totalOutputFrames && inst->bAudioThreadRunning.load()) {
        if (inst->bSeekInProgress.load(std::memory_order_acquire)) break;

        UINT32 wantFrames = (std::min)(totalOutputFrames - outputDone, framesFree);
        if (wantFrames == 0) {
            WaitForSingleObject(inst->hAudioSamplesReadyEvent.Get(), 5);
            if (FAILED(inst->pAudioClient->GetCurrentPadding(&framesPadding))) break;
            framesFree = engineBufferFrames - framesPadding;
            continue;
        }

        VideoPlayerUtils::ScopedLock lock(inst->csAudioFeed);

        BYTE* dstData = nullptr;
        if (FAILED(inst->pRenderClient->GetBuffer(wantFrames, &dstData)) || !dstData)
            break;

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
                UINT32 idx1 = (std::min)(idx0 + 1, srcFrames - 1);
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

        outputDone += wantFrames;

        if (FAILED(inst->pAudioClient->GetCurrentPadding(&framesPadding))) break;
        framesFree = engineBufferFrames - framesPadding;
    }

    if (needsResample) {
        double endPos = fracPos + outputDone * static_cast<double>(speed);
        inst->resampleFracPos = endPos - srcFrames;
        if (inst->resampleFracPos < 0.0) inst->resampleFracPos = 0.0;
    } else {
        inst->resampleFracPos = 0.0;
    }

    mediaBuf->Unlock();
    return static_cast<int>(outputDone);
}

DWORD WINAPI AudioThreadProc(LPVOID lpParam) {
    auto* inst = static_cast<VideoPlayerInstance*>(lpParam);
    if (!inst || !inst->pAudioClient || !inst->pRenderClient) return 0;

    MmcssRegistration mmcss; // boost priority while this thread lives

    IMFSourceReader* audioReader = inst->pSourceReaderAudio
        ? inst->pSourceReaderAudio.Get()
        : inst->pSourceReader.Get();
    if (!audioReader) return 0;

    UINT32 engineBufferFrames = 0;
    if (FAILED(inst->pAudioClient->GetBufferSize(&engineBufferFrames))) return 0;

    // Wait for the resume event before feeding (handles opened-in-paused case).
    WaitForSingleObject(inst->hAudioResumeEvent.Get(), INFINITE);

    const UINT32 blockAlign = inst->pSourceAudioFormat
        ? inst->pSourceAudioFormat->nBlockAlign : 4;
    const UINT32 channels = inst->pSourceAudioFormat
        ? inst->pSourceAudioFormat->nChannels : 2;

    WORD formatTag = WAVE_FORMAT_PCM, bitsPerSample = 16;
    if (inst->pSourceAudioFormat)
        ResolveFormatTag(inst->pSourceAudioFormat, &formatTag, &bitsPerSample);

    inst->resampleFracPos = 0.0;

    while (inst->bAudioThreadRunning.load()) {
        // Block efficiently while paused/seeking — no CPU burn.
        WaitForSingleObject(inst->hAudioResumeEvent.Get(), INFINITE);
        if (!inst->bAudioThreadRunning.load()) break;

        // Wait for the audio engine to signal buffer availability.
        WaitForSingleObject(inst->hAudioSamplesReadyEvent.Get(), 10);

        if (inst->bSeekInProgress.load(std::memory_order_acquire)) continue;

        const float speed = inst->playbackSpeed.load(std::memory_order_relaxed);
        int result = FeedOneSample(inst, audioReader, engineBufferFrames,
                                   blockAlign, channels, formatTag, bitsPerSample, speed);
        if (result < 0) break; // EOF or fatal error
    }

    {
        VideoPlayerUtils::ScopedLock lock(inst->csAudioFeed);
        inst->pAudioClient->Stop();
    }
    inst->audioLatencyMs.store(0.0, std::memory_order_relaxed);
    return 0;
}

} // namespace

HRESULT InitWASAPI(VideoPlayerInstance* inst, const WAVEFORMATEX* srcFmt) {
    if (!inst) return E_INVALIDARG;
    if (inst->pAudioClient && inst->pRenderClient) {
        inst->bAudioInitialized = true;
        return S_OK;
    }

    IMMDeviceEnumerator* enumerator = MediaFoundation::GetDeviceEnumerator();
    if (!enumerator) return E_FAIL;

    HRESULT hr = enumerator->GetDefaultAudioEndpoint(
        eRender, eConsole, inst->pDevice.ReleaseAndGetAddressOf());
    if (FAILED(hr)) return hr;

    hr = inst->pDevice->Activate(__uuidof(IAudioClient), CLSCTX_ALL, nullptr,
        reinterpret_cast<void**>(inst->pAudioClient.ReleaseAndGetAddressOf()));
    if (FAILED(hr)) goto cleanup;

    hr = inst->pDevice->Activate(__uuidof(IAudioEndpointVolume), CLSCTX_ALL, nullptr,
        reinterpret_cast<void**>(inst->pAudioEndpointVolume.ReleaseAndGetAddressOf()));
    if (FAILED(hr)) goto cleanup;

    {
        WAVEFORMATEX* mixFmt = nullptr;
        const WAVEFORMATEX* useFmt = srcFmt;
        if (!useFmt) {
            hr = inst->pAudioClient->GetMixFormat(&mixFmt);
            if (FAILED(hr)) goto cleanup;
            useFmt = mixFmt;
        }

        size_t totalSize = sizeof(WAVEFORMATEX) + useFmt->cbSize;
        inst->pSourceAudioFormat = static_cast<WAVEFORMATEX*>(CoTaskMemAlloc(totalSize));
        if (!inst->pSourceAudioFormat) {
            if (mixFmt) CoTaskMemFree(mixFmt);
            hr = E_OUTOFMEMORY;
            goto cleanup;
        }
        memcpy(inst->pSourceAudioFormat, useFmt, totalSize);
        if (mixFmt) CoTaskMemFree(mixFmt);
    }

    if (!inst->hAudioSamplesReadyEvent) {
        inst->hAudioSamplesReadyEvent.Reset(CreateEventW(nullptr, FALSE, FALSE, nullptr));
        if (!inst->hAudioSamplesReadyEvent) {
            hr = HRESULT_FROM_WIN32(GetLastError());
            goto cleanup;
        }
    }

    hr = inst->pAudioClient->Initialize(AUDCLNT_SHAREMODE_SHARED,
                                        AUDCLNT_STREAMFLAGS_EVENTCALLBACK,
                                        kTargetBufferDuration100ns, 0,
                                        inst->pSourceAudioFormat, nullptr);
    if (FAILED(hr)) goto cleanup;

    hr = inst->pAudioClient->SetEventHandle(inst->hAudioSamplesReadyEvent.Get());
    if (FAILED(hr)) goto cleanup;

    hr = inst->pAudioClient->GetService(__uuidof(IAudioRenderClient),
        reinterpret_cast<void**>(inst->pRenderClient.ReleaseAndGetAddressOf()));
    if (FAILED(hr)) goto cleanup;

    inst->bAudioInitialized = true;
    return S_OK;

cleanup:
    inst->pRenderClient.Reset();
    inst->pAudioClient.Reset();
    inst->pAudioEndpointVolume.Reset();
    inst->pDevice.Reset();
    if (inst->pSourceAudioFormat) {
        CoTaskMemFree(inst->pSourceAudioFormat);
        inst->pSourceAudioFormat = nullptr;
    }
    inst->hAudioSamplesReadyEvent.Reset();
    inst->bAudioInitialized = false;
    return hr;
}

HRESULT PreFillAudioBuffer(VideoPlayerInstance* inst) {
    if (!inst || !inst->pAudioClient || !inst->pRenderClient) return E_INVALIDARG;

    IMFSourceReader* audioReader = inst->pSourceReaderAudio
        ? inst->pSourceReaderAudio.Get()
        : inst->pSourceReader.Get();
    if (!audioReader) return E_FAIL;

    UINT32 engineBufferFrames = 0;
    if (FAILED(inst->pAudioClient->GetBufferSize(&engineBufferFrames))) return E_FAIL;

    const UINT32 blockAlign = inst->pSourceAudioFormat ? inst->pSourceAudioFormat->nBlockAlign : 4;
    const UINT32 channels   = inst->pSourceAudioFormat ? inst->pSourceAudioFormat->nChannels : 2;

    WORD formatTag = WAVE_FORMAT_PCM, bitsPerSample = 16;
    if (inst->pSourceAudioFormat)
        ResolveFormatTag(inst->pSourceAudioFormat, &formatTag, &bitsPerSample);

    const float speed = inst->playbackSpeed.load(std::memory_order_relaxed);
    inst->resampleFracPos = 0.0;

    const UINT32 targetFrames = engineBufferFrames / 2;
    UINT32 totalFed = 0;
    for (int attempts = 0; attempts < 20 && totalFed < targetFrames; ++attempts) {
        int fed = FeedOneSample(inst, audioReader, engineBufferFrames,
                                blockAlign, channels, formatTag, bitsPerSample, speed);
        if (fed < 0) break;
        if (fed == 0) continue;
        totalFed += fed;
    }
    return S_OK;
}

HRESULT StartAudioThread(VideoPlayerInstance* inst) {
    if (!inst || !inst->bHasAudio || !inst->bAudioInitialized) return E_INVALIDARG;

    if (inst->hAudioThread) {
        WaitForSingleObject(inst->hAudioThread.Get(), 5000);
        inst->hAudioThread.Reset();
    }

    if (!inst->hAudioResumeEvent) {
        inst->hAudioResumeEvent.Reset(CreateEventW(nullptr, TRUE, TRUE, nullptr)); // manual-reset, initially signaled
    } else {
        SetEvent(inst->hAudioResumeEvent.Get());
    }

    inst->bAudioThreadRunning.store(true);
    HANDLE h = CreateThread(nullptr, 0, AudioThreadProc, inst, 0, nullptr);
    if (!h) {
        inst->bAudioThreadRunning.store(false);
        return HRESULT_FROM_WIN32(GetLastError());
    }
    inst->hAudioThread.Reset(h);
    return S_OK;
}

void StopAudioThread(VideoPlayerInstance* inst) {
    if (!inst) return;

    inst->bAudioThreadRunning.store(false);
    if (inst->hAudioResumeEvent) SetEvent(inst->hAudioResumeEvent.Get());
    if (inst->hAudioSamplesReadyEvent) SetEvent(inst->hAudioSamplesReadyEvent.Get());

    if (inst->hAudioThread) {
        WaitForSingleObject(inst->hAudioThread.Get(), 5000);
        inst->hAudioThread.Reset();
    }

    if (inst->pAudioClient) {
        VideoPlayerUtils::ScopedLock lock(inst->csAudioFeed);
        inst->pAudioClient->Stop();
    }
    inst->audioLatencyMs.store(0.0, std::memory_order_relaxed);
}

HRESULT SetVolume(VideoPlayerInstance* inst, float vol) {
    if (!inst) return E_INVALIDARG;
    inst->instanceVolume.store(std::clamp(vol, 0.0f, 1.0f), std::memory_order_relaxed);
    return S_OK;
}

HRESULT GetVolume(const VideoPlayerInstance* inst, float* out) {
    if (!inst || !out) return E_INVALIDARG;
    *out = inst->instanceVolume.load(std::memory_order_relaxed);
    return S_OK;
}

void SignalResume(VideoPlayerInstance* inst) {
    if (inst && inst->hAudioResumeEvent) SetEvent(inst->hAudioResumeEvent.Get());
    if (inst && inst->hAudioSamplesReadyEvent) SetEvent(inst->hAudioSamplesReadyEvent.Get());
}

void SignalPause(VideoPlayerInstance* inst) {
    if (inst && inst->hAudioResumeEvent) ResetEvent(inst->hAudioResumeEvent.Get());
}

} // namespace AudioManager
