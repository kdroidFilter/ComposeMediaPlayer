package io.github.kdroidfilter.composemediaplayer.windows

import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.PointerType
import com.sun.jna.WString
import com.sun.jna.ptr.IntByReference
import com.sun.jna.ptr.PointerByReference
import com.sun.jna.win32.StdCallLibrary

/**
 * Interface JNA pour la DLL OffscreenPlayer.dll
 */
internal interface MediaFoundationLib : StdCallLibrary {

    companion object {
        val INSTANCE: MediaFoundationLib by lazy {
            Native.load("OffscreenPlayer", MediaFoundationLib::class.java)
        }
    }

    // 1) Init
    fun InitMediaFoundation(): Int

    // 2) Ouvrir
    fun OpenMedia(url: WString): Int

    // 3) Lire frame
    fun ReadVideoFrame(pData: PointerByReference, pDataSize: IntByReference): Int

    // 4) Unlock frame
    fun UnlockVideoFrame(): Int

    // 5) Close
    fun CloseMedia()

    // 6) Contrôles
    fun IsEOF(): Boolean
    fun StartAudioPlayback(): Int
    fun StopAudioPlayback(): Int

    // 7) Taille
    fun GetVideoSize(pWidth: IntByReference, pHeight: IntByReference)
}

// (Optionnel) Petit wrapper si besoin
class BytePointer(p: Pointer?) : PointerType(p)
