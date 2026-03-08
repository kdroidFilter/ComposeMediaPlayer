// HLSPlayer.cpp — IMFMediaEngine-based HLS streaming player
//
// On Windows 10+, IMFMediaEngine supports HLS natively (unlike IMFSourceReader
// which only handles HLS in UWP/Edge contexts). This file wraps the engine to
// provide frame-by-frame access compatible with the existing player API.

#include "HLSPlayer.h"
#include <mferror.h>
#include <algorithm>

#ifdef _DEBUG
#define HLS_LOG(msg, ...) fprintf(stderr, "[HLS] " msg "\n", ##__VA_ARGS__)
#else
#define HLS_LOG(msg, ...) ((void)0)
#endif

static const DXGI_FORMAT kTextureFormat = DXGI_FORMAT_B8G8R8A8_UNORM;

// ============================================================================
// IUnknown
// ============================================================================

HLSPlayer::HLSPlayer() {
    InitializeCriticalSection(&m_cs);
}

HLSPlayer::~HLSPlayer() {
    Close();
    DeleteCriticalSection(&m_cs);
}

STDMETHODIMP HLSPlayer::QueryInterface(REFIID riid, void** ppv) {
    if (!ppv) return E_POINTER;
    if (riid == __uuidof(IUnknown) || riid == __uuidof(IMFMediaEngineNotify)) {
        *ppv = static_cast<IMFMediaEngineNotify*>(this);
        AddRef();
        return S_OK;
    }
    *ppv = nullptr;
    return E_NOINTERFACE;
}

STDMETHODIMP_(ULONG) HLSPlayer::AddRef()  { return InterlockedIncrement(&m_refCount); }
STDMETHODIMP_(ULONG) HLSPlayer::Release() {
    LONG c = InterlockedDecrement(&m_refCount);
    if (c == 0) delete this;
    return c;
}

// ============================================================================
// IMFMediaEngineNotify — called on an internal MF thread
// ============================================================================

STDMETHODIMP HLSPlayer::EventNotify(DWORD event, DWORD_PTR param1, DWORD param2) {
    switch (event) {
    case MF_MEDIA_ENGINE_EVENT_LOADEDMETADATA:
    case MF_MEDIA_ENGINE_EVENT_FORMATCHANGE: {
        // Resolution may have changed (HLS adaptive bitrate)
        if (m_pEngine) {
            DWORD w = 0, h = 0;
            m_pEngine->GetNativeVideoSize(&w, &h);
            if (w > 0 && h > 0) {
                EnterCriticalSection(&m_cs);
                m_nativeWidth  = w;
                m_nativeHeight = h;
                LeaveCriticalSection(&m_cs);
                HLS_LOG("Video size: %ux%u", w, h);
            }
        }
        break;
    }

    case MF_MEDIA_ENGINE_EVENT_CANPLAY:
    case MF_MEDIA_ENGINE_EVENT_CANPLAYTHROUGH:
        m_bReady.store(true);
        if (m_hReadyEvent) SetEvent(m_hReadyEvent);
        break;

    case MF_MEDIA_ENGINE_EVENT_ENDED:
        m_bEOF.store(true);
        HLS_LOG("End of stream");
        break;

    case MF_MEDIA_ENGINE_EVENT_ERROR:
        m_bError.store(true);
        HLS_LOG("Error: param1=%llu param2=%u", (unsigned long long)param1, param2);
        if (m_hReadyEvent) SetEvent(m_hReadyEvent); // unblock Open
        break;

    default:
        break;
    }
    return S_OK;
}

// ============================================================================
// Lifecycle
// ============================================================================

