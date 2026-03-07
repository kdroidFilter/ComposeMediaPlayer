// NativeVideoPlayer.h
#pragma once
#ifndef NATIVE_VIDEO_PLAYER_H
#define NATIVE_VIDEO_PLAYER_H

#include <windows.h>
#include <mfapi.h>
#include <mfidl.h>
#include <mfreadwrite.h>
#include <audioclient.h>
#include <mmdeviceapi.h>

// Structure to hold video metadata
typedef struct VideoMetadata {
    wchar_t title[256];          // Title of the video (empty if not available)
    LONGLONG duration;           // Duration in 100-ns units
    UINT32 width;                // Width in pixels
    UINT32 height;               // Height in pixels
    LONGLONG bitrate;            // Bitrate in bits per second
    float frameRate;             // Frame rate in frames per second
    wchar_t mimeType[64];        // MIME type of the video
    UINT32 audioChannels;        // Number of audio channels
    UINT32 audioSampleRate;      // Audio sample rate in Hz
    BOOL hasTitle;               // TRUE if title is available
    BOOL hasDuration;            // TRUE if duration is available
    BOOL hasWidth;               // TRUE if width is available
    BOOL hasHeight;              // TRUE if height is available
    BOOL hasBitrate;             // TRUE if bitrate is available
    BOOL hasFrameRate;           // TRUE if frame rate is available
    BOOL hasMimeType;            // TRUE if MIME type is available
    BOOL hasAudioChannels;       // TRUE if audio channels is available
    BOOL hasAudioSampleRate;     // TRUE if audio sample rate is available
} VideoMetadata;

// Macro d'exportation pour la DLL Windows
#ifdef _WIN32
#ifdef NATIVEVIDEOPLAYER_EXPORTS
#define NATIVEVIDEOPLAYER_API __declspec(dllexport)
#else
#define NATIVEVIDEOPLAYER_API __declspec(dllimport)
#endif
#else
#define NATIVEVIDEOPLAYER_API
#endif

// Codes d'erreur personnalisés
#define OP_E_NOT_INITIALIZED     ((HRESULT)0x80000001L)
#define OP_E_ALREADY_INITIALIZED ((HRESULT)0x80000002L)
#define OP_E_INVALID_PARAMETER   ((HRESULT)0x80000003L)

// Structure pour encapsuler l'état d'une instance de lecteur vidéo
struct VideoPlayerInstance;

