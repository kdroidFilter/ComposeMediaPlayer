package io.github.kdroidfilter.composemediaplayer.mac

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asComposeImageBitmap
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp
import io.github.kdroidfilter.composemediaplayer.InitialPlayerState
import io.github.kdroidfilter.composemediaplayer.SubtitleTrack
import io.github.kdroidfilter.composemediaplayer.VideoMetadata
import io.github.kdroidfilter.composemediaplayer.VideoPlayerError
import io.github.kdroidfilter.composemediaplayer.VideoPlayerState
import io.github.kdroidfilter.composemediaplayer.util.TaggedLogger
import io.github.kdroidfilter.composemediaplayer.util.formatTime
import io.github.vinceglb.filekit.PlatformFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.ImageInfo
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.write

internal val macLogger = TaggedLogger("MacVideoPlayerState")

/**
 * Cadence (ms) for polling the AVPlayer clock to advance the timeline. ~200 ms keeps the slider
 * smooth without spamming the native bridge; mirrors AVPlayer's addPeriodicTimeObserver usage.
 */
private const val POSITION_POLL_INTERVAL_MS = 200L

/**
 * macOS implementation of the video player state.
 *
 * Handles media playback through a native AVFoundation player (via [MacNativeBridge]).
 *
 * The architecture intentionally mirrors the Windows implementation 1:1 so the two platforms
 * behave identically: a producer/consumer coroutine pair drives frames through a single-slot
 * drop-oldest [Channel], frames are deduplicated by content hash, triple-buffered Skia bitmaps
 * avoid tearing, and the playback position is derived from each frame's timestamp rather than a
 * separate polling loop. The one place macOS deliberately diverges is the aspect ratio: it uses
 * AVFoundation's display aspect ratio (pixel-aspect-ratio / clean-aperture corrected) so
 * anamorphic content renders correctly — see [displayAspectRatio].
 */
class MacVideoPlayerState : VideoPlayerState {
    /** Native AVFoundation player. AtomicLong allows lock-free reads from the frame hot path. */
    private val playerPtrAtomic = AtomicLong(0L)
    private val playerPtr: Long get() = playerPtrAtomic.get()

    /** Coroutine scope for all async operations. Mirrors Windows (Dispatchers.Default). */
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    /** Whether media has been loaded. */
    private var _hasMedia by mutableStateOf(false)
    override val hasMedia get() = _hasMedia

    /** Whether media is currently playing. */
    private var _isPlaying by mutableStateOf(false)
    override val isPlaying get() = _isPlaying

    /** Whether the user has intentionally paused the video. */
    private var userPaused = false

    /** Deferred completed when the native player has been created. */
    private val initReady = CompletableDeferred<Unit>()

    /** Flag to track if the player is being disposed. */
    private val isDisposing = AtomicBoolean(false)

    /** Current volume level (0.0 to 1.0). */
    private var _volume by mutableStateOf(1f)
    override var volume: Float
        get() = _volume
        set(value) {
            val newVolume = value.coerceIn(0f, 1f)
            if (_volume != newVolume) {
                _volume = newVolume
                scope.launch {
                    mediaOperationMutex.withLock {
                        playerPtr.takeIf { it != 0L }?.let { ptr ->
                            try {
                                MacNativeBridge.nSetVolume(ptr, newVolume)
                            } catch (e: Exception) {
                                if (e is CancellationException) throw e
                                setError("Error updating volume: ${e.message}")
                            }
                        }
                    }
                }
            }
        }

    private var _currentTime by mutableStateOf(0.0)
    private var _duration by mutableStateOf(0.0)
    private var _progress by mutableStateOf(0f)
    override var sliderPos: Float
        get() = _progress * 1000f
        set(value) {
            _progress = (value / 1000f).coerceIn(0f, 1f)
        }
    private var _userDragging by mutableStateOf(false)
    override var userDragging: Boolean
        get() = _userDragging
        set(value) {
            _userDragging = value
        }
    private var _loop by mutableStateOf(false)
    override var loop: Boolean
        get() = _loop
        set(value) {
            _loop = value
        }

    override var onPlaybackEnded: (() -> Unit)? = null
    override var onRestart: (() -> Unit)? = null

    private var _playbackSpeed by mutableStateOf(1.0f)
    override var playbackSpeed: Float
        get() = _playbackSpeed
        set(value) {
            val newSpeed = value.coerceIn(VideoPlayerState.MIN_PLAYBACK_SPEED, VideoPlayerState.MAX_PLAYBACK_SPEED)
            if (_playbackSpeed != newSpeed) {
                _playbackSpeed = newSpeed
                scope.launch {
                    mediaOperationMutex.withLock {
                        playerPtr.takeIf { it != 0L }?.let { ptr ->
                            try {
                                MacNativeBridge.nSetPlaybackSpeed(ptr, newSpeed)
                            } catch (e: Exception) {
                                if (e is CancellationException) throw e
                                setError("Error updating playback speed: ${e.message}")
                            }
                        }
                    }
                }
            }
        }

    private var _error: VideoPlayerError? by mutableStateOf(null)
    override val error get() = _error

    override fun clearError() {
        // _error is snapshot state (thread-safe); set it directly. A runBlocking { withContext(Main) }
        // here would self-deadlock when called from the AWT/Compose UI thread.
        _error = null
    }

    // Current frame management
    private var _currentFrame: Bitmap? by mutableStateOf(null)
    private val bitmapLock = ReentrantReadWriteLock()
    internal val currentFrameState = mutableStateOf<ImageBitmap?>(null)

    // Aspect ratio — driven by the live frame (see syncAspectRatioToFrame).
    private var _aspectRatio by mutableStateOf(16f / 9f)
    override val aspectRatio: Float get() = _aspectRatio