HRESULT HLSPlayer::Initialize(ID3D11Device* pDevice, IMFDXGIDeviceManager* pDXGIManager) {
    if (!pDevice || !pDXGIManager) return E_INVALIDARG;

    m_pDevice = pDevice;
    m_pDevice->GetImmediateContext(&m_pContext);

    // Create the Media Engine via class factory
    IMFMediaEngineClassFactory* pFactory = nullptr;
    HRESULT hr = CoCreateInstance(CLSID_MFMediaEngineClassFactory, nullptr,
                                  CLSCTX_ALL, IID_PPV_ARGS(&pFactory));
    if (FAILED(hr)) {
        HLS_LOG("CoCreateInstance MFMediaEngineClassFactory failed: 0x%08x", (unsigned)hr);
        return hr;
    }

    IMFAttributes* pAttrs = nullptr;
    hr = MFCreateAttributes(&pAttrs, 3);
    if (FAILED(hr)) { pFactory->Release(); return hr; }

    pAttrs->SetUnknown(MF_MEDIA_ENGINE_CALLBACK,
                       static_cast<IMFMediaEngineNotify*>(this));
    pAttrs->SetUnknown(MF_MEDIA_ENGINE_DXGI_MANAGER, pDXGIManager);
    pAttrs->SetUINT32(MF_MEDIA_ENGINE_VIDEO_OUTPUT_FORMAT, kTextureFormat);

    IMFMediaEngine* pEngine = nullptr;
    hr = pFactory->CreateInstance(0, pAttrs, &pEngine);
    pAttrs->Release();
    pFactory->Release();
    if (FAILED(hr)) {
        HLS_LOG("CreateInstance failed: 0x%08x", (unsigned)hr);
        return hr;
    }

    // QI for IMFMediaEngineEx (needed for SetCurrentTime seek)
    hr = pEngine->QueryInterface(IID_PPV_ARGS(&m_pEngine));
    pEngine->Release();
    if (FAILED(hr)) {
        HLS_LOG("QI for IMFMediaEngineEx failed: 0x%08x", (unsigned)hr);
        return hr;
    }

    return S_OK;
}

HRESULT HLSPlayer::Open(const wchar_t* url) {
    if (!m_pEngine || !url) return E_INVALIDARG;

    m_bEOF.store(false);
    m_bError.store(false);
    m_bReady.store(false);
    m_lastPts = -1;

    // Create a ready event for synchronous wait
    if (!m_hReadyEvent)
        m_hReadyEvent = CreateEvent(nullptr, TRUE, FALSE, nullptr);
    else
        ResetEvent(m_hReadyEvent);

    // SetSource requires a BSTR
    BSTR bstrUrl = SysAllocString(url);
    if (!bstrUrl) return E_OUTOFMEMORY;

    HRESULT hr = m_pEngine->SetSource(bstrUrl);
    SysFreeString(bstrUrl);
    if (FAILED(hr)) {
        HLS_LOG("SetSource failed: 0x%08x", (unsigned)hr);
        return hr;
    }

    hr = m_pEngine->Load();
    if (FAILED(hr)) {
        HLS_LOG("Load failed: 0x%08x", (unsigned)hr);
        return hr;
    }

    // Wait for the engine to reach a playable state (up to 15 s).
    // EventNotify signals m_hReadyEvent on CANPLAY or ERROR.
    // We also poll GetReadyState as a safety net.
    for (int i = 0; i < 150; i++) {
        DWORD wait = WaitForSingleObject(m_hReadyEvent, 100);
        if (wait == WAIT_OBJECT_0) break;

        // Poll readyState directly
        USHORT state = m_pEngine->GetReadyState();
        if (state >= MF_MEDIA_ENGINE_READY_HAVE_FUTURE_DATA) {
            m_bReady.store(true);
            break;
        }

        // Check for error
        IMFMediaError* pErr = nullptr;
        m_pEngine->GetError(&pErr);
        if (pErr) {
            USHORT code = pErr->GetErrorCode();
            pErr->Release();
            HLS_LOG("Engine error code: %u", code);
            return MF_E_INVALIDMEDIATYPE;
        }
    }

    if (m_bError.load()) {
        HLS_LOG("Open aborted due to engine error");
        return MF_E_INVALIDMEDIATYPE;
    }

    if (!m_bReady.load()) {
        // One more check
        USHORT state = m_pEngine->GetReadyState();
        if (state >= MF_MEDIA_ENGINE_READY_HAVE_METADATA) {
            m_bReady.store(true);
        } else {
            HLS_LOG("Open timed out (readyState=%u)", state);
            return MF_E_NET_READ;
        }
    }

    // Retrieve native video dimensions
    {
        DWORD w = 0, h = 0;
        m_pEngine->GetNativeVideoSize(&w, &h);
        EnterCriticalSection(&m_cs);
        m_nativeWidth  = w;
        m_nativeHeight = h;
        LeaveCriticalSection(&m_cs);
        HLS_LOG("Opened: %ux%u", w, h);
    }

    return S_OK;
}

