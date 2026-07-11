# Compose Media Player Audio

[![Maven Central - Audio](https://img.shields.io/maven-central/v/io.github.kdroidfilter/composemediaplayer-audio.svg?label=audio)](https://search.maven.org/artifact/io.github.kdroidfilter/composemediaplayer-audio)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](https://opensource.org/licenses/MIT)
[![Kotlin](https://img.shields.io/badge/kotlin-multiplatform-blue.svg?logo=kotlin)](https://kotlinlang.org/docs/multiplatform.html)
[![Platforms](https://img.shields.io/badge/platforms-Android%20|%20iOS%20|%20macOS%20JVM%20|%20Windows%20JVM%20|%20Linux%20JVM%20|%20Web-lightgrey.svg)](https://github.com/kdroidfilter/ComposeMediaPlayer)

Compose Media Player audio module is a lightweight audio-only player for Compose Multiplatform. It supports Android, iOS, JVM Desktop (macOS/Windows/Linux), and Web (Wasm).

For the video module, see [README_VIDEO.MD](README_VIDEO.MD).

## Table of Contents
- [Features](#features)
- [Installation](#installation)
- [Usage](#usage)
- [State and Controls](#state-and-controls)
- [Error Handling](#error-handling)
- [Platform Notes](#platform-notes)
- [Audio Format Support](#audio-format-support)
- [Sample](#sample)

## Features

- **Multiplatform Support**: Works on Android, iOS, JVM Desktop, and Web (Wasm).
- **Local and Remote Playback**: Play files from disk or stream from HTTP(S) URLs.
- **Simple Controls**: Play, pause, stop, seek, and volume.
- **Live State Helper**: `rememberAudioPlayerLiveState()` exposes state, position, duration, and volume.
- **Error Callbacks**: Hook into playback errors via `setOnErrorListener`.

## Installation

Add the audio module dependency in your `build.gradle.kts`:

```kotlin
dependencies {
    implementation("io.github.kdroidfilter:composemediaplayer-audio:<version>")
}
```

## Usage

```kotlin
@Composable
fun AudioSample() {
    val audioState = rememberAudioPlayerLiveState()
    val url = "https://example.com/stream.mp3"

    Row {
        Button(onClick = { audioState.player.play(url) }) { Text("Play") }
        Button(onClick = { audioState.player.pause() }) { Text("Pause") }
        Button(onClick = { audioState.player.stop() }) { Text("Stop") }
    }
}
```

For local files, pass a file path or file URI:

```kotlin
val player = AudioPlayer()
player.play("/path/to/file.mp3")
// or: player.play("file:///path/to/file.mp3")
```

If you are using FileKit, you can pass `PlatformFile.getUri()`:

```kotlin
val file = FileKit.openFilePicker(type = FileKitType.File("mp3", "wav"))
file?.let { audioState.player.play(it.getUri()) }
```

## State and Controls

- `currentPosition()` and `currentDuration()` return milliseconds. Duration may be null for live streams.
- `currentPlayerState()` returns `AudioPlayerState.PLAYING`, `PAUSED`, `BUFFERING`, or `IDLE`.
- `seekTo(timeMs)` works only when the source is seekable.
- `setVolume(volume)` expects a value between 0.0 and 1.0.
- `setRate(rate)` is supported on Android/iOS/Web; on JVM it is currently a no-op.

## Error Handling

```kotlin
audioState.player.setOnErrorListener(object : ErrorListener {
    override fun onError(message: String?) {
        println("Audio error: ${message ?: "Unknown error"}")
    }
})
```

## Platform Notes

- Android uses Media3, iOS uses AVFoundation, Web uses HTML5 Audio, JVM uses Rodio.
- Seeking and duration availability depend on the source (stream vs file).

## Audio Format Support

Audio format support depends on OS/browser decoders. The tables below show typical baseline support per backend.
Legend: Yes supported, No not supported, Partial device/browser-dependent.

### JVM (Rodio)

| Format | Local | HTTP | HLS | Seeking |
|--------|-------|------|-----|---------|
| MP3    | Yes   | Yes  | Yes | Yes     |
| AAC    | Yes   | Yes  | Yes | Yes     |
| FLAC   | Yes   | Yes  | No  | Yes     |
| OGG    | Yes   | Yes  | No  | Yes     |
| WAV    | Yes   | Yes  | No  | Yes     |

### Android (Media3)

| Format | Local | HTTP | HLS | Seeking |
|--------|-------|------|-----|---------|
| MP3    | Yes   | Yes  | Yes | Yes     |
| AAC    | Yes   | Yes  | Yes | Yes     |
| FLAC   | Yes   | Yes  | No  | Yes     |
| OGG    | Yes   | Yes  | No  | Yes     |
| WAV    | Yes   | Yes  | No  | Yes     |

### iOS (AVFoundation)

| Format | Local | HTTP | HLS | Seeking |
|--------|-------|------|-----|---------|
| MP3    | Yes   | Yes  | Partial | Yes |
| AAC    | Yes   | Yes  | Yes | Yes     |
| FLAC   | Partial | Partial | No | Partial |
| OGG    | No    | No   | No  | No      |
| WAV    | Yes   | Yes  | No  | Yes     |

### Web (HTML5 Audio)

| Format | Local | HTTP | HLS | Seeking |
|--------|-------|------|-----|---------|
| MP3    | Yes   | Yes  | Partial | Yes |
| AAC    | Yes   | Yes  | Partial | Yes |
| FLAC   | Partial | Partial | No | Partial |
| OGG    | Partial | Partial | No | Partial |
| WAV    | Yes   | Yes  | No  | Yes     |

Notes:
- Web "Local" assumes a `blob:` or `file:` URL from a file picker; browser security rules apply.
- Web HLS is supported only in Safari unless you provide a custom HLS loader.
- iOS FLAC support varies by OS version and device.

## Sample

See `sample/composeApp/src/commonMain/kotlin/sample/app/AudioPlayerScreen.kt` for a complete example.
