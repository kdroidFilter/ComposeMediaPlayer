#pragma once

#include "ErrorCodes.h"
#include <windows.h>
#include <mfapi.h>
#include <mfidl.h>
#include <d3d11.h>
#include <mmdeviceapi.h>

namespace MediaFoundation {

HRESULT Initialize();
HRESULT Shutdown();

ID3D11Device*         GetD3DDevice();
IMFDXGIDeviceManager* GetDXGIDeviceManager();
IMMDeviceEnumerator*  GetDeviceEnumerator();

void IncrementInstanceCount();
void DecrementInstanceCount();
bool IsInitialized();
int  GetInstanceCount();

} // namespace MediaFoundation
