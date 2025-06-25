# 🎥 Compose Media Player

<img src="/assets/banner.jpg" style="border-radius: 10px;"/>

[![Maven Central](https://img.shields.io/maven-central/v/io.github.kdroidfilter/composemediaplayer.svg)](https://search.maven.org/artifact/io.github.kdroidfilter/composemediaplayer)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](https://opensource.org/licenses/MIT)
[![Kotlin](https://img.shields.io/badge/kotlin-multiplatform-blue.svg?logo=kotlin)](https://kotlinlang.org/docs/multiplatform.html)
[![Platforms](https://img.shields.io/badge/platforms-Android%20|%20iOS%20|%20macOS%20JVM%20|%20Windows%20JVM%20|%20Linux%20JVM%20|%20Web-lightgrey.svg)](https://github.com/kdroidfilter/ComposeMediaPlayer)
[![Contributions welcome](https://img.shields.io/badge/contributions-welcome-brightgreen.svg?style=flat)](https://github.com/kdroidfilter/ComposeMediaPlayer/issues)
[![Documentation](https://img.shields.io/badge/docs-Dokka-blue.svg)](https://kdroidfilter.github.io/ComposeMediaPlayer/)
[![GitHub Workflow Status](https://img.shields.io/github/actions/workflow/status/kdroidfilter/ComposeMediaPlayer/publish-documentation-and-sample.yml?branch=master)](https://github.com/kdroidfilter/ComposeMediaPlayer/actions/workflows/publish-documentation-and-sample.yml)
[![GitHub last commit](https://img.shields.io/github/last-commit/kdroidfilter/ComposeMediaPlayer)](https://github.com/kdroidfilter/ComposeMediaPlayer/commits/master)

**Compose Media Player** is a video player library designed for Compose Multiplatform, supporting multiple platforms including Android, macOS, Windows, and Linux. It is the first fully functional multiplatform video player for Compose for Desktop that requires no additional software installations. The library leverages:

- **GStreamer** for Linux
- **Media Foundation** for Windows
- **AVPlayer** for macOS and iOS
- **Media3** for Android
- **HTML5 Player** for WASMJS

## Table of Contents
- [Live Demo](#-live-demo)
- [Features](#-features)
- [Supported Video Formats](#-supported-video-formats)
- [Installation](#-installation)
- [Compatibility Table](#-compatibility-table)
- [Getting Started](#-getting-started)
  - [Initialization](#initialization)
  - [Displaying the Video Surface](#displaying-the-video-surface)
    - [Content Scaling](#content-scaling)
    - [Custom Overlay UI](#custom-overlay-ui)
  - [Video Playback via URL or Local Files](#video-playback-via-url-or-local-files)
  - [Full Controls](#full-controls)
  - [Progress Indicators](#progress-indicators)
  - [Display Left and Right Volume Levels](#display-left-and-right-volume-levels)
  - [Error Handling](#error-handling)
  - [Loading Indicator](#loading-indicator)
  - [Using Subtitles](#using-subtitles)
    - [Supported Formats](#-supported-formats)
    - [Adding Subtitles from URL or Local File](#-adding-subtitles-from-url-or-local-file)
    - [Customizing Subtitle Appearance](#-customizing-subtitle-appearance)
    - [Disabling Subtitles](#-disabling-subtitles)
  - [Fullscreen Mode](#️-fullscreen-mode)
- [Metadata Support](#-metadata-support)
  - [Example Usage](#example-usage)
  - [Basic Example](#-basic-example)
- [License](#-license)
- [Roadmap](#-roadmap)
- [Applications Using This Library](#-applications-using-this-library)
- [Star History](#-star-history)

## 🚀 Live Demo

Try the online demo here : [🎥 Live Demo](https://kdroidfilter.github.io/ComposeMediaPlayer/sample/)

## ✨ Features

- **Multiplatform Support**: Works seamlessly on Android, macOS, Windows, Linux and Compose Web (Wasm).
- **File and URL Support**: Play videos from local files or directly from URLs.
- **Media Controls**: Includes play, pause, loop toggle, volume control, playback speed, loop playback and timeline slider.
- **Custom Video Player UI**: Fully customizable using Compose Multiplatform, with support for custom overlays that display even in fullscreen mode.
- **Audio Levels**: Displays left and right audio levels in real time.
- **Fullscreen Mode**: Toggle between windowed and fullscreen playback modes.
- **Error handling** Simple error handling for network or playback issues.

## ✨ Supported Video Formats
| Format | Windows                                                                                                           | Linux  | macOS & iOS                                                       | Android | WasmJS |
|--------|-------------------------------------------------------------------------------------------------------------------|-------------------|-----------------------------------------------------------------------------|-----------------|-------------------|
| **Player** | [MediaFoundation](https://learn.microsoft.com/en-us/windows/win32/medfound/microsoft-media-foundation-sdk) | [GStreamer](https://gstreamer.freedesktop.org/) | [AVPlayer](https://developer.apple.com/documentation/avfoundation/avplayer) | [Media 3](https://developer.android.com/media/media3) | [HTML5 Video](https://www.w3schools.com/html/html5_video.asp) |
| MP4 (H.264) | ✅                                                                                                                 | ✅ | ✅                                                                           | ✅ | ✅ |
| AVI | ❌                                                                                                                 | ✅ | ❌                                                                           | ❌ | ❌ |
| MKV | ❌                                                                                                                 | ✅ | ❌                                                                           | ✅ | ❌ |
| MOV | ✅                                                                                                                 | ✅ | ✅                                                                           | ❌ | ✅ |
| FLV | ❌                                                                                                                 | ✅ | ❌                                                                           | ❌ | ❌ |
| WEBM | ❌                                                                                                                 | ✅ | ❌                                                                           | ✅ | ✅ |
| WMV | ✅                                                                                                                 | ✅ | ❌                                                                           | ❌ | ❌ |
| 3GP | ✅                                                                                                                 | ✅ | ✅                                                                           | ✅ | ❌ |


## 🔧 Installation

To add Compose Media Player to your project, include the following dependency in your `build.gradle.kts` file:

```kotlin
dependencies {
    implementation("io.github.kdroidfilter:composemediaplayer:<version>")
}
```

## 📊 Compatibility Table

| Library Version | Kotlin Version |
|-----------------|----------------|
| 0.7.10          | 2.1.21         |
| 0.7.11          | 2.2.0          |

## 🚀 Getting Started

### Initialization

Before using Compose Media Player, you need to create a state for the video player using the `rememberVideoPlayerState` function:

```kotlin
val playerState = rememberVideoPlayerState()
```

### Displaying the Video Surface

After initializing the player state, you can display the surface of the video using `VideoPlayerSurface`:

```kotlin
// Video Surface
Box(
    modifier = Modifier.weight(1f).fillMaxWidth(),
    contentAlignment = Alignment.Center
) {
    VideoPlayerSurface(
        playerState = playerState,
        modifier = Modifier.fillMaxSize()
    )
}
```

#### Content Scaling

> [!WARNING]
> Content scaling support is experimental. The behavior may vary across different platforms.

You can control how the video content is scaled inside the surface using the `contentScale` parameter:

```kotlin
VideoPlayerSurface(
    playerState = playerState,
    modifier = Modifier.fillMaxSize(),
    contentScale = ContentScale.Crop // Default is ContentScale.Fit
)
```

Available content scale options:
- `ContentScale.Fit` (default): Scales the video to fit within the surface while maintaining aspect ratio
- `ContentScale.Crop`: Scales the video to fill the surface while maintaining aspect ratio, potentially cropping parts
- `ContentScale.FillBounds`: Stretches the video to fill the surface, may distort the aspect ratio
- `ContentScale.Inside`: Similar to Fit, but won't scale up if the video is smaller than the surface
- `ContentScale.None`: No scaling applied

#### Custom Overlay UI

You can add a custom overlay UI that will always be visible, even in fullscreen mode, by using the `overlay` parameter:

```kotlin
VideoPlayerSurface(
    playerState = playerState,
    modifier = Modifier.fillMaxSize()) {
        // This overlay will always be visible
        Box(modifier = Modifier.fillMaxSize()) {
            // You can customize the UI based on fullscreen state
            if (playerState.isFullscreen) {
                // Fullscreen UI
                IconButton(
                    onClick = { playerState.toggleFullscreen() },
                    modifier = Modifier.align(Alignment.TopEnd).padding(16.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.FullscreenExit,
                        contentDescription = "Exit Fullscreen",
                        tint = Color.White
                    )
                }
            } else {
                // Regular UI
                Row(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .background(Color.Black.copy(alpha = 0.5f))
                        .padding(8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    // Your custom controls here
                    IconButton(onClick = { 
                        if (playerState.isPlaying) playerState.pause() else playerState.play() 
                    }) {
                        Icon(
                            imageVector = if (playerState.isPlaying) 
                                Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = "Play/Pause",
                            tint = Color.White
                        )
                    }
                }
            }
        }
    }
```

### Video Playback via URL or Local Files

You can play a video by providing a direct URL:

```kotlin
playerState.openUri("http://example.com/video.mp4")
```

To play a local video file you can use [PlatformFile](https://filekit.mintlify.app/core/platform-file) from [FileKit](https://github.com/vinceglb/FileKit).

```kotlin
val file = FileKit.openFilePicker(type = FileKitType.Video)
file?.let { playerState.openFile(file) }
```

Check the [sample project](sample/composeApp/src/commonMain/kotlin/sample/app/App.kt) for a complete example.

### Full Controls

- **Play and Pause**:

You can detect the current playback state via `playerState.isPlaying` and configure a Play/Pause button as follows:

```kotlin
Button(onClick = {
    if (playerState.isPlaying) {
        playerState.pause()
        println("Playback paused")
    } else {
        playerState.play()
        println("Playback started")
    }
}) {
    Text(if (playerState.isPlaying) "Pause" else "Play")
}
```

- **Stop**:

```kotlin
playerState.stop()
println("Playback stopped")
```

- **Volume**:

```kotlin
playerState.volume = 0.5f // Set volume to 50%
println("Volume set to 50%")
```

- **Loop Playback**:

```kotlin
playerState.loop = true // Enable loop playback
println("Loop playback enabled")
```

- **Playback Speed**:

```kotlin
playerState.playbackSpeed = 1.5f // Set playback speed to 1.5x
println("Playback speed set to 1.5x")
```

You can adjust the playback speed between 0.5x (slower) and 2.0x (faster). The default value is 1.0x (normal speed).

### Progress Indicators

To display and control playback progress:

```kotlin
Slider(
    value = playerState.sliderPos,
    onValueChange = {
        playerState.sliderPos = it
        playerState.userDragging = true
        println("Position changed: $it")
    },
    onValueChangeFinished = {
        playerState.userDragging = false
        playerState.seekTo(playerState.sliderPos)
        println("Position finalized: ${playerState.sliderPos}")
    },
    valueRange = 0f..1000f
)
```

### Display Left and Right Volume Levels

To display audio levels:

```kotlin
println("Left level: ${playerState.leftLevel.toInt()}%, Right level: ${playerState.rightLevel.toInt()}%")
```

> [!IMPORTANT]
> This feature is not working on iOS.


### Error Handling

In case of an error, you can display it using `println`:

```kotlin
playerState.error?.let { error ->
    println("Error detected: ${error.message}")
    playerState.clearError()
}
```

### Loading Indicator

To detect if the video is buffering:

```kotlin 
if (playerState.isLoading) {
    CircularProgressIndicator()
}
````

### Using Subtitles

Compose Media Player supports adding subtitles from both URLs and local files. Subtitles are now rendered using Compose, providing a uniform appearance across all platforms.

#### 🎯 Supported Formats

The player supports both SRT and VTT subtitle formats with automatic format detection.

#### 🎯 Adding Subtitles from URL or Local File

You can add subtitles by specifying a URL:

```kotlin
val track = SubtitleTrack(
    label = "English Subtitles",
    language = "en",
    src = "https://example.com/subtitles.vtt" // Works with both .srt and .vtt files
)
playerState.selectSubtitleTrack(track)
```

#### 🎨 Customizing Subtitle Appearance

You can customize the appearance of subtitles using the following properties:

```kotlin
// Customize subtitle text style
playerState.subtitleTextStyle = TextStyle(
    color = Color.White,
    fontSize = 20.sp,
    fontWeight = FontWeight.Bold,
    textAlign = TextAlign.Center
)

// Customize subtitle background color
playerState.subtitleBackgroundColor = Color.Black.copy(alpha = 0.7f)
```

#### ❌ Disabling Subtitles

To disable subtitles:

```kotlin
playerState.disableSubtitles()
```

### 🖥️ Fullscreen Mode

> [!WARNING]
> Fullscreen support is experimental. The behavior may vary across different platforms.

You can toggle between windowed and fullscreen modes using the `toggleFullscreen()` method:

```kotlin
// Toggle fullscreen mode
playerState.toggleFullscreen()

// Check current fullscreen state
if (playerState.isFullscreen) {
    println("Player is in fullscreen mode")
} else {
    println("Player is in windowed mode")
}
```

The player doesn't display any UI by default in fullscreen mode - you need to create your own custom UI using the `overlay` parameter of `VideoPlayerSurface`. The overlay will be displayed even in fullscreen mode, and you can customize it based on the fullscreen state:

```kotlin
VideoPlayerSurface(
    playerState = playerState,
    modifier = Modifier.fillMaxSize(),
    overlay = {
        Box(modifier = Modifier.fillMaxSize()) {
            // Customize UI based on fullscreen state
            if (playerState.isFullscreen) {
                // Fullscreen UI
                // ...
            } else {
                // Regular UI
                // ...
            }
        }
    }
)
```

See the "Custom Overlay UI" section under "Displaying the Video Surface" for a complete example.

## 🔍 Metadata Support

> [!WARNING]
> Metadata support is experimental. There may be inconsistencies between platforms, and on WASM it's currently limited to width and height only.

The player can extract the following metadata:
- Title
- Duration (in milliseconds)
- Video resolution (width and height)
- Bitrate (in bits per second)
- Frame rate
- MIME type
- Audio channels
- Audio sample rate

### Example Usage

You can access video metadata through the `metadata` property of the player state:

```kotlin
// Access metadata after loading a video
playerState.openUri("http://example.com/video.mp4")

// Display metadata information
val metadata = playerState.metadata

println("Video Metadata:")
metadata.title?.let { println("Title: $it") }
metadata.duration?.let { println("Duration: ${it}ms") }
metadata.width?.let { width ->
    metadata.height?.let { height ->
        println("Resolution: ${width}x${height}")
    }
}
metadata.bitrate?.let { println("Bitrate: ${it}bps") }
metadata.frameRate?.let { println("Frame Rate: ${it}fps") }
metadata.mimeType?.let { println("MIME Type: $it") }
metadata.audioChannels?.let { println("Audio Channels: $it") }
metadata.audioSampleRate?.let { println("Audio Sample Rate: ${it}Hz") }

```


### 📋 Basic Example

Here is a minimal example of how to integrate the Compose Media Player into your Compose application with a hardcoded URL:

```kotlin
@Composable
fun App() {
    val playerState = rememberVideoPlayerState()

    MaterialTheme {
        Column(modifier = Modifier.fillMaxSize().padding(8.dp)) {

            // Video Surface
            Box(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                VideoPlayerSurface(
                    playerState = playerState,
                    modifier = Modifier.fillMaxSize()
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Playback Controls
            Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                Button(onClick = { playerState.play() }) { Text("Play") }
                Button(onClick = { playerState.pause() }) { Text("Pause") }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Open Video URL
            Button(
                onClick = {
                    val url = "http://commondatastorage.googleapis.com/gtv-videos-bucket/sample/BigBuckBunny.mp4"
                    playerState.openUri(url)
                }
            ) {
                Text("Open Video")
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Volume Control
            Text("Volume: ${(playerState.volume * 100).toInt()}%")
            Slider(
                value = playerState.volume,
                onValueChange = { playerState.volume = it },
                valueRange = 0f..1f
            )
        }
    }
}
```

## 📄 License

Compose Media Player is licensed under the MIT License. See [LICENSE](LICENSE) for details.

## 📊 Roadmap

- **Audio Player**: Introduce a standalone audio player for handling audio-only content.
- **Player with Separate Audio and Video Streams**: Add functionality to support different audio and video streams for advanced playback scenarios.

## 🚀 Applications Using This Library

- [Pixelix](https://github.com/ghostbyte-dev/pixelix) - Pixelfed client for Android and iOS

If you're using this library in your project, please let us know and we'll add it to this list!

## ⭐ Star History

[![Star History Chart](https://api.star-history.com/svg?repos=kdroidfilter/ComposeMediaPlayer&type=Date)](https://www.star-history.com/#kdroidfilter/ComposeMediaPlayer&Date)
