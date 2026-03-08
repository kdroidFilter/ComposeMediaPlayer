#pragma once

#include <windows.h>
#include <mfapi.h>
#include <mfmediaengine.h>
#include <d3d11.h>
#include <atomic>
#include <cmath>

// Forward declaration
struct VideoPlayerInstance;

/**
 * HLS streaming player using IMFMediaEngine.
 *
 * IMFMediaEngine has native HLS support on Windows 10+ (unlike IMFSourceReader
 * which only supports HLS in UWP/Edge contexts). This class wraps the engine
 * and exposes a frame-server API compatible with the existing ReadVideoFrame
 * lock/unlock pattern.
 *
 * Audio playback is handled internally by the engine — no WASAPI setup needed.
 * Frame extraction uses TransferVideoFrame -> D3D11 staging texture -> CPU copy.
 */
class HLSPlayer : public IMFMediaEngineNotify {
public:
    HLSPlayer();
    ~HLSPlayer();

    // ---- IUnknown ----
    STDMETHODIMP QueryInterface(REFIID riid, void** ppv) override;
    STDMETHODIMP_(ULONG) AddRef() override;
    STDMETHODIMP_(ULONG) Release() override;

    // ---- IMFMediaEngineNotify ----
    STDMETHODIMP EventNotify(DWORD event, DWORD_PTR param1, DWORD param2) override;

    // ---- Lifecycle ----
    HRESULT Initialize(ID3D11Device* pDevice, IMFDXGIDeviceManager* pDXGIManager);
    HRESULT Open(const wchar_t* url);
    void    Close();

    // ---- Frame access (matches ReadVideoFrame / UnlockVideoFrame pattern) ----
    HRESULT ReadFrame(BYTE** ppData, DWORD* pDataSize);
    void    UnlockFrame(); // no-op — buffer owned by HLSPlayer

    // ---- Playback control ----
    HRESULT SetPlaying(BOOL bPlaying, BOOL bStop = FALSE);
    HRESULT Seek(LONGLONG position100ns);

    // ---- Properties ----
    BOOL    IsEOF()  const { return m_bEOF.load(); }
    BOOL    IsReady() const { return m_bReady.load(); }
    BOOL    HasAudio() const { return TRUE; } // engine handles audio
    void    GetVideoSize(UINT32* pW, UINT32* pH) const;
    HRESULT GetDuration(LONGLONG* pDuration) const;
    HRESULT GetPosition(LONGLONG* pPosition) const;
    HRESULT SetVolume(float vol);
    HRESULT GetVolume(float* pVol) const;
    HRESULT SetPlaybackSpeed(float speed);
    HRESULT GetPlaybackSpeed(float* pSpeed) const;

    // ---- Output scaling (mirrors SetOutputSize) ----
    HRESULT SetOutputSize(UINT32 targetW, UINT32 targetH);

private:
    LONG m_refCount = 1;

    // Media Engine
    IMFMediaEngineEx* m_pEngine = nullptr;

    // D3D11 (not owned — borrowed from MediaFoundationManager)
    ID3D11Device*        m_pDevice  = nullptr;
    ID3D11DeviceContext* m_pContext = nullptr;

    // Textures for frame extraction
    ID3D11Texture2D* m_pRenderTarget   = nullptr;
    ID3D11Texture2D* m_pStagingTexture = nullptr;

    // CPU frame buffer (returned by ReadFrame)
    BYTE* m_pFrameBuffer     = nullptr;
    DWORD m_frameBufferSize  = 0;

    // Video dimensions
    UINT32 m_nativeWidth  = 0;
    UINT32 m_nativeHeight = 0;
    UINT32 m_outputWidth  = 0; // 0 = use native
    UINT32 m_outputHeight = 0;

    // State
    LONGLONG m_lastPts = -1;
    std::atomic<bool> m_bReady{false};
    std::atomic<bool> m_bEOF{false};
    std::atomic<bool> m_bError{false};
    HANDLE m_hReadyEvent = nullptr;

    CRITICAL_SECTION m_cs;

    // Internal helpers
    UINT32 EffectiveWidth()  const { return m_outputWidth  > 0 ? m_outputWidth  : m_nativeWidth; }
    UINT32 EffectiveHeight() const { return m_outputHeight > 0 ? m_outputHeight : m_nativeHeight; }
    HRESULT EnsureTextures(UINT32 w, UINT32 h);
    void    ReleaseTextures();
};