    // Metadata and UI state
    override val metadata: VideoMetadata = VideoMetadata()
    override var subtitlesEnabled by mutableStateOf(false)
    override var currentSubtitleTrack: SubtitleTrack? by mutableStateOf(null)
    override val availableSubtitleTracks = mutableListOf<SubtitleTrack>()
    override var subtitleTextStyle: TextStyle by mutableStateOf(
        TextStyle(
            color = Color.White,
            fontSize = 18.sp,
            fontWeight = FontWeight.Normal,
            textAlign = TextAlign.Center,
        ),
    )
    override var subtitleBackgroundColor: Color by mutableStateOf(Color.Black.copy(alpha = 0.5f))
    override var isLoading by mutableStateOf(false)
        private set
    override val positionText: String get() = formatTime(_currentTime)
    override val durationText: String get() = formatTime(_duration)
    override val currentTime: Double get() = _currentTime
    override val duration: Double get() = _duration

    // Fullscreen state
    override var isFullscreen by mutableStateOf(false)

    // Video properties (decoded frame dimensions, possibly scaled to the surface)
    private var videoWidth by mutableStateOf(0)
    private var videoHeight by mutableStateOf(0)

    // Surface display size (pixels) — used to scale native output resolution
    private var surfaceWidth = 0
    private var surfaceHeight = 0

    // Frame rate info
    private var videoFrameRate: Float = 0f
    private var screenRefreshRate: Float = 0f
    private var captureFrameRate: Float = 0f

    // Synchronization
    private val mediaOperationMutex = Mutex()
    private val isResizing = AtomicBoolean(false)
    private var videoJob: Job? = null
    private var resizeJob: Job? = null

    // Seek coalescing: rapid slider drags overwrite the target; only the latest value is actually
    // seeked. seekInFlight acts as the "a loop is draining the target" claim. The target is stored
    // as microseconds (seconds * 1_000_000) so it fits a Long; Long.MIN_VALUE is the empty sentinel.
    private val pendingSeekTarget = AtomicLong(Long.MIN_VALUE)
    private val seekInFlight = AtomicBoolean(false)

    // Serializes native frame access (nLockFrame/nUnlockFrame, held by the producer) and nSeekTo
    // (held by the seek flow), so a seek never runs concurrently with a frame read.
    private val videoReaderMutex = Mutex()
    private val isSeeking = AtomicBoolean(false)

    // Open coordination: openToken makes a newer open supersede an older one; isOpening lets
    // callers (e.g. play()'s slow path) tell that an open is already in flight.
    private val openToken = AtomicLong(0L)
    private val isOpening = AtomicBoolean(false)

    // Frame channel: one slot, drop-oldest. With triple-buffering on the producer side, overflow
    // simply means the consumer was slow — safe to drop.
    private val frameChannel =
        Channel<FrameData>(
            capacity = 1,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )

    private data class FrameData(
        val bitmap: Bitmap,
        val timestamp: Double,
    )

    // Triple-buffering for zero-copy frame rendering: the consumer may still be driving a frame
    // onto Compose (via currentFrameState) when the producer writes the next frame. Two bitmaps is
    // racy — with three, the buffer the producer writes is distinct from both the one currently
    // bound to ImageBitmap and the one Compose just finished.
    private val skiaBitmaps = arrayOfNulls<Bitmap>(3)
    private var nextBitmapIndex: Int = 0
    @Volatile
    private var lastFrameHash: Int = Int.MIN_VALUE
    private var skiaBitmapWidth: Int = 0
    private var skiaBitmapHeight: Int = 0

    // Bitmaps awaiting safe closure. When the video resolution changes mid-stream (HLS adaptive
    // bitrate) the old buffers may still be read by Compose via currentFrameState. We defer close()
    // by a few consumed frames so Compose has swapped to the new bitmap first.
    private data class PendingCloseBitmap(val bitmap: Bitmap, var framesLeft: Int)
    private val pendingCloseBitmaps = ArrayDeque<PendingCloseBitmap>()
    private val pendingCloseGraceFrames: Int = 4

    // Adaptive frame interval (ms) based on the video's frame rate — the producer polls the native
    // "latest frame" at this cadence (AVFoundation pushes frames; it has no blocking read).
    private var frameIntervalMs: Long = 16L

    /** Flag to track whether we've read at least one frame while paused (for the paused thumbnail). */
    private val initialFrameRead = AtomicBoolean(false)

    // Last opened URI, for play()-without-media reload.
    private var lastUri: String? = null

