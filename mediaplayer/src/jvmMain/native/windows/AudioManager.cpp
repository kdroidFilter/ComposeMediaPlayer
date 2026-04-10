// AudioManager.cpp – full rewrite with tighter A/V synchronisation
// -----------------------------------------------------------------------------
//  * Keeps the original public API so that existing call‑sites still compile.
//  * Uses an event‑driven render loop instead of busy‑wait polling where possible.
//  * Measures drift between the WASAPI render clock and the Media Foundation
//    presentation clock and corrects it gradually to avoid audible glitches.
//  * All sleeps are clamped to a minimum of 1 ms to keep the thread responsive.
//  * Volume scaling is done in place only when necessary and supports both
//    16‑bit and 32‑bit (float) PCM formats.
// -----------------------------------------------------------------------------

#include "AudioManager.h"
#include "VideoPlayerInstance.h"
#include "Utils.h"
#include "MediaFoundationManager.h"
#include <algorithm>
#include <cmath>
#include <array>
#include <mmreg.h>
// WAVE_FORMAT_EXTENSIBLE sub-format GUIDs for volume scaling.
// Defined inline to avoid pulling in <ks.h>/<ksmedia.h> which may conflict.
static const GUID kSubtypePCM =
    {0x00000001, 0x0000, 0x0010, {0x80, 0x00, 0x00, 0xAA, 0x00, 0x38, 0x9B, 0x71}};
static const GUID kSubtypeIEEEFloat =
    {0x00000003, 0x0000, 0x0010, {0x80, 0x00, 0x00, 0xAA, 0x00, 0x38, 0x9B, 0x71}};

using namespace VideoPlayerUtils;