#ifdef __cplusplus
extern "C" {
#endif

// ====================================================================
// Fonctions exportées pour la gestion des instances et la lecture multimédia
// ====================================================================

/**
 * @brief Initialise Media Foundation, Direct3D11 et le gestionnaire DXGI (une seule fois pour toutes les instances).
 * @return S_OK en cas de succès, ou un code d'erreur.
 */
NATIVEVIDEOPLAYER_API HRESULT InitMediaFoundation();

/**
 * @brief Crée une nouvelle instance de lecteur vidéo.
 * @param ppInstance Pointeur pour recevoir le handle de l'instance créée.
 * @return S_OK en cas de succès, ou un code d'erreur.
 */
NATIVEVIDEOPLAYER_API HRESULT CreateVideoPlayerInstance(VideoPlayerInstance** ppInstance);

/**
 * @brief Détruit une instance de lecteur vidéo et libère ses ressources.
 * @param pInstance Handle de l'instance à détruire.
 */
NATIVEVIDEOPLAYER_API void DestroyVideoPlayerInstance(VideoPlayerInstance* pInstance);

/**
 * @brief Ouvre un média (fichier ou URL) et prépare le décodage avec accélération matérielle pour une instance spécifique.
 * @param pInstance Handle de l'instance.
 * @param url Chemin ou URL du média (chaîne large).
 * @param startPlayback TRUE pour démarrer la lecture immédiatement, FALSE pour rester en pause.
 * @return S_OK en cas de succès, ou un code d'erreur.
 */
NATIVEVIDEOPLAYER_API HRESULT OpenMedia(VideoPlayerInstance* pInstance, const wchar_t* url, BOOL startPlayback = TRUE);

/**
 * @brief Lit la prochaine frame vidéo en format RGB32 pour une instance spécifique.
 * @param pInstance Handle de l'instance.
 * @param pData Reçoit un pointeur sur les données de la frame (à ne pas libérer).
 * @param pDataSize Reçoit la taille en octets du tampon.
 * @return S_OK si une frame est lue, S_FALSE en fin de flux, ou un code d'erreur.
 */
NATIVEVIDEOPLAYER_API HRESULT ReadVideoFrame(VideoPlayerInstance* pInstance, BYTE** pData, DWORD* pDataSize);

/**
 * @brief Déverrouille le tampon de la frame vidéo précédemment verrouillé pour une instance spécifique.
 * @param pInstance Handle de l'instance.
 * @return S_OK en cas de succès.
 */
NATIVEVIDEOPLAYER_API HRESULT UnlockVideoFrame(VideoPlayerInstance* pInstance);

/*
 * Reads the next video frame and copies it into a destination buffer.
 * pTimestamp receives the 100ns timestamp when available.
 */
NATIVEVIDEOPLAYER_API HRESULT ReadVideoFrameInto(
    VideoPlayerInstance* pInstance,
    BYTE* pDst,
    DWORD dstRowBytes,
    DWORD dstCapacity,
    LONGLONG* pTimestamp);

/**
 * @brief Ferme le média et libère les ressources associées pour une instance spécifique.
 * @param pInstance Handle de l'instance.
 */
NATIVEVIDEOPLAYER_API void CloseMedia(VideoPlayerInstance* pInstance);

/**
 * @brief Indique si la fin du flux média a été atteinte pour une instance spécifique.
 * @param pInstance Handle de l'instance.
 * @return TRUE si fin de flux, FALSE sinon.
 */
NATIVEVIDEOPLAYER_API BOOL IsEOF(const VideoPlayerInstance* pInstance);

/**
 * @brief Récupère les dimensions de la vidéo pour une instance spécifique.
 * @param pInstance Handle de l'instance.
 * @param pWidth Pointeur pour recevoir la largeur en pixels.
 * @param pHeight Pointeur pour recevoir la hauteur en pixels.
 */
NATIVEVIDEOPLAYER_API void GetVideoSize(const VideoPlayerInstance* pInstance, UINT32* pWidth, UINT32* pHeight);

/**
 * @brief Récupère le taux de rafraîchissement (frame rate) de la vidéo pour une instance spécifique.
 * @param pInstance Handle de l'instance.
 * @param pNum Pointeur pour recevoir le numérateur.
 * @param pDenom Pointeur pour recevoir le dénominateur.
 * @return S_OK en cas de succès, ou un code d'erreur.
 */
NATIVEVIDEOPLAYER_API HRESULT GetVideoFrameRate(const VideoPlayerInstance* pInstance, UINT* pNum, UINT* pDenom);

/**
 * @brief Recherche une position spécifique dans le média pour une instance spécifique.
 * @param pInstance Handle de l'instance.
 * @param llPosition Position (en 100-ns) à atteindre.
 * @return S_OK en cas de succès, ou un code d'erreur.
 */
NATIVEVIDEOPLAYER_API HRESULT SeekMedia(VideoPlayerInstance* pInstance, LONGLONG llPosition);

/**
 * @brief Obtient la durée totale du média pour une instance spécifique.
 * @param pInstance Handle de l'instance.
 * @param pDuration Pointeur pour recevoir la durée (en 100-ns).
 * @return S_OK en cas de succès, ou un code d'erreur.
 */
NATIVEVIDEOPLAYER_API HRESULT GetMediaDuration(const VideoPlayerInstance* pInstance, LONGLONG* pDuration);

/**
 * @brief Obtient la position de lecture courante pour une instance spécifique.
 * @param pInstance Handle de l'instance.
 * @param pPosition Pointeur pour recevoir la position (en 100-ns).
 * @return S_OK en cas de succès, ou un code d'erreur.
 */
NATIVEVIDEOPLAYER_API HRESULT GetMediaPosition(const VideoPlayerInstance* pInstance, LONGLONG* pPosition);

/**
 * @brief Définit l'état de lecture (lecture ou pause) pour une instance spécifique.
 * @param pInstance Handle de l'instance.
 * @param bPlaying TRUE pour lecture, FALSE pour pause.
 * @param bStop TRUE si c'est un arrêt complet, FALSE si c'est simplement une pause.
 * @return S_OK en cas de succès, ou un code d'erreur.
 */
NATIVEVIDEOPLAYER_API HRESULT SetPlaybackState(VideoPlayerInstance* pInstance, BOOL bPlaying, BOOL bStop = FALSE);

/**
 * @brief Arrête Media Foundation et libère les ressources globales (après destruction de toutes les instances).
 * @return S_OK en cas de succès, ou un code d'erreur.
 */
NATIVEVIDEOPLAYER_API HRESULT ShutdownMediaFoundation();

/**
 * @brief Définit le niveau de volume audio pour une instance spécifique.
 * @param pInstance Handle de l'instance.
 * @param volume Niveau de volume (0.0 à 1.0).
 * @return S_OK en cas de succès, ou un code d'erreur.
 */
NATIVEVIDEOPLAYER_API HRESULT SetAudioVolume(VideoPlayerInstance* pInstance, float volume);

/**
 * @brief Récupère le niveau de volume audio actuel pour une instance spécifique.
 * @param pInstance Handle de l'instance.
 * @param volume Pointeur pour recevoir le niveau de volume (0.0 à 1.0).
 * @return S_OK en cas de succès, ou un code d'erreur.
 */
NATIVEVIDEOPLAYER_API HRESULT GetAudioVolume(const VideoPlayerInstance* pInstance, float* volume);

/**
 * @brief Récupère les niveaux audio pour les canaux gauche et droit pour une instance spécifique.
 * @param pInstance Handle de l'instance.
 * @param pLeftLevel Pointeur pour le niveau du canal gauche.
 * @param pRightLevel Pointeur pour le niveau du canal droit.
 * @return S_OK en cas de succès, ou un code d'erreur.
 */
NATIVEVIDEOPLAYER_API HRESULT GetAudioLevels(const VideoPlayerInstance* pInstance, float* pLeftLevel, float* pRightLevel);

/**
 * @brief Définit la vitesse de lecture pour une instance spécifique.
 * @param pInstance Handle de l'instance.
 * @param speed Vitesse de lecture (0.5 à 2.0, où 1.0 est la vitesse normale).
 * @return S_OK en cas de succès, ou un code d'erreur.
 */
NATIVEVIDEOPLAYER_API HRESULT SetPlaybackSpeed(VideoPlayerInstance* pInstance, float speed);

/**
 * @brief Récupère la vitesse de lecture actuelle pour une instance spécifique.
 * @param pInstance Handle de l'instance.
 * @param pSpeed Pointeur pour recevoir la vitesse de lecture.
 * @return S_OK en cas de succès, ou un code d'erreur.
 */
NATIVEVIDEOPLAYER_API HRESULT GetPlaybackSpeed(const VideoPlayerInstance* pInstance, float* pSpeed);

/**
 * @brief Retrieves all available metadata for the current media.
 * @param pInstance Handle to the instance.
 * @param pMetadata Pointer to receive the metadata structure.
 * @return S_OK on success, or an error code.
 */
NATIVEVIDEOPLAYER_API HRESULT GetVideoMetadata(const VideoPlayerInstance* pInstance, VideoMetadata* pMetadata);

#ifdef __cplusplus
}
#endif

#endif // NATIVE_VIDEO_PLAYER_H