    init {
        macLogger.d { "Initializing video player" }
        scope.launch {
            try {
                val ptr = MacNativeBridge.nCreatePlayer()
                if (ptr == 0L) {
                    setError("Failed to create native player")
                    return@launch
                }
                playerPtrAtomic.set(ptr)
                applyVolume()
                applyPlaybackSpeed()
                initReady.complete(Unit)
            } catch (e: Exception) {
                initReady.completeExceptionally(e)
                setError("Exception during initialization: ${e.message}")
            }
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Open
    // ---------------------------------------------------------------------------------------------

    override fun openUri(
        uri: String,
        initializeplayerState: InitialPlayerState,
    ) {
        if (isDisposing.get()) {
            macLogger.w { "Ignoring openUri call - player is being disposed" }
            return
        }

        lastUri = uri
        playbackSpeed = 1.0f

        scope.launch {
            try {
                withTimeout(10_000) { initReady.await() }
                openUriInternal(uri, initializeplayerState)
            } catch (_: TimeoutCancellationException) {
                setError("Player initialization timed out after 10 s.")
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                setError("Error while waiting for initialization: ${e.message}")
            }
        }
    }

    override fun openFile(
        file: PlatformFile,
        initializeplayerState: InitialPlayerState,
    ) {
        openUri(file.file.path, initializeplayerState)
    }

    private fun openUriInternal(
        uri: String,
        initializeplayerState: InitialPlayerState,
    ) {
        scope.launch {
            if (isDisposing.get()) return@launch

            // A newer open supersedes any older one still running its (unlocked) polling phase.
            val myToken = openToken.incrementAndGet()
            isOpening.set(true)
            var ptr = 0L
            try {
                // Phase 1 (locked): stop current playback, reset state, hand the URI to native.
                mediaOperationMutex.withLock {
                    if (isDisposing.get() || myToken != openToken.get()) return@withLock
                    isLoading = true

                    val p = playerPtr
                    if (p == 0L) {
                        setError("Native player is null")
                        return@withLock
                    }

                    if (_isPlaying) {
                        MacNativeBridge.nPause(p)
                        _isPlaying = false
                        delay(50)
                    }

                    videoJob?.cancelAndJoin()
                    releaseAllResources()

                    _currentTime = 0.0
                    _progress = 0f
                    _duration = 0.0
                    resetMetadata()
                    _hasMedia = false
                    userPaused = false
                    initialFrameRead.set(false)

                    // Validate local files before handing off to the native layer.
                    if (!checkExistsIfLocalFile(uri)) {
                        setError("File not found: $uri")
                        return@withLock
                    }

                    MacNativeBridge.nOpenUri(p, uri)
                    ptr = p
                }

                if (ptr == 0L || isDisposing.get() || myToken != openToken.get()) return@launch

                // Phase 2 (UNLOCKED): AVFoundation loads asynchronously, so these polls can take
                // several seconds. Running them outside mediaOperationMutex keeps volume/other
                // operations responsive during a slow open.
                pollDimensionsUntilReady(ptr)
                val resolvedDuration = pollDurationUntilReady(ptr)
                if (isDisposing.get() || myToken != openToken.get()) return@launch

                // Phase 3 (locked): finalize metadata and start the pipeline.
                mediaOperationMutex.withLock {
                    if (isDisposing.get() || myToken != openToken.get()) return@withLock
                    try {
                        val w = MacNativeBridge.nGetFrameWidth(ptr)
                        val h = MacNativeBridge.nGetFrameHeight(ptr)
                        if (w <= 0 || h <= 0) {
                            setError("Failed to retrieve video size")
                            return@withLock
                        }
                        videoWidth = w
                        videoHeight = h

                        // Scale output to match the display surface (saves memory for 4K+ video).
                        if (surfaceWidth > 0 && surfaceHeight > 0) {
                            videoReaderMutex.withLock {
                                MacNativeBridge.nSetOutputSize(ptr, surfaceWidth, surfaceHeight)
                                val sw = MacNativeBridge.nGetFrameWidth(ptr)
                                val sh = MacNativeBridge.nGetFrameHeight(ptr)
                                if (sw > 0 && sh > 0) {
                                    videoWidth = sw
                                    videoHeight = sh
                                }
                            }
                        }

                        // pollDurationUntilReady already resolved this; live streams settle to 0,
                        // and observePosition() backfills it if it arrives even later.
                        _duration = resolvedDuration

                        updateMetadata()
                        updateFrameRateInfo()

                        // Adaptive polling interval from the video frame rate, like Windows.
                        val rate = if (captureFrameRate > 0f) captureFrameRate else videoFrameRate
                        frameIntervalMs = if (rate > 0f) (1000f / rate).toLong().coerceIn(8L, 50L) else 16L

                        _hasMedia = true

                        val startPlayback = initializeplayerState == InitialPlayerState.PLAY
                        if (!startPlayback) {
                            userPaused = true
                            initialFrameRead.set(false)
                            isLoading = false
                        }
                        if (startPlayback) {
                            MacNativeBridge.nPlay(ptr)
                        }
                        _isPlaying = startPlayback

                        videoJob = startVideoPipeline()
                    } catch (e: Exception) {
                        if (e is CancellationException) throw e
                        setError("Error while opening media: ${e.message}")
                        _hasMedia = false
                    }
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                setError("Error while opening media: ${e.message}")
                _hasMedia = false
            } finally {
                // Only clear the flag if a newer open hasn't taken over.
                if (myToken == openToken.get()) isOpening.set(false)
                if (!_hasMedia) isLoading = false
            }
        }
    }

    /**
     * Loops several times (every 250 ms) until width/height are no longer zero, or until
     * [maxAttempts] is reached.
     */
    private suspend fun pollDimensionsUntilReady(
        ptr: Long,
        maxAttempts: Int = 20,
    ) {
        for (attempt in 1..maxAttempts) {
            val width = MacNativeBridge.nGetFrameWidth(ptr)
            val height = MacNativeBridge.nGetFrameHeight(ptr)
            if (width > 0 && height > 0) {
                macLogger.d { "Dimensions validated (w=$width, h=$height) after $attempt attempts" }
                return
            }
            delay(250)
        }
        macLogger.e { "Unable to retrieve valid dimensions after $maxAttempts attempts" }
    }

    /**
     * Reads the media duration from the native player, normalizing AVFoundation's states:
     *  - `> 0.0` — a finite duration in seconds
     *  - `0.0`   — not available yet (AVPlayerItem.duration still indefinite → NaN while loading)
     *  - `-1.0`  — a live / indefinite stream (no finite duration)
     */
    private fun readDuration(ptr: Long): Double {
        val raw = MacNativeBridge.nGetVideoDuration(ptr)
        return when {
            raw < 0.0 -> -1.0 // live-stream sentinel from native getDuration()
            raw.isNaN() -> 0.0 // not loaded yet
            else -> raw // 0.0 (not ready) or a real duration
        }
    }

    /**
     * Polls [readDuration] until a finite duration is available, the stream is detected as live,
     * or [maxAttempts] is reached. Mirrors [pollDimensionsUntilReady]. Returns the duration in
     * seconds, or 0.0 for live / still-unknown streams.
     */
    private suspend fun pollDurationUntilReady(
        ptr: Long,
        maxAttempts: Int = 20,
    ): Double {
        for (attempt in 1..maxAttempts) {
            val d = readDuration(ptr)
            if (d > 0.0) {
                macLogger.d { "Duration resolved ($d s) after $attempt attempts" }
                return d
            }
            if (d < 0.0) return 0.0 // live stream — no finite total time
            delay(100)
        }
        macLogger.w { "Duration still unavailable after $maxAttempts attempts" }
        return 0.0
    }

    // Handles both URIs with a "file:" scheme and simple filenames; network URIs are assumed reachable.
    private fun checkExistsIfLocalFile(uri: String): Boolean {
        val schemeDelimiter = uri.indexOf("://")
        val scheme = if (schemeDelimiter >= 0) uri.substring(0, schemeDelimiter) else ""
        return when (scheme) {
            "", "file" -> {
                val path = if (scheme == "file") uri.removePrefix("file://") else uri
                File(path).exists()
            }
            else -> true
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Aspect ratio (the deliberate divergence from Windows — display-AR correct)
    // ---------------------------------------------------------------------------------------------

    /**
     * Returns the aspect ratio the video should be displayed at.
     *
     * Prefers the display aspect ratio reported by AVFoundation ([MacNativeBridge.nGetDisplayAspectRatio],
     * i.e. `presentationSize`), which has the pixel aspect ratio and clean aperture applied. Anamorphic /
     * non-square-pixel videos have a display aspect ratio that differs from the raw decoded pixel-buffer
     * dimensions; drawing the raw bitmap into a Canvas sized with this display ratio rescales the pixels
     * back to their intended geometry. Falls back to the raw frame ratio when unavailable.
     */
    private fun displayAspectRatio(width: Int, height: Int): Float {
        val ptr = playerPtr
        val displayAspect = if (ptr != 0L) MacNativeBridge.nGetDisplayAspectRatio(ptr) else 0.0
        return if (displayAspect > 0.0) displayAspect.toFloat() else width.toFloat() / height.toFloat()
    }

    /**
     * Aligns the displayed aspect ratio (and reported dimensions) with the live frame. Called on
     * every published frame; the guards keep it a no-op unless a value actually changed.
     */
    private fun syncAspectRatioToFrame(width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        val frameAspect = displayAspectRatio(width, height)
        if (kotlin.math.abs(frameAspect - _aspectRatio) > 0.001f) {
            _aspectRatio = frameAspect
        }
        if (metadata.width != width) metadata.width = width
        if (metadata.height != height) metadata.height = height
    }

    // ---------------------------------------------------------------------------------------------
    // Metadata
    // ---------------------------------------------------------------------------------------------

    private fun resetMetadata() {
        metadata.title = null
        metadata.duration = null
        metadata.width = null
        metadata.height = null
        metadata.bitrate = null
        metadata.frameRate = null
        metadata.mimeType = null
        metadata.audioChannels = null
        metadata.audioSampleRate = null
    }

    private fun updateMetadata() {
        val ptr = playerPtr
        if (ptr == 0L) return
        try {
            val width = MacNativeBridge.nGetFrameWidth(ptr)
            val height = MacNativeBridge.nGetFrameHeight(ptr)
            // Use the already-resolved _duration (pollDurationUntilReady ran before this) rather
            // than re-reading the raw native value, which is often still NaN here and would pin
            // metadata.duration to null forever. observePosition() backfills it if it lands later.
            metadata.duration = (_duration * 1000).toLong().takeIf { it > 0 }
            metadata.width = width
            metadata.height = height
            metadata.frameRate = MacNativeBridge.nGetVideoFrameRate(ptr)
            metadata.title = MacNativeBridge.nGetVideoTitle(ptr)
            metadata.bitrate = MacNativeBridge.nGetVideoBitrate(ptr)
            metadata.mimeType = MacNativeBridge.nGetVideoMimeType(ptr)
            metadata.audioChannels = MacNativeBridge.nGetAudioChannels(ptr).takeIf { it != 0 }
            metadata.audioSampleRate = MacNativeBridge.nGetAudioSampleRate(ptr).takeIf { it != 0 }

            if (width > 0 && height > 0) {
                _aspectRatio = displayAspectRatio(width, height)
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            macLogger.e { "Error updating metadata: ${e.message}" }
        }
    }

    private fun updateFrameRateInfo() {
        val ptr = playerPtr
        if (ptr == 0L) return
        try {
            videoFrameRate = MacNativeBridge.nGetVideoFrameRate(ptr)
            screenRefreshRate = MacNativeBridge.nGetScreenRefreshRate(ptr)
            captureFrameRate = MacNativeBridge.nGetCaptureFrameRate(ptr)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            macLogger.e { "Error updating frame rate info: ${e.message}" }
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Frame pipeline (producer / consumer) — mirrors Windows
    // ---------------------------------------------------------------------------------------------

    private fun startVideoPipeline(): Job = scope.launch {
        launch { produceFrames() }
        launch { consumeFrames() }
        launch { observePosition() }
    }

    /**
     * Drives the timeline from the AVPlayer clock, independent of the frame pipeline.
     *
     * Windows can ride the timeline on consumed frames because Media Foundation couples its frame
     * clock to GetMediaPosition. AVFoundation does not: nLockFrame returns the same CVPixelBuffer
     * until a genuinely new one is ready, so the content-hash dedup in [processOneFrame] drops
     * repeats and the frame-derived position in [consumeFrames] stalls (the slider freezes on
     * low-motion content even though playback continues). Polling nGetCurrentTime on a fixed
     * cadence keeps the position advancing smoothly regardless of frame delivery.
     */
    private suspend fun observePosition() {
        while (scope.isActive && _hasMedia && !isDisposing.get()) {
            val ptr = playerPtr
            if (ptr != 0L) {
                // Backfill a duration that arrived after open (e.g. HLS VOD) regardless of play
                // state, so the total-time label appears even when the media was opened paused.
                // metadata.duration is what consumers read for the total-time label.
                if (_duration <= 0.0) {
                    val d = readDuration(ptr)
                    if (d > 0.0) {
                        _duration = d
                        metadata.duration = (d * 1000).toLong().takeIf { it > 0 }
                    }
                }
                // Advance the timeline only while genuinely playing and not seeking/dragging, so
                // we never clobber the optimistic seek/drag UI (seekFinished() reads sliderPos,
                // backed by _progress). seekInFlight covers the whole async-seek window, not just
                // the brief native-seek (isSeeking) portion.
                if (_isPlaying && !_userDragging && !isSeeking.get() && !seekInFlight.get() && _duration > 0.0) {
                    val pos = MacNativeBridge.nGetCurrentTime(ptr)
                    if (pos >= 0.0) {
                        _currentTime = pos
                        _progress = (pos / _duration).toFloat().coerceIn(0f, 1f)
                    }
                }
            }
            delay(POSITION_POLL_INTERVAL_MS)
        }
    }

    private suspend fun produceFrames() {
        while (scope.isActive && _hasMedia && !isDisposing.get()) {
            val ptr = playerPtr
            if (ptr == 0L) break

            // End-of-playback: AVFoundation fires AVPlayerItemDidPlayToEndTime, surfaced as a
            // one-shot flag. Only consume/act on it while genuinely playing and not mid-seek —
            // otherwise a stray flag observed during a seek or paused thumbnail read could be
            // swallowed or trigger a spurious end. Mirrors the Windows IsEOF branch.
            if (_isPlaying && !isSeeking.get() && !seekInFlight.get() && MacNativeBridge.nConsumeDidPlayToEnd(ptr)) {
                if (_duration <= 0.0) {
                    // Live stream — wait and continue.
                    delay(1000)
                    continue
                } else if (loop) {
                    try {
                        userPaused = false
                        initialFrameRead.set(false)
                        lastFrameHash = Int.MIN_VALUE
                        // _isPlaying stays true, so performSeek() resumes native playback via its
                        // own nPlay; no explicit play() needed here.
                        seekTo(0f)
                        onRestart?.invoke()
                    } catch (e: Exception) {
                        if (e is CancellationException) throw e
                        setError("Error during loop restart: ${e.message}")
                    }
                    // Don't fall through to read the stale end-of-stream frame this iteration.
                    continue
                } else {
                    // Stop the play state synchronously first so observePosition (a sibling
                    // coroutine) can't immediately un-snap the slider, then snap to 100% and pause
                    // the native player directly (don't rely on pause()'s _isPlaying precondition).
                    _isPlaying = false
                    _currentTime = _duration
                    _progress = 1f
                    userPaused = true
                    initialFrameRead.set(false)
                    try {
                        MacNativeBridge.nPause(ptr)
                    } catch (e: Exception) {
                        if (e is CancellationException) throw e
                    }
                    onPlaybackEnded?.invoke()
                    break
                }
            }

            try {
                if (!waitForPlaybackState(allowInitialFrame = true)) {
                    delay(100)
                    continue
                }
            } catch (e: CancellationException) {
                break
            }

            if (waitIfResizing()) continue

            // Short-circuit while a seek is in progress — avoids contending on videoReaderMutex.
            if (isSeeking.get()) {
                delay(5)
                continue
            }

            val produced = try {
                videoReaderMutex.withLock {
                    processOneFrame(ptr)
                }
            } catch (e: CancellationException) {
                break
            } catch (e: Exception) {
                if (scope.isActive && _hasMedia && !isDisposing.get()) {
                    setError("Error while reading a frame: ${e.message}")
                }
                delay(100)
                null
            }

            when (produced) {
                ProduceOutcome.NotReady -> {
                    // AVFoundation delivers frames asynchronously: the first read after a paused
                    // open can find no buffer yet. Don't spend the one-shot initial-frame allowance
                    // on an empty buffer — restore it so the next pass tries again and the paused
                    // thumbnail still appears once the buffer is ready.
                    if (!_isPlaying && userPaused) initialFrameRead.set(false)
                    delay(frameIntervalMs)
                }
                ProduceOutcome.SkipIteration -> delay(frameIntervalMs)
                is ProduceOutcome.Frame -> {
                    frameChannel.trySend(FrameData(produced.bitmap, produced.timestamp))
                    delay(frameIntervalMs)
                }
                null -> { /* exception already handled */ }
            }
        }
    }

    private sealed interface ProduceOutcome {
        data object NotReady : ProduceOutcome
        data object SkipIteration : ProduceOutcome
        data class Frame(val bitmap: Bitmap, val timestamp: Double) : ProduceOutcome
    }

    /**
     * Locks the latest CVPixelBuffer, copies it to the next Skia bitmap, and returns the outcome.
     * Must be called under [videoReaderMutex]. Always unlocks the native frame on exit.
     */
    private fun processOneFrame(ptr: Long): ProduceOutcome {
        // outInfo = [width, height, bytesPerRow]
        val outInfo = IntArray(3)
        val frameAddress = MacNativeBridge.nLockFrame(ptr, outInfo)
        if (frameAddress == 0L) return ProduceOutcome.NotReady

        try {
            val width = outInfo[0]
            val height = outInfo[1]
            val srcBytesPerRow = outInfo[2]
            if (width <= 0 || height <= 0) return ProduceOutcome.SkipIteration

            // HLS adaptive bitrate may change the decoded size mid-stream.
            if (width != videoWidth || height != videoHeight) {
                videoWidth = width
                videoHeight = height
            }

            val srcBuf =
                MacNativeBridge.nWrapPointer(frameAddress, srcBytesPerRow.toLong() * height.toLong())
                    ?: return ProduceOutcome.SkipIteration

            srcBuf.rewind()
            val newHash = calculateFrameHash(srcBuf, width, height, srcBytesPerRow)
            if (newHash == lastFrameHash) return ProduceOutcome.SkipIteration
            lastFrameHash = newHash

            if (skiaBitmaps[0] == null || skiaBitmapWidth != width || skiaBitmapHeight != height) {
                bitmapLock.write {
                    // Defer-close previous bitmaps instead of freeing memory Compose may still draw.
                    for (i in skiaBitmaps.indices) {
                        skiaBitmaps[i]?.let {
                            pendingCloseBitmaps.addLast(PendingCloseBitmap(it, pendingCloseGraceFrames))
                        }
                        skiaBitmaps[i] = null
                    }
                    val imageInfo = ImageInfo(width, height, ColorType.BGRA_8888, ColorAlphaType.OPAQUE)
                    for (i in skiaBitmaps.indices) {
                        skiaBitmaps[i] = Bitmap().apply { allocPixels(imageInfo) }
                    }
                    skiaBitmapWidth = width
                    skiaBitmapHeight = height
                    nextBitmapIndex = 0
                }
            }

            drainPendingCloseBitmaps()

            val targetBitmap = skiaBitmaps[nextBitmapIndex]!!
            nextBitmapIndex = (nextBitmapIndex + 1) % skiaBitmaps.size

            val pixmap = targetBitmap.peekPixels() ?: return ProduceOutcome.SkipIteration
            val pixelsAddr = pixmap.addr
            if (pixelsAddr == 0L) return ProduceOutcome.SkipIteration

            val dstRowBytes = pixmap.rowBytes
            val dstBuf =
                MacNativeBridge.nWrapPointer(pixelsAddr, dstRowBytes.toLong() * height.toLong())
                    ?: return ProduceOutcome.SkipIteration

            srcBuf.rewind()
            copyBgraFrame(srcBuf, dstBuf, width, height, srcBytesPerRow, dstRowBytes)

            // Keep the display aspect ratio aligned with the frame we actually draw.
            syncAspectRatioToFrame(width, height)

            val timestamp = MacNativeBridge.nGetCurrentTime(ptr)
            return ProduceOutcome.Frame(targetBitmap, timestamp)
        } finally {
            MacNativeBridge.nUnlockFrame(ptr)
        }
    }

    private suspend fun consumeFrames() {
        var frameReceived = false
        var loadingTimeout = 0

        while (scope.isActive && _hasMedia && !isDisposing.get()) {
            if (waitIfResizing()) continue

            try {
                val frameData =
                    frameChannel.tryReceive().getOrNull() ?: run {
                        if (isLoading && !frameReceived) {
                            loadingTimeout++
                            if (loadingTimeout > 200) {
                                macLogger.w { "No frames received for 3 seconds, forcing isLoading to false" }
                                isLoading = false
                                loadingTimeout = 0
                            }
                        }
                        delay(16)
                        return@run null
                    } ?: continue

                loadingTimeout = 0
                frameReceived = true

                bitmapLock.write {
                    _currentFrame = frameData.bitmap
                    currentFrameState.value = frameData.bitmap.asComposeImageBitmap()
                }

                // Don't clobber the timeline while the user is dragging or a seek is in flight: a
                // frame received here can predate the seek, and seekFinished() reads sliderPos
                // (backed by _progress) to decide where to seek.
                if (!_userDragging && !seekInFlight.get()) {
                    _currentTime = frameData.timestamp
                    _progress =
                        if (_duration > 0.0) {
                            (_currentTime / _duration).toFloat().coerceIn(0f, 1f)
                        } else {
                            0f
                        }
                }
                isLoading = false

                delay(1)
            } catch (e: CancellationException) {
                break
            } catch (e: Exception) {
                if (scope.isActive && _hasMedia && !isDisposing.get()) {
                    setError("Error while processing a frame: ${e.message}")
                }
                delay(100)
            }
        }
    }

    private fun drainPendingCloseBitmaps() {
        if (pendingCloseBitmaps.isEmpty()) return
        bitmapLock.write {
            val iterator = pendingCloseBitmaps.iterator()
            while (iterator.hasNext()) {
                val entry = iterator.next()
                entry.framesLeft -= 1
                if (entry.framesLeft <= 0) {
                    try {
                        entry.bitmap.close()
                    } catch (_: Throwable) {
                        // Ignore: bitmap may already be released by the Skia cleaner.
                    }
                    iterator.remove()
                }
            }
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Playback controls
    // ---------------------------------------------------------------------------------------------

    override fun play() {
        if (isDisposing.get()) return

        if (readyForPlayback()) {
            executeMediaOperation(operation = "play") {
                resumePlayback()
            }
            return
        }

        // Slow path: wait for any in-progress openUri to complete, then resume.
        scope.launch {
            try {
                withTimeout(10_000) { initReady.await() }
                withTimeout(10_000) {
                    snapshotFlow { _hasMedia }.filter { it }.first()
                }
            } catch (_: Exception) {
                // Only kick off a reload if there's genuinely no media AND no open already in
                // flight, otherwise a slow open completing near the timeout triggers a redundant
                // second open (full reload/flicker of the just-loaded video).
                if (!_hasMedia && !isOpening.get()) {
                    lastUri?.takeIf { it.isNotEmpty() }?.let { uri ->
                        openUriInternal(uri, InitialPlayerState.PLAY)
                    }
                }
                return@launch
            }

            mediaOperationMutex.withLock {
                if (!isDisposing.get()) resumePlayback()
            }
        }
    }

    /** Resumes playback — must be called under [mediaOperationMutex]. */
    private fun resumePlayback() {
        userPaused = false
        initialFrameRead.set(false)

        if (!_isPlaying) {
            setPlaybackState(true, "Error while starting playback")
        }

        if (_hasMedia && (videoJob == null || videoJob?.isActive == false)) {
            videoJob = startVideoPipeline()
        }
    }

    override fun pause() {
        if (isDisposing.get()) return

        executeMediaOperation(
            operation = "pause",
            precondition = _isPlaying,
        ) {
            userPaused = true
            // Read a fresh frame to display while paused.
            initialFrameRead.set(false)
            setPlaybackState(false, "Error while pausing playback")
        }
    }

    override fun stop() {
        if (isDisposing.get()) return

        executeMediaOperation(operation = "stop") {
            setPlaybackState(false, "Error while stopping playback")
            playerPtr.takeIf { it != 0L }?.let { ptr ->
                MacNativeBridge.nSeekTo(ptr, 0.0)
            }
            delay(50)
            videoJob?.cancelAndJoin()
            releaseAllResources()
            _hasMedia = false
            _progress = 0f
            _currentTime = 0.0
            _duration = 0.0
            isLoading = false
            _error = null
            userPaused = false
            initialFrameRead.set(false)
        }
    }

    override fun seekTo(value: Float) {
        if (isDisposing.get()) return
        if (_duration <= 0.0) return // Live stream — seeking not supported

        val clamped = value.coerceIn(0f, 1000f)
        val targetSeconds = _duration * (clamped / 1000.0)

        // Latch the newest target; whoever is running the seek loop will see it.
        pendingSeekTarget.set((targetSeconds * 1_000_000.0).toLong())

        // Optimistic UI so the slider tracks the drag smoothly while the native seek settles.
        _progress = (clamped / 1000f).coerceIn(0f, 1f)
        _currentTime = _duration * _progress

        scheduleSeek()
    }

    /**
     * Launches the seek loop if no other loop is currently draining the target. Rapid [seekTo]
     * calls are coalesced — only the latest target is actually processed.
     */
    private fun scheduleSeek() {
        if (!seekInFlight.compareAndSet(false, true)) return

        scope.launch {
            try {
                while (true) {
                    val target = pendingSeekTarget.getAndSet(Long.MIN_VALUE)
                    if (target == Long.MIN_VALUE) break
                    performSeek(target / 1_000_000.0)
                }
            } finally {
                seekInFlight.set(false)
                if (pendingSeekTarget.get() != Long.MIN_VALUE) scheduleSeek()
            }
        }
    }

    /**
     * Executes a single native seek. Keeps the producer/consumer alive and serializes native reader
     * access with [videoReaderMutex] + [isSeeking].
     */
    private suspend fun performSeek(targetSeconds: Double) {
        val loadingTrigger = scope.launch {
            delay(200)
            if (!isDisposing.get()) isLoading = true
        }

        try {
            mediaOperationMutex.withLock {
                if (isDisposing.get()) return@withLock
                val ptr = playerPtr
                if (ptr == 0L || !_hasMedia) return@withLock

                isSeeking.set(true)
                try {
                    videoReaderMutex.withLock {
                        initialFrameRead.set(false)
                        lastFrameHash = Int.MIN_VALUE
                        clearFrameChannel()

                        MacNativeBridge.nSeekTo(ptr, targetSeconds)
                        // Keep showing frames while playing; a paused seek captures one natively.
                        if (_isPlaying) MacNativeBridge.nPlay(ptr)

                        val pos = MacNativeBridge.nGetCurrentTime(ptr)
                        _currentTime = pos
                        _progress =
                            if (_duration > 0.0) (pos / _duration).toFloat().coerceIn(0f, 1f) else 0f
                    }
                } finally {
                    isSeeking.set(false)
                }

                if (!isDisposing.get() && (videoJob == null || videoJob?.isActive == false)) {
                    videoJob = startVideoPipeline()
                }
            }
        } finally {
            loadingTrigger.cancel()
            isLoading = false
        }
    }

    /**
     * Sets the native playback state (play / pause).
     *
     * @return true if the operation succeeded.
     */
    private fun setPlaybackState(
        playing: Boolean,
        errorMessage: String,
    ): Boolean {
        val ptr = playerPtr
        if (ptr == 0L) {
            setError("$errorMessage: No player instance")
            return false
        }
        return try {
            if (playing) MacNativeBridge.nPlay(ptr) else MacNativeBridge.nPause(ptr)
            _isPlaying = playing
            if (_error != null) _error = null
            true
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            setError("$errorMessage: ${e.message}")
            false
        }
    }

    /**
     * Waits for playback to become active, allowing one frame to be read while paused (thumbnail).
     *
     * @return true if the producer should process frames, false if it should keep waiting.
     */
    private suspend fun waitForPlaybackState(allowInitialFrame: Boolean = false): Boolean {
        if (_isPlaying) return true
        if (userPaused && allowInitialFrame && !initialFrameRead.getAndSet(true)) return true
        if (isLoading) isLoading = false

        while (scope.isActive && _hasMedia && !isDisposing.get()) {
            if (_isPlaying) return true
            if (userPaused && allowInitialFrame && !initialFrameRead.getAndSet(true)) return true
            try {
                delay(40)
            } catch (e: CancellationException) {
                throw e
            }
        }
        return false
    }

    private var resizeWaitCount = 0

    private suspend fun waitIfResizing(): Boolean {
        if (isResizing.get()) {
            resizeWaitCount++
            if (resizeWaitCount > 200) {
                isResizing.set(false)
                resizeWaitCount = 0
                return false
            }
            try {
                yield()
                delay(8)
            } catch (e: CancellationException) {
                throw e
            }
            return true
        }
        resizeWaitCount = 0
        return false
    }

    private fun readyForPlayback(): Boolean =
        initReady.isCompleted && playerPtr != 0L && _hasMedia && !isDisposing.get()

    private fun executeMediaOperation(
        operation: String,
        precondition: Boolean = true,
        block: suspend () -> Unit,
    ) {
        if (!precondition || isDisposing.get()) return

        scope.launch {
            mediaOperationMutex.withLock {
                try {
                    if (!isDisposing.get()) block()
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    setError("Error during $operation: ${e.message}")
                }
            }
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Resize / output scaling
    // ---------------------------------------------------------------------------------------------

    fun onResized(
        width: Int = 0,
        height: Int = 0,
    ) {
        if (isDisposing.get()) return
        if (width <= 0 || height <= 0) return
        if (width == surfaceWidth && height == surfaceHeight) return

        surfaceWidth = width
        surfaceHeight = height

        isResizing.set(true)
        resizeJob?.cancel()
        resizeJob =
            scope.launch {
                delay(120)
                try {
                    applyOutputScaling()
                } finally {
                    isResizing.set(false)
                }
            }
    }

    private suspend fun applyOutputScaling() {
        if (isDisposing.get() || !_hasMedia) return
        val sw = surfaceWidth
        val sh = surfaceHeight
        if (sw <= 0 || sh <= 0) return
        val ptr = playerPtr
        if (ptr == 0L) return

        mediaOperationMutex.withLock {
            // Hold videoReaderMutex too: nSetOutputSize reconfigures the native video output and
            // must not run while processOneFrame has a pixel buffer locked. This also makes the
            // lastFrameHash reset race-free against processOneFrame's read-modify-write.
            // Lock order (mediaOperationMutex → videoReaderMutex) matches performSeek().
            videoReaderMutex.withLock {
                MacNativeBridge.nSetOutputSize(ptr, sw, sh)
                val w = MacNativeBridge.nGetFrameWidth(ptr)
                val h = MacNativeBridge.nGetFrameHeight(ptr)
                if (w > 0 && h > 0) {
                    videoWidth = w
                    videoHeight = h
                    // Force reallocation/republish at the new size.
                    lastFrameHash = Int.MIN_VALUE
                }
            }
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Volume / speed application (used at init)
    // ---------------------------------------------------------------------------------------------

    private fun applyVolume() {
        playerPtr.takeIf { it != 0L }?.let { ptr ->
            try {
                MacNativeBridge.nSetVolume(ptr, _volume)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                macLogger.e { "Error applying volume: ${e.message}" }
            }
        }
    }

    private fun applyPlaybackSpeed() {
        playerPtr.takeIf { it != 0L }?.let { ptr ->
            try {
                MacNativeBridge.nSetPlaybackSpeed(ptr, _playbackSpeed)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                macLogger.e { "Error applying playback speed: ${e.message}" }
            }
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Subtitles / fullscreen
    // ---------------------------------------------------------------------------------------------

    override fun selectSubtitleTrack(track: SubtitleTrack?) {
        scope.launch {
            withContext(Dispatchers.Main) {
                currentSubtitleTrack = track
                subtitlesEnabled = track != null
            }
        }
    }

    override fun disableSubtitles() {
        scope.launch {
            withContext(Dispatchers.Main) {
                subtitlesEnabled = false
                currentSubtitleTrack = null
            }
        }
    }

    override fun toggleFullscreen() {
        isFullscreen = !isFullscreen
    }

    // ---------------------------------------------------------------------------------------------
    // Cleanup
    // ---------------------------------------------------------------------------------------------

    private fun setError(msg: String) {
        _error = VideoPlayerError.UnknownError(msg)
        isLoading = false
        macLogger.e { msg }
    }

    private fun clearFrameChannel() {
        while (frameChannel.tryReceive().isSuccess) { /* drain */ }
    }

    private fun releaseAllResources() {
        videoJob?.cancel()
        resizeJob?.cancel()
        clearFrameChannel()

        // Do NOT close the triple-buffer bitmaps here: the ImageBitmap exposed via
        // currentFrameState shares the same native pixel memory (asComposeImageBitmap is
        // zero-copy) and Compose may still be rendering. Nullifying lets the Skia cleaner free
        // them once all holders drop their reference.
        bitmapLock.write {
            _currentFrame = null
            currentFrameState.value = null
            for (i in skiaBitmaps.indices) skiaBitmaps[i] = null
            skiaBitmapWidth = 0
            skiaBitmapHeight = 0
            nextBitmapIndex = 0
            lastFrameHash = Int.MIN_VALUE
            pendingCloseBitmaps.clear()
        }
        initialFrameRead.set(false)
    }

    override fun dispose() {
        if (isDisposing.getAndSet(true)) return

        val jobToJoin = videoJob
        videoJob = null
        jobToJoin?.cancel()
        resizeJob?.cancel()
        _isPlaying = false
        _hasMedia = false

        releaseAllResources()

        val ptrToDispose = playerPtrAtomic.getAndSet(0L)
        lastUri = null

        // Native teardown on a background thread to avoid blocking the UI thread. Before freeing
        // the player, wait for the producer to exit AND acquire videoReaderMutex — processOneFrame
        // holds that mutex across the whole nLockFrame→…→nUnlockFrame sequence, so holding it once
        // guarantees no frame is currently locked and no in-flight nUnlockFrame will touch freed
        // memory. The pointer is already zeroed and the job cancelled, so the producer cannot
        // re-enter with a valid handle after we release. The timeouts keep shutdown bounded.
        if (ptrToDispose != 0L) {
            Thread {
                try {
                    runBlocking {
                        withTimeoutOrNull(500) { jobToJoin?.join() }
                        withTimeoutOrNull(1000) { videoReaderMutex.withLock { } }
                    }
                } catch (_: Throwable) { /* best effort */ }
                try {
                    MacNativeBridge.nDisposePlayer(ptrToDispose)
                } catch (e: Exception) {
                    macLogger.e { "Error disposing player: ${e.message}" }
                }
            }.start()
        }

        scope.cancel()
    }
}