void HLSPlayer::Close() {
    if (m_pEngine) {
        m_pEngine->Shutdown();
        m_pEngine->Release();
        m_pEngine = nullptr;
    }

    ReleaseTextures();

    if (m_pFrameBuffer) {
        delete[] m_pFrameBuffer;
        m_pFrameBuffer    = nullptr;
        m_frameBufferSize = 0;
    }

    if (m_pContext) {
        m_pContext->Release();
        m_pContext = nullptr;
    }

    if (m_hReadyEvent) {
        CloseHandle(m_hReadyEvent);
        m_hReadyEvent = nullptr;
    }

    m_nativeWidth = m_nativeHeight = 0;
    m_outputWidth = m_outputHeight = 0;
    m_lastPts = -1;
    m_bReady.store(false);
    m_bEOF.store(false);
    m_bError.store(false);
}

// ============================================================================
// D3D11 texture management
// ============================================================================

HRESULT HLSPlayer::EnsureTextures(UINT32 w, UINT32 h) {
    if (w == 0 || h == 0) return E_INVALIDARG;

    // Check if existing textures are the right size
    if (m_pRenderTarget) {
        D3D11_TEXTURE2D_DESC desc;
        m_pRenderTarget->GetDesc(&desc);
        if (desc.Width == w && desc.Height == h)
            return S_OK;
        ReleaseTextures();
    }

    // Render target (GPU, for TransferVideoFrame)
    D3D11_TEXTURE2D_DESC desc = {};
    desc.Width            = w;
    desc.Height           = h;
    desc.MipLevels        = 1;
    desc.ArraySize        = 1;
    desc.Format           = kTextureFormat;
    desc.SampleDesc.Count = 1;
    desc.Usage            = D3D11_USAGE_DEFAULT;
    desc.BindFlags        = D3D11_BIND_RENDER_TARGET;

    HRESULT hr = m_pDevice->CreateTexture2D(&desc, nullptr, &m_pRenderTarget);
    if (FAILED(hr)) return hr;

    // Staging texture (CPU-readable)
    desc.Usage          = D3D11_USAGE_STAGING;
    desc.BindFlags      = 0;
    desc.CPUAccessFlags = D3D11_CPU_ACCESS_READ;

    hr = m_pDevice->CreateTexture2D(&desc, nullptr, &m_pStagingTexture);
    if (FAILED(hr)) {
        m_pRenderTarget->Release();
        m_pRenderTarget = nullptr;
        return hr;
    }

    // Resize CPU frame buffer
    DWORD needed = w * h * 4;
    if (m_frameBufferSize < needed) {
        delete[] m_pFrameBuffer;
        m_pFrameBuffer    = new (std::nothrow) BYTE[needed];
        m_frameBufferSize = m_pFrameBuffer ? needed : 0;
        if (!m_pFrameBuffer) return E_OUTOFMEMORY;
    }

    return S_OK;
}

