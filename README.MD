# 🎥 Compose Media Player

<img src="/assets/banner.jpg" style="border-radius: 10px;"/>

[![Maven Central](https://img.shields.io/maven-central/v/io.github.kdroidfilter/composemediaplayer.svg)](https://search.maven.org/artifact/io.github.kdroidfilter/composemediaplayer)
[![Maven Central - Audio](https://img.shields.io/maven-central/v/io.github.kdroidfilter/composemediaplayer-audio.svg?label=audio)](https://search.maven.org/artifact/io.github.kdroidfilter/composemediaplayer-audio)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](https://opensource.org/licenses/MIT)
[![Kotlin](https://img.shields.io/badge/kotlin-multiplatform-blue.svg?logo=kotlin)](https://kotlinlang.org/docs/multiplatform.html)
[![Platforms](https://img.shields.io/badge/platforms-Android%20|%20iOS%20|%20macOS%20JVM%20|%20Windows%20JVM%20|%20Linux%20JVM%20|%20Web-lightgrey.svg)](https://github.com/kdroidfilter/ComposeMediaPlayer)
[![Contributions welcome](https://img.shields.io/badge/contributions-welcome-brightgreen.svg?style=flat)](https://github.com/kdroidfilter/ComposeMediaPlayer/issues)
[![Documentation](https://img.shields.io/badge/docs-Dokka-blue.svg)](https://kdroidfilter.github.io/ComposeMediaPlayer/)
[![GitHub Workflow Status](https://img.shields.io/github/actions/workflow/status/kdroidfilter/ComposeMediaPlayer/publish-documentation-and-sample.yml?branch=master)](https://github.com/kdroidfilter/ComposeMediaPlayer/actions/workflows/publish-documentation-and-sample.yml)
[![GitHub last commit](https://img.shields.io/github/last-commit/kdroidfilter/ComposeMediaPlayer)](https://github.com/kdroidfilter/ComposeMediaPlayer/commits/master)

Compose Media Player is a media playback library for Compose Multiplatform. It provides two modules with distinct APIs:

- **Video player**: `composemediaplayer` for full video playback with Compose UI, subtitles, metadata, and platform-native backends.
- **Audio player**: `composemediaplayer-audio` for lightweight audio-only playback (play, pause, stop, seek, volume).

## Table of Contents
- [Documentation](#documentation)
- [Installation](#installation)
- [Live Demo](#live-demo)
- [Roadmap](#roadmap)
- [License](#license)
- [Applications Using This Library](#applications-using-this-library)
- [Star History](#star-history)

## Documentation

- Video player docs: [README_VIDEO.MD](README_VIDEO.MD)
- Audio player docs: [README_AUDIO.MD](README_AUDIO.MD)

## Installation

Video player:

```kotlin
dependencies {
    implementation("io.github.kdroidfilter:composemediaplayer:<version>")
}
```

Audio player:

```kotlin
dependencies {
    implementation("io.github.kdroidfilter:composemediaplayer-audio:<version>")
}
```

## Live Demo

Try the online demo here: [Live Demo](https://kdroidfilter.github.io/ComposeMediaPlayer/sample/)

## Roadmap

- **Audio Player Enhancements**: Add metadata surfacing, playback rate on JVM, and richer stream diagnostics.
- **Player with Separate Audio and Video Streams**: Add functionality to support different audio and video streams for advanced playback scenarios.

## License

Compose Media Player is licensed under the MIT License. See [LICENSE](LICENSE) for details.

## Applications Using This Library

- [Pushscroll](https://pushscroll.com) - Pushscroll: Screen-Time Gym
- [Pixelix](https://github.com/ghostbyte-dev/pixelix) - Pixelfed client for Android and iOS

If you're using this library in your project, please let us know and we'll add it to this list!

## Star History

[![Star History Chart](https://api.star-history.com/svg?repos=kdroidfilter/ComposeMediaPlayer&type=Date)](https://www.star-history.com/#kdroidfilter/ComposeMediaPlayer&Date)
