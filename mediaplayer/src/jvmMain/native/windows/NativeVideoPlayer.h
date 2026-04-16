// NativeVideoPlayer.h
#pragma once
#ifndef NATIVE_VIDEO_PLAYER_H
#define NATIVE_VIDEO_PLAYER_H

#include "ErrorCodes.h"
#include <windows.h>
#include <mfapi.h>
#include <mfidl.h>
#include <mfreadwrite.h>
#include <audioclient.h>
#include <mmdeviceapi.h>

// Native API version — bump when the exported API changes.
#define NATIVE_VIDEO_PLAYER_VERSION 2

typedef struct VideoMetadata {
    wchar_t title[256];
    LONGLONG duration;
    UINT32 width;
    UINT32 height;
    LONGLONG bitrate;
    float frameRate;
    wchar_t mimeType[64];
    UINT32 audioChannels;
    UINT32 audioSampleRate;
    BOOL hasTitle;
    BOOL hasDuration;
    BOOL hasWidth;
    BOOL hasHeight;
    BOOL hasBitrate;
    BOOL hasFrameRate;
    BOOL hasMimeType;
    BOOL hasAudioChannels;
    BOOL hasAudioSampleRate;
} VideoMetadata;

#ifdef _WIN32
  #ifdef NATIVEVIDEOPLAYER_EXPORTS
    #define NATIVEVIDEOPLAYER_API __declspec(dllexport)
  #else
    #define NATIVEVIDEOPLAYER_API __declspec(dllimport)
  #endif
#else
  #define NATIVEVIDEOPLAYER_API
#endif

struct VideoPlayerInstance;

#ifdef __cplusplus
extern "C" {
#endif

NATIVEVIDEOPLAYER_API int     GetNativeVersion();
NATIVEVIDEOPLAYER_API HRESULT InitMediaFoundation();
NATIVEVIDEOPLAYER_API HRESULT CreateVideoPlayerInstance(VideoPlayerInstance** ppInstance);
NATIVEVIDEOPLAYER_API void    DestroyVideoPlayerInstance(VideoPlayerInstance* pInstance);
NATIVEVIDEOPLAYER_API HRESULT OpenMedia(VideoPlayerInstance* pInstance, const wchar_t* url, BOOL startPlayback = TRUE);
NATIVEVIDEOPLAYER_API HRESULT ReadVideoFrame(VideoPlayerInstance* pInstance, BYTE** pData, DWORD* pDataSize);
NATIVEVIDEOPLAYER_API HRESULT UnlockVideoFrame(VideoPlayerInstance* pInstance);
NATIVEVIDEOPLAYER_API HRESULT ReadVideoFrameInto(VideoPlayerInstance* pInstance,
                                                  BYTE* pDst, DWORD dstRowBytes, DWORD dstCapacity,
                                                  LONGLONG* pTimestamp);
NATIVEVIDEOPLAYER_API void    CloseMedia(VideoPlayerInstance* pInstance);
NATIVEVIDEOPLAYER_API BOOL    IsEOF(const VideoPlayerInstance* pInstance);
NATIVEVIDEOPLAYER_API void    GetVideoSize(const VideoPlayerInstance* pInstance, UINT32* pWidth, UINT32* pHeight);
NATIVEVIDEOPLAYER_API HRESULT GetVideoFrameRate(const VideoPlayerInstance* pInstance, UINT* pNum, UINT* pDenom);
NATIVEVIDEOPLAYER_API HRESULT SeekMedia(VideoPlayerInstance* pInstance, LONGLONG llPosition);
NATIVEVIDEOPLAYER_API HRESULT GetMediaDuration(const VideoPlayerInstance* pInstance, LONGLONG* pDuration);
NATIVEVIDEOPLAYER_API HRESULT GetMediaPosition(const VideoPlayerInstance* pInstance, LONGLONG* pPosition);
NATIVEVIDEOPLAYER_API HRESULT SetPlaybackState(VideoPlayerInstance* pInstance, BOOL bPlaying, BOOL bStop = FALSE);
NATIVEVIDEOPLAYER_API HRESULT ShutdownMediaFoundation();
NATIVEVIDEOPLAYER_API HRESULT SetAudioVolume(VideoPlayerInstance* pInstance, float volume);
NATIVEVIDEOPLAYER_API HRESULT GetAudioVolume(const VideoPlayerInstance* pInstance, float* volume);
NATIVEVIDEOPLAYER_API HRESULT SetPlaybackSpeed(VideoPlayerInstance* pInstance, float speed);
NATIVEVIDEOPLAYER_API HRESULT GetPlaybackSpeed(const VideoPlayerInstance* pInstance, float* pSpeed);
NATIVEVIDEOPLAYER_API HRESULT GetVideoMetadata(const VideoPlayerInstance* pInstance, VideoMetadata* pMetadata);
NATIVEVIDEOPLAYER_API HRESULT SetOutputSize(VideoPlayerInstance* pInstance, UINT32 targetWidth, UINT32 targetHeight);

#ifdef __cplusplus
}
#endif

#endif // NATIVE_VIDEO_PLAYER_H