void HLSPlayer::ReleaseTextures() {
    if (m_pRenderTarget)   { m_pRenderTarget->Release();   m_pRenderTarget   = nullptr; }
    if (m_pStagingTexture) { m_pStagingTexture->Release(); m_pStagingTexture = nullptr; }
}

// ============================================================================
// Frame access
// ============================================================================

HRESULT HLSPlayer::ReadFrame(BYTE** ppData, DWORD* pDataSize) {
    if (!ppData || !pDataSize) return E_INVALIDARG;
    *ppData   = nullptr;
    *pDataSize = 0;

    if (!m_pEngine || !m_bReady.load()) return S_OK; // not ready yet
    if (m_bEOF.load()) return S_FALSE;

    if (!m_pEngine->HasVideo()) return S_OK;

    // Check for a new frame
    LONGLONG pts = 0;
    HRESULT hr = m_pEngine->OnVideoStreamTick(&pts);
    if (hr == S_FALSE) return S_OK; // no new frame yet
    if (FAILED(hr)) return hr;

    // Skip duplicate frames (same pts)
    if (pts == m_lastPts) return S_OK;

    // Query current native dimensions (may change with HLS ABR)
    DWORD natW = 0, natH = 0;
    m_pEngine->GetNativeVideoSize(&natW, &natH);
    if (natW == 0 || natH == 0) return S_OK;

    EnterCriticalSection(&m_cs);
    m_nativeWidth  = natW;
    m_nativeHeight = natH;
    UINT32 w = EffectiveWidth();
    UINT32 h = EffectiveHeight();
    LeaveCriticalSection(&m_cs);

    // Ensure D3D textures are the right size
    hr = EnsureTextures(w, h);
    if (FAILED(hr)) return hr;

    // Transfer the current video frame to our render target
    RECT destRect = { 0, 0, (LONG)w, (LONG)h };
    MFARGB borderColor = { 0, 0, 0, 255 };
    hr = m_pEngine->TransferVideoFrame(m_pRenderTarget, nullptr, &destRect, &borderColor);
    if (FAILED(hr)) {
        HLS_LOG("TransferVideoFrame failed: 0x%08x", (unsigned)hr);
        return hr;
    }

    // Copy render target → staging texture
    m_pContext->CopyResource(m_pStagingTexture, m_pRenderTarget);

    // Map staging texture → CPU frame buffer
    D3D11_MAPPED_SUBRESOURCE mapped = {};
    hr = m_pContext->Map(m_pStagingTexture, 0, D3D11_MAP_READ, 0, &mapped);
    if (FAILED(hr)) return hr;

    const DWORD dstRowBytes = w * 4;
    if ((UINT)mapped.RowPitch == dstRowBytes) {
        memcpy(m_pFrameBuffer, mapped.pData, dstRowBytes * h);
    } else {
        const BYTE* pSrc = static_cast<const BYTE*>(mapped.pData);
        BYTE* pDst = m_pFrameBuffer;
        for (UINT32 y = 0; y < h; y++) {
            memcpy(pDst, pSrc, dstRowBytes);
            pSrc += mapped.RowPitch;
            pDst += dstRowBytes;
        }
    }

    m_pContext->Unmap(m_pStagingTexture, 0);

    m_lastPts  = pts;
    *ppData    = m_pFrameBuffer;
    *pDataSize = w * h * 4;
    return S_OK;
}

void HLSPlayer::UnlockFrame() {
    // No-op: frame buffer is owned by HLSPlayer and reused across calls
}

// ============================================================================
// Playback control
// ============================================================================

HRESULT HLSPlayer::SetPlaying(BOOL bPlaying, BOOL bStop) {
    if (!m_pEngine) return E_FAIL;

    if (bStop && !bPlaying) {
        m_pEngine->Pause();
        m_pEngine->SetCurrentTime(0);
        m_bEOF.store(false);
        return S_OK;
    }

    if (bPlaying) {
        m_bEOF.store(false);
        return m_pEngine->Play();
    } else {
        return m_pEngine->Pause();
    }
}

