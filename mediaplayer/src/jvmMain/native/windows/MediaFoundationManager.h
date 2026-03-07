#pragma once

#include <windows.h>
#include <mfapi.h>
#include <mfidl.h>
#include <d3d11.h>
#include <mmdeviceapi.h>

// Error code definitions
#define OP_E_NOT_INITIALIZED     ((HRESULT)0x80000001L)
#define OP_E_ALREADY_INITIALIZED ((HRESULT)0x80000002L)
#define OP_E_INVALID_PARAMETER   ((HRESULT)0x80000003L)

namespace MediaFoundation {

/**
 * @brief Initializes Media Foundation, Direct3D11, and the DXGI device manager.
 * @return S_OK on success, or an error code.
 */
HRESULT Initialize();

/**
 * @brief Shuts down Media Foundation and releases global resources.
 * @return S_OK on success, or an error code.
 */
HRESULT Shutdown();

/**
 * @brief Creates a Direct3D11 device with video support.
 * @return S_OK on success, or an error code.
 */
HRESULT CreateDX11Device();

/**
 * @brief Gets the D3D11 device.
 * @return Pointer to the D3D11 device.
 */
ID3D11Device* GetD3DDevice();

/**
 * @brief Gets the DXGI device manager.
 * @return Pointer to the DXGI device manager.
 */
IMFDXGIDeviceManager* GetDXGIDeviceManager();

/**
 * @brief Gets the device enumerator for audio devices.
 * @return Pointer to the device enumerator.
 */
IMMDeviceEnumerator* GetDeviceEnumerator();

/**
 * @brief Increments the instance count.
 */
void IncrementInstanceCount();

/**
 * @brief Decrements the instance count.
 */
void DecrementInstanceCount();

/**
 * @brief Checks if Media Foundation is initialized.
 * @return True if initialized, false otherwise.
 */
bool IsInitialized();

/**
 * @brief Gets the current instance count.
 * @return The number of active instances.
 */
int GetInstanceCount();

} // namespace MediaFoundation
