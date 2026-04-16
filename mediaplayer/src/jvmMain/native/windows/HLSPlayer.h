#pragma once

#include "ComHelpers.h"
#include <windows.h>
#include <mfapi.h>
#include <mfmediaengine.h>
#include <d3d11.h>
#include <wrl/client.h>
#include <atomic>
#include <cmath>

struct VideoPlayerInstance;

// HLS streaming player using IMFMediaEngine. IMFMediaEngine has native HLS
// support on Windows 10+ (unlike IMFSourceReader which only supports HLS in
// UWP/Edge contexts). Audio playback is handled internally by the engine —
// no WASAPI setup needed. Frame extraction goes through TransferVideoFrame
// -> D3D11 staging texture -> CPU copy.
class HLSPlayer : public IMFMediaEngineNotify {
public:
    HLSPlayer();
    virtual ~HLSPlayer();

    // IUnknown
    STDMETHODIMP         QueryInterface(REFIID riid, void** ppv) override;
    STDMETHODIMP_(ULONG) AddRef() override;
    STDMETHODIMP_(ULONG) Release() override;

    // IMFMediaEngineNotify
    STDMETHODIMP EventNotify(DWORD event, DWORD_PTR param1, DWORD param2) override;

    HRESULT Initialize(ID3D11Device* pDevice, IMFDXGIDeviceManager* pDXGIManager);
    HRESULT Open(const wchar_t* url, DWORD timeoutMs = 15000);
    void    Close();

    HRESULT ReadFrame(BYTE** ppData, DWORD* pDataSize);
    void    UnlockFrame();

    HRESULT SetPlaying(BOOL bPlaying, BOOL bStop = FALSE);
    HRESULT Seek(LONGLONG position100ns);

    BOOL    IsEOF()    const { return m_bEOF.load(); }
    BOOL    IsReady()  const { return m_bReady.load(); }
    BOOL    HasAudio() const { return TRUE; }
    void    GetVideoSize(UINT32* pW, UINT32* pH) const;
    HRESULT GetDuration(LONGLONG* pDuration) const;
    HRESULT GetPosition(LONGLONG* pPosition) const;
    HRESULT SetVolume(float vol);
    HRESULT GetVolume(float* pVol) const;
    HRESULT SetPlaybackSpeed(float speed);
    HRESULT GetPlaybackSpeed(float* pSpeed) const;
    HRESULT SetOutputSize(UINT32 targetW, UINT32 targetH);

private:
    LONG m_refCount = 1;

    Microsoft::WRL::ComPtr<IMFMediaEngineEx>   m_pEngine;
    Microsoft::WRL::ComPtr<ID3D11Device>       m_pDevice;
    Microsoft::WRL::ComPtr<ID3D11DeviceContext> m_pContext;
    Microsoft::WRL::ComPtr<ID3D11Texture2D>    m_pRenderTarget;
    Microsoft::WRL::ComPtr<ID3D11Texture2D>    m_pStagingTexture;

    BYTE* m_pFrameBuffer    = nullptr;
    DWORD m_frameBufferSize = 0;

    UINT32 m_nativeWidth  = 0;
    UINT32 m_nativeHeight = 0;
    UINT32 m_outputWidth  = 0;
    UINT32 m_outputHeight = 0;

    LONGLONG m_lastPts = -1;
    std::atomic<bool> m_bReady{false};
    std::atomic<bool> m_bEOF{false};
    std::atomic<bool> m_bError{false};
    VideoPlayerUtils::UniqueHandle m_hReadyEvent;

    mutable VideoPlayerUtils::CriticalSection m_cs;

    UINT32  EffectiveWidth()  const { return m_outputWidth  > 0 ? m_outputWidth  : m_nativeWidth; }
    UINT32  EffectiveHeight() const { return m_outputHeight > 0 ? m_outputHeight : m_nativeHeight; }
    HRESULT EnsureTextures(UINT32 w, UINT32 h);
    void    ReleaseTextures();
};