namespace AudioManager {

// ‑‑‑‑‑‑‑‑‑‑‑‑‑‑‑‑‑‑‑‑‑‑ Helper constants ‑‑‑‑‑‑‑‑‑‑‑‑‑‑‑‑‑‑‑‑‑‑‑‑‑‑
constexpr REFERENCE_TIME kTargetBufferDuration100ns = 2'000'000; // 200 ms
constexpr REFERENCE_TIME kMinSleepUs              = 1'000;       // 1 ms
constexpr double         kDriftPositiveThresholdMs =  15.0;      // audio ahead  → wait
constexpr double         kDriftNegativeThresholdMs = -50.0;      // audio behind → drop

// ------------------------------------------------------------------------------------
//  InitWASAPI  –  initialises the shared WASAPI client for the default render endpoint
// ------------------------------------------------------------------------------------
HRESULT InitWASAPI(VideoPlayerInstance* inst, const WAVEFORMATEX* srcFmt)
{
    if (!inst) return E_INVALIDARG;

    // Reuse previously initialized client if still valid
    if (inst->pAudioClient && inst->pRenderClient) {
        inst->bAudioInitialized = TRUE;
        return S_OK;
    }

    HRESULT hr = S_OK;
    WAVEFORMATEX* deviceMixFmt = nullptr;

    // 1. Get the default render device
    IMMDeviceEnumerator* enumerator = MediaFoundation::GetDeviceEnumerator();
    if (!enumerator) return E_FAIL;

    hr = enumerator->GetDefaultAudioEndpoint(eRender, eConsole, &inst->pDevice);
    if (FAILED(hr)) goto fail;

    // 2. Activate IAudioClient + IAudioEndpointVolume
    hr = inst->pDevice->Activate(__uuidof(IAudioClient), CLSCTX_ALL, nullptr,
                                 reinterpret_cast<void**>(&inst->pAudioClient));
    if (FAILED(hr)) goto fail;

    hr = inst->pDevice->Activate(__uuidof(IAudioEndpointVolume), CLSCTX_ALL, nullptr,
                                 reinterpret_cast<void**>(&inst->pAudioEndpointVolume));
    if (FAILED(hr)) goto fail;

    // 3. Determine the format that will be rendered
    if (!srcFmt) {
        hr = inst->pAudioClient->GetMixFormat(&deviceMixFmt);
        if (FAILED(hr)) goto fail;
        srcFmt = deviceMixFmt;
    }
    inst->pSourceAudioFormat = reinterpret_cast<WAVEFORMATEX*>(
        CoTaskMemAlloc(srcFmt->cbSize + sizeof(WAVEFORMATEX)));
    if (!inst->pSourceAudioFormat) { hr = E_OUTOFMEMORY; goto fail; }
    memcpy(inst->pSourceAudioFormat, srcFmt, srcFmt->cbSize + sizeof(WAVEFORMATEX));

    // 4. Create (or reuse) the render-ready event
    if (!inst->hAudioSamplesReadyEvent) {
        inst->hAudioSamplesReadyEvent = CreateEvent(nullptr, FALSE, FALSE, nullptr);
        if (!inst->hAudioSamplesReadyEvent) {
            hr = HRESULT_FROM_WIN32(GetLastError());
            goto fail;
        }
    }

    // 5. Initialize the audio client in shared, event-callback mode
    hr = inst->pAudioClient->Initialize(AUDCLNT_SHAREMODE_SHARED,
                                        AUDCLNT_STREAMFLAGS_EVENTCALLBACK,
                                        kTargetBufferDuration100ns,
                                        0,
                                        srcFmt,
                                        nullptr);
    if (FAILED(hr)) goto fail;

    hr = inst->pAudioClient->SetEventHandle(inst->hAudioSamplesReadyEvent);
    if (FAILED(hr)) goto fail;

    // 6. Grab the render-client service interface
    hr = inst->pAudioClient->GetService(__uuidof(IAudioRenderClient),
                                        reinterpret_cast<void**>(&inst->pRenderClient));
    if (FAILED(hr)) goto fail;

    inst->bAudioInitialized = TRUE;
    if (deviceMixFmt) CoTaskMemFree(deviceMixFmt);
    return S_OK;

fail:
    // Release any partially-created COM objects so that CloseMedia does not
    // call methods (e.g. pAudioClient->Stop()) on an uninitialized client.
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

// ----------------------------------------------------------------------------
//  AudioThreadProc – feeds decoded audio samples into the WASAPI render client
// ----------------------------------------------------------------------------
DWORD WINAPI AudioThreadProc(LPVOID lpParam)
{
    auto* inst = static_cast<VideoPlayerInstance*>(lpParam);
    if (!inst || !inst->pAudioClient || !inst->pRenderClient || !inst->pSourceReaderAudio)
        return 0;

    // Pre‑warm the audio engine so that GetBufferSize() is valid
    UINT32 engineBufferFrames = 0;
    if (FAILED(inst->pAudioClient->GetBufferSize(&engineBufferFrames)))
        return 0;

    if (inst->hAudioReadyEvent)
        WaitForSingleObject(inst->hAudioReadyEvent, INFINITE);

    const UINT32 blockAlign = inst->pSourceAudioFormat ? inst->pSourceAudioFormat->nBlockAlign : 4;

    // Main render loop – wait for "ready" event, then push as many frames as possible
    while (inst->bAudioThreadRunning) {
        DWORD signalled = WaitForSingleObject(inst->hAudioSamplesReadyEvent, 10);
        if (signalled != WAIT_OBJECT_0) continue; // timeout ⇒ loop back

        // Handle seek / pause concurrently with the decoder thread
        {
            EnterCriticalSection(&inst->csClockSync);
            bool suspended = inst->bSeekInProgress || inst->llPauseStart != 0;
            LeaveCriticalSection(&inst->csClockSync);
            if (suspended) {
                PreciseSleepHighRes(5);
                continue;
            }
        }

        // How many frames are currently available for writing?
        UINT32 framesPadding = 0;
        if (FAILED(inst->pAudioClient->GetCurrentPadding(&framesPadding)))
            break;
        UINT32 framesFree = engineBufferFrames - framesPadding;
        if (framesFree == 0) continue; // buffer full – wait for next event

        // Read one decoded sample from MF (non‑blocking)
        IMFSample* sample = nullptr;
        DWORD      flags  = 0;
        LONGLONG   ts100n = 0;
        HRESULT hr = inst->pSourceReaderAudio->ReadSample(MF_SOURCE_READER_FIRST_AUDIO_STREAM,
                                                          0, nullptr, &flags, &ts100n, &sample);
        if (FAILED(hr)) break;
        if (!sample)     continue; // decoder starved – wait for more data
        if (flags & MF_SOURCE_READERF_ENDOFSTREAM) {
            sample->Release();
            break;
        }

        // Measure drift between sample PTS and wall clock (real elapsed time)
        // This ensures audio and video are synchronized to the same time reference
        double driftMs = 0.0;
        if (inst->bUseClockSync && inst->llPlaybackStartTime != 0 && ts100n > 0) {
            // Calculate elapsed time since playback started (in milliseconds)
            LONGLONG currentTimeMs = GetCurrentTimeMs();
            LONGLONG elapsedMs = currentTimeMs - inst->llPlaybackStartTime - inst->llTotalPauseTime;

            // Apply playback speed to elapsed time
            double adjustedElapsedMs = elapsedMs * inst->playbackSpeed.load(std::memory_order_relaxed);

            // Convert sample timestamp from 100ns units to milliseconds
            double sampleTimeMs = ts100n / 10000.0;

            // Calculate drift: positive means audio is ahead, negative means audio is late
            driftMs = sampleTimeMs - adjustedElapsedMs;
        }

        if (driftMs > kDriftPositiveThresholdMs) {
            // Audio ahead → delay feed to renderer
            PreciseSleepHighRes(std::min(driftMs, 100.0));
        } else if (driftMs < kDriftNegativeThresholdMs) {
            // Audio too late → drop sample completely (skip)
            sample->Release();
            continue;
        }

        // Copy contiguous audio buffer into render buffer – may span multiple GetBuffer() calls
        IMFMediaBuffer* mediaBuf = nullptr;
        if (FAILED(sample->ConvertToContiguousBuffer(&mediaBuf)) || !mediaBuf) {
            sample->Release();
            continue;
        }

        BYTE*  srcData = nullptr;
        DWORD  srcSize = 0, srcMax = 0;
        if (FAILED(mediaBuf->Lock(&srcData, &srcMax, &srcSize))) {
            mediaBuf->Release();
            sample->Release();
            continue;
        }

        UINT32 totalFrames = srcSize / blockAlign;
        UINT32 offsetFrames = 0;

        while (offsetFrames < totalFrames) {
            UINT32 framesWanted = std::min(totalFrames - offsetFrames, framesFree);
            if (framesWanted == 0) {
                // Renderer is full → wait for next event
                WaitForSingleObject(inst->hAudioSamplesReadyEvent, 5);
                if (FAILED(inst->pAudioClient->GetCurrentPadding(&framesPadding))) break;
                framesFree = engineBufferFrames - framesPadding;
                continue;
            }

            BYTE* dstData = nullptr;
            if (FAILED(inst->pRenderClient->GetBuffer(framesWanted, &dstData)) || !dstData) break;

            const BYTE* chunkStart = srcData + (offsetFrames * blockAlign);
            memcpy(dstData, chunkStart, framesWanted * blockAlign);

            // Apply per-instance volume in-place.
            // Supports PCM 16-bit, PCM 24-bit, IEEE float 32-bit, and
            // WAVE_FORMAT_EXTENSIBLE wrappers around those sub-formats.
            const float vol = inst->instanceVolume.load(std::memory_order_relaxed);
            if (vol < 0.999f) {
                WORD formatTag = inst->pSourceAudioFormat->wFormatTag;
                WORD bitsPerSample = inst->pSourceAudioFormat->wBitsPerSample;

                // Unwrap WAVE_FORMAT_EXTENSIBLE to the actual sub-format
                if (formatTag == WAVE_FORMAT_EXTENSIBLE && inst->pSourceAudioFormat->cbSize >= 22) {
                    auto* ext = reinterpret_cast<WAVEFORMATEXTENSIBLE*>(inst->pSourceAudioFormat);
                    if (ext->SubFormat == kSubtypePCM)
                        formatTag = WAVE_FORMAT_PCM;
                    else if (ext->SubFormat == kSubtypeIEEEFloat)
                        formatTag = WAVE_FORMAT_IEEE_FLOAT;
                }

                if (formatTag == WAVE_FORMAT_PCM && bitsPerSample == 16) {
                    auto* s = reinterpret_cast<int16_t*>(dstData);
                    size_t n = (framesWanted * blockAlign) / sizeof(int16_t);
                    for (size_t i = 0; i < n; ++i)
                        s[i] = static_cast<int16_t>(s[i] * vol);
                } else if (formatTag == WAVE_FORMAT_PCM && bitsPerSample == 24) {
                    // 24-bit PCM: 3 bytes per sample, little-endian
                    size_t totalBytes = framesWanted * blockAlign;
                    for (size_t i = 0; i + 2 < totalBytes; i += 3) {
                        int32_t sample = static_cast<int8_t>(dstData[i + 2]);
                        sample = (sample << 8) | dstData[i + 1];
                        sample = (sample << 8) | dstData[i];
                        sample = static_cast<int32_t>(sample * vol);
                        dstData[i]     = static_cast<BYTE>(sample & 0xFF);
                        dstData[i + 1] = static_cast<BYTE>((sample >> 8) & 0xFF);
                        dstData[i + 2] = static_cast<BYTE>((sample >> 16) & 0xFF);
                    }
                } else if (formatTag == WAVE_FORMAT_IEEE_FLOAT && bitsPerSample == 32) {
                    auto* s = reinterpret_cast<float*>(dstData);
                    size_t n = (framesWanted * blockAlign) / sizeof(float);
                    for (size_t i = 0; i < n; ++i) s[i] *= vol;
                }
            }

            inst->pRenderClient->ReleaseBuffer(framesWanted, 0);
            offsetFrames += framesWanted;

            // Recompute free frames for potential second iteration in this loop
            if (FAILED(inst->pAudioClient->GetCurrentPadding(&framesPadding))) break;
            framesFree = engineBufferFrames - framesPadding;
        }

        mediaBuf->Unlock();
        mediaBuf->Release();
        sample->Release();
    }

    inst->pAudioClient->Stop();
    return 0;
}

// -------------------------------------------------------------
//  Thread management helpers
// -------------------------------------------------------------
HRESULT StartAudioThread(VideoPlayerInstance* inst)
{
    if (!inst || !inst->bHasAudio || !inst->bAudioInitialized)
        return E_INVALIDARG;

    // Terminate any previous thread first
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
    if (inst->hAudioThread) {
        if (WaitForSingleObject(inst->hAudioThread, 1000) == WAIT_TIMEOUT)
            TerminateThread(inst->hAudioThread, 0);
        CloseHandle(inst->hAudioThread);
        inst->hAudioThread = nullptr;
    }

    if (inst->pAudioClient) inst->pAudioClient->Stop();
}

// -----------------------------------------
//  Per‑instance volume helpers (0.0 – 1.0)
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
