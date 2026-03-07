#include "MediaFoundationManager.h"
#include <mfidl.h>
#include <mfreadwrite.h>
#include <dxgi.h>

namespace MediaFoundation {

// Global resources shared across all instances
static bool g_bMFInitialized = false;
static ID3D11Device* g_pD3DDevice = nullptr;
static IMFDXGIDeviceManager* g_pDXGIDeviceManager = nullptr;
static UINT32 g_dwResetToken = 0;
static IMMDeviceEnumerator* g_pEnumerator = nullptr;
static int g_instanceCount = 0;

HRESULT Initialize() {
    if (g_bMFInitialized)
        return OP_E_ALREADY_INITIALIZED;

    HRESULT hr = CoInitializeEx(nullptr, COINIT_MULTITHREADED);
    if (SUCCEEDED(hr))
        hr = MFStartup(MF_VERSION);
    if (FAILED(hr))
        return hr;

    hr = CreateDX11Device();
    if (FAILED(hr)) { 
        MFShutdown(); 
        return hr; 
    }

    hr = MFCreateDXGIDeviceManager(&g_dwResetToken, &g_pDXGIDeviceManager);
    if (SUCCEEDED(hr))
        hr = g_pDXGIDeviceManager->ResetDevice(g_pD3DDevice, g_dwResetToken);
    if (FAILED(hr)) {
        if (g_pD3DDevice) { 
            g_pD3DDevice->Release(); 
            g_pD3DDevice = nullptr; 
        }
        MFShutdown();
        return hr;
    }

    g_bMFInitialized = true;
    return S_OK;
}

HRESULT Shutdown() {
    if (g_instanceCount > 0)
        return E_FAIL; // Instances still active

    HRESULT hr = S_OK;

    // Release DXGI and D3D resources
    if (g_pDXGIDeviceManager) {
        g_pDXGIDeviceManager->Release();
        g_pDXGIDeviceManager = nullptr;
    }

    if (g_pD3DDevice) {
        g_pD3DDevice->Release();
        g_pD3DDevice = nullptr;
    }

    // Release audio enumerator
    if (g_pEnumerator) {
        g_pEnumerator->Release();
        g_pEnumerator = nullptr;
    }

    // Shutdown Media Foundation last
    if (g_bMFInitialized) {
        hr = MFShutdown();
        g_bMFInitialized = false;
    }

    // Uninitialize COM
    CoUninitialize();
    return hr;
}

HRESULT CreateDX11Device() {
    HRESULT hr = D3D11CreateDevice(nullptr, D3D_DRIVER_TYPE_HARDWARE, nullptr,
                                  D3D11_CREATE_DEVICE_VIDEO_SUPPORT, nullptr, 0,
                                  D3D11_SDK_VERSION, &g_pD3DDevice, nullptr, nullptr);
    if (FAILED(hr))
        return hr;
    
    ID3D10Multithread* pMultithread = nullptr;
    if (SUCCEEDED(g_pD3DDevice->QueryInterface(__uuidof(ID3D10Multithread), reinterpret_cast<void**>(&pMultithread)))) {
        pMultithread->SetMultithreadProtected(TRUE);
        pMultithread->Release();
    }
    
    return hr;
}

ID3D11Device* GetD3DDevice() {
    return g_pD3DDevice;
}

IMFDXGIDeviceManager* GetDXGIDeviceManager() {
    return g_pDXGIDeviceManager;
}

IMMDeviceEnumerator* GetDeviceEnumerator() {
    if (!g_pEnumerator) {
        CoCreateInstance(__uuidof(MMDeviceEnumerator), nullptr, CLSCTX_ALL, 
                        IID_PPV_ARGS(&g_pEnumerator));
    }
    return g_pEnumerator;
}

void IncrementInstanceCount() {
    g_instanceCount++;
}

void DecrementInstanceCount() {
    g_instanceCount--;
}

bool IsInitialized() {
    return g_bMFInitialized;
}

int GetInstanceCount() {
    return g_instanceCount;
}

} // namespace MediaFoundation