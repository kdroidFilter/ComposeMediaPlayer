// MediaFoundationManager.cpp — process-wide Media Foundation / D3D11 / audio
// enumerator bootstrap. Refcounts instances so Shutdown() is a no-op while
// players are alive.

#include "MediaFoundationManager.h"
#include <mfreadwrite.h>
#include <dxgi.h>
#include <atomic>
#include <wrl/client.h>

using Microsoft::WRL::ComPtr;

namespace MediaFoundation {
namespace {

std::atomic<bool>      g_initialized{false};
std::atomic<int>       g_instanceCount{0};
std::atomic<bool>      g_comInitialized{false};

ComPtr<ID3D11Device>         g_device;
ComPtr<IMFDXGIDeviceManager> g_dxgiManager;
ComPtr<IMMDeviceEnumerator>  g_enumerator;
UINT32                       g_resetToken = 0;

HRESULT CreateDX11Device() {
    HRESULT hr = D3D11CreateDevice(
        nullptr, D3D_DRIVER_TYPE_HARDWARE, nullptr,
        D3D11_CREATE_DEVICE_VIDEO_SUPPORT, nullptr, 0,
        D3D11_SDK_VERSION, g_device.ReleaseAndGetAddressOf(), nullptr, nullptr);
    if (FAILED(hr)) return hr;

    ComPtr<ID3D10Multithread> multithread;
    if (SUCCEEDED(g_device.As(&multithread))) {
        multithread->SetMultithreadProtected(TRUE);
    }
    return S_OK;
}

} // namespace

HRESULT Initialize() {
    if (g_initialized.load()) return OP_E_ALREADY_INITIALIZED;

    HRESULT hr = CoInitializeEx(nullptr, COINIT_MULTITHREADED);
    const bool comOwned = SUCCEEDED(hr);           // false if RPC_E_CHANGED_MODE
    if (hr == RPC_E_CHANGED_MODE) hr = S_OK;       // caller picked a different mode; fine
    if (FAILED(hr)) return hr;
    g_comInitialized.store(comOwned);

    hr = MFStartup(MF_VERSION);
    if (FAILED(hr)) {
        if (comOwned) CoUninitialize();
        g_comInitialized.store(false);
        return hr;
    }

    hr = CreateDX11Device();
    if (FAILED(hr)) {
        MFShutdown();
        if (comOwned) CoUninitialize();
        g_comInitialized.store(false);
        return hr;
    }

    hr = MFCreateDXGIDeviceManager(&g_resetToken, g_dxgiManager.ReleaseAndGetAddressOf());
    if (SUCCEEDED(hr))
        hr = g_dxgiManager->ResetDevice(g_device.Get(), g_resetToken);

    if (SUCCEEDED(hr)) {
        hr = CoCreateInstance(__uuidof(MMDeviceEnumerator), nullptr, CLSCTX_ALL,
                              IID_PPV_ARGS(g_enumerator.ReleaseAndGetAddressOf()));
    }

    if (FAILED(hr)) {
        g_enumerator.Reset();
        g_dxgiManager.Reset();
        g_device.Reset();
        MFShutdown();
        if (comOwned) CoUninitialize();
        g_comInitialized.store(false);
        return hr;
    }

    g_initialized.store(true);
    return S_OK;
}

HRESULT Shutdown() {
    // No guard on instance count: this is called from a JVM shutdown hook
    // after all Kotlin-side player instances have been told to dispose. MF
    // worker threads MUST be stopped before the DLL is unloaded, or Windows
    // crashes inside KERNELBASE on shutdown (exit 0x87A).
    if (!g_initialized.load()) return S_OK;

    g_enumerator.Reset();
    g_dxgiManager.Reset();
    g_device.Reset();

    HRESULT hr = MFShutdown();
    g_initialized.store(false);

    if (g_comInitialized.load()) {
        CoUninitialize();
        g_comInitialized.store(false);
    }
    return hr;
}

ID3D11Device*         GetD3DDevice()         { return g_device.Get(); }
IMFDXGIDeviceManager* GetDXGIDeviceManager() { return g_dxgiManager.Get(); }
IMMDeviceEnumerator*  GetDeviceEnumerator()  { return g_enumerator.Get(); }

void IncrementInstanceCount() { ++g_instanceCount; }
void DecrementInstanceCount() { --g_instanceCount; }
bool IsInitialized()          { return g_initialized.load(); }
int  GetInstanceCount()       { return g_instanceCount.load(); }

} // namespace MediaFoundation