HRESULT HLSPlayer::Seek(LONGLONG position100ns) {
    if (!m_pEngine) return E_FAIL;
    double seconds = position100ns / 10000000.0;
    m_bEOF.store(false);
    m_lastPts = -1; // force next frame to be read
    m_pEngine->SetCurrentTime(seconds);
    return S_OK;
}

// ============================================================================
// Properties
// ============================================================================

void HLSPlayer::GetVideoSize(UINT32* pW, UINT32* pH) const {
    EnterCriticalSection(const_cast<CRITICAL_SECTION*>(&m_cs));
    if (pW) *pW = EffectiveWidth();
    if (pH) *pH = EffectiveHeight();
    LeaveCriticalSection(const_cast<CRITICAL_SECTION*>(&m_cs));
}

HRESULT HLSPlayer::GetDuration(LONGLONG* pDuration) const {
    if (!m_pEngine || !pDuration) return E_INVALIDARG;
    double dur = m_pEngine->GetDuration();
    if (std::isnan(dur) || std::isinf(dur) || dur <= 0.0) {
        *pDuration = 0; // live stream
    } else {
        *pDuration = static_cast<LONGLONG>(dur * 10000000.0);
    }
    return S_OK;
}

HRESULT HLSPlayer::GetPosition(LONGLONG* pPosition) const {
    if (!m_pEngine || !pPosition) return E_INVALIDARG;
    double pos = m_pEngine->GetCurrentTime();
    *pPosition = static_cast<LONGLONG>(pos * 10000000.0);
    return S_OK;
}

HRESULT HLSPlayer::SetVolume(float vol) {
    if (!m_pEngine) return E_FAIL;
    return m_pEngine->SetVolume(static_cast<double>(std::clamp(vol, 0.0f, 1.0f)));
}

HRESULT HLSPlayer::GetVolume(float* pVol) const {
    if (!m_pEngine || !pVol) return E_INVALIDARG;
    *pVol = static_cast<float>(m_pEngine->GetVolume());
    return S_OK;
}

HRESULT HLSPlayer::SetPlaybackSpeed(float speed) {
    if (!m_pEngine) return E_FAIL;
    return m_pEngine->SetPlaybackRate(static_cast<double>(speed));
}

HRESULT HLSPlayer::GetPlaybackSpeed(float* pSpeed) const {
    if (!m_pEngine || !pSpeed) return E_INVALIDARG;
    *pSpeed = static_cast<float>(m_pEngine->GetPlaybackRate());
    return S_OK;
}

HRESULT HLSPlayer::SetOutputSize(UINT32 targetW, UINT32 targetH) {
    EnterCriticalSection(&m_cs);

    if (targetW == 0 || targetH == 0) {
        // Reset to native
        m_outputWidth = m_outputHeight = 0;
        LeaveCriticalSection(&m_cs);
        return S_OK;
    }

    // Don't scale up
    if (targetW > m_nativeWidth || targetH > m_nativeHeight) {
        targetW = m_nativeWidth;
        targetH = m_nativeHeight;
    }

    // Preserve aspect ratio
    if (m_nativeWidth > 0 && m_nativeHeight > 0) {
        double srcAspect = (double)m_nativeWidth / m_nativeHeight;
        double dstAspect = (double)targetW / targetH;
        if (srcAspect > dstAspect)
            targetH = (UINT32)(targetW / srcAspect);
        else
            targetW = (UINT32)(targetH * srcAspect);
    }

    // Even dimensions
    targetW = (targetW + 1) & ~1u;
    targetH = (targetH + 1) & ~1u;
    if (targetW < 2) targetW = 2;
    if (targetH < 2) targetH = 2;

    m_outputWidth  = targetW;
    m_outputHeight = targetH;
    LeaveCriticalSection(&m_cs);
    return S_OK;
}
