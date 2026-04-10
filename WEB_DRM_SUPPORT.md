# Web DRM Support (Widevine)

This adds Widevine DRM support for web platforms (WASM/JS) using dash.js for DASH manifest parsing and EME for license acquisition.

## Features

- Widevine DRM playback on web browsers (Chrome, Firefox, Edge)
- DASH/MPD manifest support via dash.js
- Custom license headers for authentication
- Sample app with DRM testing UI

## Installation

### 1. Add dash.js to your HTML

In your `src/webMain/resources/index.html`, add the dash.js script before your app:

```html
<head>
    <!-- dash.js for DASH/DRM playback -->
    <script src="https://cdn.dashjs.org/latest/dash.all.min.js"></script>
    <!-- DRM helper module -->
    <script src="drm-helper.js"></script>
</head>
```

### 2. Copy drm-helper.js

Copy `mediaplayer/src/webMain/resources/drm-helper.js` to your project's `src/webMain/resources/` folder.

### 3. Enable HTTPS (Required for DRM)

EME requires a secure context. Create `webpack.config.d/https.config.js`:

```javascript
config.devServer = config.devServer || {};
config.devServer.server = 'https';
config.devServer.host = '0.0.0.0';
```

## Usage

### Basic Widevine Playback

```kotlin
import io.github.kdroidfilter.composemediaplayer.DrmConfiguration
import io.github.kdroidfilter.composemediaplayer.DrmType
import io.github.kdroidfilter.composemediaplayer.rememberVideoPlayerState

@Composable
fun DrmVideoPlayer() {
    val playerState = rememberVideoPlayerState()
    
    // Create DRM configuration
    val drmConfig = DrmConfiguration(
        drmType = DrmType.WIDEVINE,
        licenseUrl = "https://your-license-server.com/acquire",
        licenseHeaders = mapOf(
            "Authorization" to "Bearer your-token"
        )
    )
    
    // Open DRM-protected content
    LaunchedEffect(Unit) {
        playerState.openUri(
            uri = "https://example.com/content.mpd",
            drmConfiguration = drmConfig
        )
    }
    
    VideoPlayerSurface(
        playerState = playerState,
        modifier = Modifier.fillMaxSize()
    )
}
```

### With Custom Headers (AxDRM Example)

```kotlin
val drmConfig = DrmConfiguration(
    drmType = DrmType.WIDEVINE,
    licenseUrl = "https://drm-widevine-licensing.axtest.net/AcquireLicense",
    licenseHeaders = mapOf(
        "X-AxDRM-Message" to "eyJhbGciOiJIUzI1NiIs..."  // Your JWT token
    )
)

playerState.openUri(
    uri = "https://media.axprod.net/TestVectors/Cmaf/protected_1080p_h264_cbcs/manifest.mpd",
    drmConfiguration = drmConfig
)
```

### Non-DRM Playback (Unchanged)

For regular content without DRM, use the standard API:

```kotlin
playerState.openUri("https://example.com/video.mp4")
```

## DrmConfiguration Options

| Parameter | Type | Description |
|-----------|------|-------------|
| `drmType` | `DrmType` | DRM system: `WIDEVINE`, `PLAYREADY`, or `CLEARKEY` |
| `licenseUrl` | `String` | License server URL |
| `licenseHeaders` | `Map<String, String>` | Custom HTTP headers for license requests |

## Platform Support

| Platform | Status |
|----------|--------|
| Web (WASM/JS) | ✅ Widevine supported |
| Android | 🔜 Planned (ExoPlayer DRM) |
| iOS | 🔜 Planned (FairPlay) |
| Desktop | 🔜 Planned |

## Troubleshooting

### "No supported version of EME detected"
- Make sure you're accessing the page via HTTPS (or localhost)
- Check that the browser supports Widevine

### License request fails
- Verify the license URL is correct
- Check that custom headers are properly formatted
- Ensure CORS is configured on the license server

### Video doesn't play
- Confirm the manifest URL is a valid DASH/MPD file
- Check browser console for `[DRM]` log messages
- Verify the content is encrypted with Widevine

## Test Streams

AxDRM test content for development:

```
URL: https://media.axprod.net/TestVectors/Cmaf/protected_1080p_h264_cbcs/manifest.mpd
License: https://drm-widevine-licensing.axtest.net/AcquireLicense
```
