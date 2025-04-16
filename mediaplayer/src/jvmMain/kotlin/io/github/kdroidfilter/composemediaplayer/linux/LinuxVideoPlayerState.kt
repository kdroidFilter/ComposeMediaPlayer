package io.github.kdroidfilter.composemediaplayer.linux

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asComposeImageBitmap
import io.github.kdroidfilter.composemediaplayer.PlatformVideoPlayerState
import io.github.kdroidfilter.composemediaplayer.SubtitleTrack
import io.github.kdroidfilter.composemediaplayer.VideoMetadata
import io.github.kdroidfilter.composemediaplayer.VideoPlayerError
import io.github.kdroidfilter.composemediaplayer.util.DEFAULT_ASPECT_RATIO
import io.github.kdroidfilter.composemediaplayer.util.formatTime
import org.freedesktop.gstreamer.*
import org.freedesktop.gstreamer.elements.AppSink
import org.freedesktop.gstreamer.elements.PlayBin
import org.freedesktop.gstreamer.event.SeekFlags
import org.freedesktop.gstreamer.message.MessageType
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.ImageInfo
import java.awt.EventQueue
import java.io.File
import java.net.URI
import java.util.EnumSet
import javax.swing.Timer
import kotlin.math.abs
import kotlin.math.pow

/**
 * LinuxVideoPlayerState serves as the Linux-specific implementation for
 * a video player using GStreamer.
 *
 * To dynamically change the subtitle source, the pipeline is set to READY,
 * the source is updated, and then the pipeline is set back to PLAYING.
 * A Timer performs a slight seek to reposition exactly at the saved position.
 */
@Stable
class LinuxVideoPlayerState : PlatformVideoPlayerState {

    companion object {
        // Flag to enable text subtitles (GST_PLAY_FLAG_TEXT)
        const val GST_PLAY_FLAG_TEXT = 1 shl 2
    }

    init {
        GStreamerInit.init()
    }

    private val playbin = PlayBin("playbin")
    private val videoSink = ElementFactory.make("appsink", "videosink") as AppSink
    private val sliderTimer = Timer(50, null)

    // ---- Internal states ----
    private var _currentFrame by mutableStateOf<ImageBitmap?>(null)
    val currentFrame: ImageBitmap?
        get() = _currentFrame

    private var frameWidth = 0
    private var frameHeight = 0

    private var bufferingPercent by mutableStateOf(100)
    private var isUserPaused by mutableStateOf(false)

    private var _sliderPos by mutableStateOf(0f)
    override var sliderPos: Float
        get() = _sliderPos
        set(value) {
            _sliderPos = value
        }

    // This variable will allow us to handle a potential delay before buffering is signaled
    private var _isSeeking by mutableStateOf(false)
    private var targetSeekPos: Float = 0f

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

    private var _volume by mutableStateOf(1f)
    override var volume: Float
        get() = _volume
        set(value) {
            _volume = value.coerceIn(0f..1f)
            playbin.set("volume", _volume.toDouble())
        }

    private var _leftLevel by mutableStateOf(0f)
    override val leftLevel: Float
        get() = _leftLevel

    private var _rightLevel by mutableStateOf(0f)
    override val rightLevel: Float
        get() = _rightLevel

    private var _positionText by mutableStateOf("0:00")
    override val positionText: String
        get() = _positionText

    private var _durationText by mutableStateOf("0:00")
    override val durationText: String
        get() = _durationText

    private var _isLoading by mutableStateOf(false)
    override val isLoading: Boolean
        get() = _isLoading

    private var _hasMedia by mutableStateOf(false)
    override val hasMedia: Boolean
        get() = _hasMedia


    private var _isPlaying by mutableStateOf(false)
    override val isPlaying: Boolean
        get() = _isPlaying

    private var _error by mutableStateOf<VideoPlayerError?>(null)
    override val error: VideoPlayerError?
        get() = _error

    private var _isFullscreen by mutableStateOf(false)
    override var isFullscreen: Boolean
        get() = _isFullscreen
        set(value) {
            _isFullscreen = value
        }

    override val metadata: VideoMetadata = VideoMetadata()

    override var subtitlesEnabled: Boolean = false
    override var currentSubtitleTrack: SubtitleTrack? = null
    override val availableSubtitleTracks = mutableListOf<SubtitleTrack>()

    // ---- Aspect ratio management ----
    private var lastAspectRatioUpdateTime: Long = 0
    private val ASPECT_RATIO_DEBOUNCE_MS = 500
    private var _aspectRatio by mutableStateOf(DEFAULT_ASPECT_RATIO)
    val aspectRatio: Float
        get() = _aspectRatio

    init {
        // GStreamer configuration
        val levelElement = ElementFactory.make("level", "level")
        playbin.set("audio-filter", levelElement)

        // Configuration of the AppSink for video
        // Requesting RGBA (R, G, B, A) without additional conversion.
        val caps = Caps.fromString("video/x-raw,format=RGBA")
        videoSink.caps = caps
        videoSink.set("emit-signals", true)
        videoSink.connect(object : AppSink.NEW_SAMPLE {
            override fun newSample(appSink: AppSink): FlowReturn {
                val sample = appSink.pullSample()
                if (sample != null) {
                    processSample(sample)
                    sample.dispose()
                }
                return FlowReturn.OK
            }
        })
        playbin.setVideoSink(videoSink)

        // ---- GStreamer bus handling ----

        // End of stream
        playbin.bus.connect(object : Bus.EOS {
            override fun endOfStream(source: GstObject) {
                EventQueue.invokeLater {
                    if (loop) {
                        // Restart from beginning if loop = true
                        playbin.seekSimple(Format.TIME, EnumSet.of(SeekFlags.FLUSH), 0)
                    } else {
                        stop()
                    }
                    _isPlaying = loop
                }
            }
        })

        // Errors
        playbin.bus.connect(object : Bus.ERROR {
            override fun errorMessage(source: GstObject, code: Int, message: String) {
                EventQueue.invokeLater {
                    _error = when {
                        message.contains("codec", ignoreCase = true) ||
                                message.contains("decode", ignoreCase = true) ->
                            VideoPlayerError.CodecError(message)

                        message.contains("network", ignoreCase = true) ||
                                message.contains("connection", ignoreCase = true) ||
                                message.contains("dns", ignoreCase = true) ||
                                message.contains("http", ignoreCase = true) ->
                            VideoPlayerError.NetworkError(message)

                        message.contains("source", ignoreCase = true) ||
                                message.contains("uri", ignoreCase = true) ||
                                message.contains("resource", ignoreCase = true) ->
                            VideoPlayerError.SourceError(message)

                        else ->
                            VideoPlayerError.UnknownError(message)
                    }
                    stop()
                }
            }
        })

        // Buffering
        playbin.bus.connect(object : Bus.BUFFERING {
            override fun bufferingData(source: GstObject, percent: Int) {
                EventQueue.invokeLater {
                    bufferingPercent = percent
                    // When reaching 100%, we consider that any seek has finished
                    if (percent == 100) {
                        _isSeeking = false
                    }
                    updateLoadingState()
                }
            }
        })

        // Pipeline state change
        playbin.bus.connect(object : Bus.STATE_CHANGED {
            override fun stateChanged(
                source: GstObject,
                old: State,
                current: State,
                pending: State,
            ) {
                EventQueue.invokeLater {
                    when (current) {
                        State.PLAYING -> {
                            _isPlaying = true
                            isUserPaused = false
                            updateLoadingState()
                            updateAspectRatio()
                        }
                        State.PAUSED -> {
                            _isPlaying = false
                            updateLoadingState()
                        }
                        State.READY -> {
                            _isPlaying = false
                            updateLoadingState()
                        }
                        else -> {
                            _isPlaying = false
                            updateLoadingState()
                        }
                    }
                }
            }
        })

        // TAG (metadata, if needed)
        playbin.bus.connect(object : Bus.TAG {
            override fun tagsFound(source: GstObject?, tagList: TagList?) {
                // Metadata implementation if necessary
            }
        })

        // Measuring audio level (via the "level" element)
        playbin.bus.connect("element") { _, message ->
            if (message.source == levelElement) {
                val struct = message.structure
                if (struct != null && struct.hasField("peak")) {
                    val peaks = struct.getDoubles("peak")
                    if (peaks.isNotEmpty() && isPlaying) {
                        for (i in peaks.indices) {
                            peaks[i] = 10.0.pow(peaks[i] / 20.0)
                        }
                        val l = if (peaks.isNotEmpty()) peaks[0] else 0.0
                        val r = if (peaks.size > 1) peaks[1] else l
                        EventQueue.invokeLater {
                            _leftLevel = (l.coerceIn(0.0, 1.0) * 100f).toFloat()
                            _rightLevel = (r.coerceIn(0.0, 1.0) * 100f).toFloat()
                        }
                    } else {
                        EventQueue.invokeLater {
                            _leftLevel = 0f
                            _rightLevel = 0f
                        }
                    }
                }
            }
        }

        // Also monitoring the end of async transitions (e.g., after a seek)
        playbin.bus.connect("async-done") { _, message ->
            if (message.type == MessageType.ASYNC_DONE) {
                EventQueue.invokeLater {
                    _isSeeking = false
                    updateLoadingState()
                }
            }
        }

        // Timer for the slider position and duration
        sliderTimer.addActionListener {
            if (!userDragging) {
                val dur = playbin.queryDuration(Format.TIME)
                val pos = playbin.queryPosition(Format.TIME)
                if (dur > 0) {
                    val relPos = pos.toDouble() / dur.toDouble()
                    val currentSliderPos = (relPos * 1000.0).toFloat()

                    if (targetSeekPos > 0f) {
                        if (abs(targetSeekPos - currentSliderPos) < 1f) {
                            _sliderPos = currentSliderPos
                            targetSeekPos = 0f
                        }
                    } else {
                        _sliderPos = currentSliderPos
                    }

                    if (pos > 0) {
                        EventQueue.invokeLater {
                            _positionText = formatTime(pos, true)
                            _durationText = formatTime(dur, true)
                        }
                    }
                }
            }
        }
        sliderTimer.start()
    }

    // ---- Subtitle management ----
    override fun selectSubtitleTrack(track: SubtitleTrack?) {
        currentSubtitleTrack = track
        try {
            if (track != null) {
                val subUri = if (
                    track.src.toString().startsWith("http://") ||
                    track.src.toString().startsWith("https://")
                ) {
                    track.src.toString()
                } else {
                    File(track.src.toString()).toURI().toString()
                }
                if (isPlaying) {
                    val pos = playbin.queryPosition(Format.TIME)
                    playbin.state = State.READY
                    playbin.set("suburi", subUri)
                    val currentFlags = playbin.get("flags") as Int
                    playbin.set("flags", currentFlags or GST_PLAY_FLAG_TEXT)
                    playbin.state = State.PLAYING
                    // Timer to perform a seek to the correct position
                    Timer(100) { _ ->
                        playbin.seekSimple(
                            Format.TIME,
                            EnumSet.of(SeekFlags.FLUSH, SeekFlags.ACCURATE),
                            pos
                        )
                    }.apply {
                        isRepeats = false
                        start()
                    }
                } else {
                    playbin.set("suburi", subUri)
                    val currentFlags = playbin.get("flags") as Int
                    playbin.set("flags", currentFlags or GST_PLAY_FLAG_TEXT)
                }
                subtitlesEnabled = true
            } else {
                disableSubtitles()
            }
        } catch (e: Exception) {
            _error = VideoPlayerError.UnknownError("Error while selecting subtitles: ${e.message}")
        }
    }

    override fun disableSubtitles() {
        currentSubtitleTrack = null
        try {
            if (isPlaying) {
                val pos = playbin.queryPosition(Format.TIME)
                playbin.state = State.READY
                playbin.set("suburi", "")
                val currentFlags = playbin.get("flags") as Int
                playbin.set("flags", currentFlags and GST_PLAY_FLAG_TEXT.inv())
                playbin.state = State.PLAYING
                Timer(100) { _ ->
                    playbin.seekSimple(
                        Format.TIME,
                        EnumSet.of(SeekFlags.FLUSH, SeekFlags.ACCURATE),
                        pos
                    )
                }.apply {
                    isRepeats = false
                    start()
                }
            } else {
                playbin.set("suburi", "")
                val currentFlags = playbin.get("flags") as Int
                playbin.set("flags", currentFlags and GST_PLAY_FLAG_TEXT.inv())
            }
            subtitlesEnabled = false
        } catch (e: Exception) {
            _error = VideoPlayerError.UnknownError("Error disabling subtitles: ${e.message}")
        }
    }

    // ---- Aspect ratio management ----
    private fun updateAspectRatio() {
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastAspectRatioUpdateTime < ASPECT_RATIO_DEBOUNCE_MS) {
            return
        }
        lastAspectRatioUpdateTime = currentTime

        try {
            val videoSinkElement = playbin.get("video-sink") as? Element
            val sinkPad = videoSinkElement?.getStaticPad("sink")
            val caps = sinkPad?.currentCaps
            val structure = caps?.getStructure(0)

            if (structure != null) {
                val width = structure.getInteger("width")
                val height = structure.getInteger("height")

                if (width > 0 && height > 0) {
                    val calculatedRatio = width.toFloat() / height.toFloat()
                    if (calculatedRatio != _aspectRatio) {
                        EventQueue.invokeLater {
                            _aspectRatio = if (calculatedRatio > 0) calculatedRatio else 16f / 9f
                        }
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            _aspectRatio = 16f / 9f
        }
    }

    private fun updateLoadingState() {
        _isLoading = when {
            bufferingPercent < 100 -> true
            _isSeeking -> true
            isUserPaused -> false
            else -> false
        }
    }

    // ---- Controls ----
    override fun openUri(uri: String) {
        stop()
        clearError()
        _isLoading = true
        _hasMedia = false
        try {
            val uriObj = if (uri.startsWith("http://") || uri.startsWith("https://")) {
                URI(uri)
            } else {
                File(uri).toURI()
            }
            playbin.setURI(uriObj)
            _hasMedia = true
            play()
        } catch (e: Exception) {
            _error = VideoPlayerError.SourceError("Unable to open URI: ${e.message}")
            _isLoading = false
            _isPlaying = false
            _hasMedia = false
            e.printStackTrace()
        }
    }

    override fun play() {
        try {
            playbin.play()
            playbin.set("volume", volume.toDouble())
            _hasMedia = true
            _isPlaying = true
            isUserPaused = false
            updateLoadingState()
        } catch (e: Exception) {
            _error = VideoPlayerError.UnknownError("Playback failed: ${e.message}")
            _isPlaying = false
        }
    }

    override fun pause() {
        try {
            playbin.pause()
            _isPlaying = false
            isUserPaused = true
            updateLoadingState()
        } catch (e: Exception) {
            _error = VideoPlayerError.UnknownError("Pause failed: ${e.message}")
        }
    }

    override fun stop() {
        playbin.stop()
        _isPlaying = false
        _sliderPos = 0f
        _positionText = "0:00"
        _isLoading = false
        isUserPaused = false
        bufferingPercent = 100
        _hasMedia = false
        _isSeeking = false
    }

    override fun seekTo(value: Float) {
        val dur = playbin.queryDuration(Format.TIME)
        if (dur > 0) {
            // Force the loading and seeking indicator before the operation
            _isSeeking = true
            _isLoading = true

            _sliderPos = value
            targetSeekPos = value

            val relPos = value / 1000f
            val seekPos = (relPos * dur).toLong()
            _positionText = formatTime(seekPos, true)

            playbin.seekSimple(Format.TIME, EnumSet.of(SeekFlags.FLUSH, SeekFlags.ACCURATE), seekPos)

            EventQueue.invokeLater {
                _positionText = formatTime(seekPos, true)
            }
        }
    }

    override fun clearError() {
        _error = null
    }

    /**
     * Toggles the fullscreen state of the video player
     */
    override fun toggleFullscreen() {
        EventQueue.invokeLater {
            _isFullscreen = !_isFullscreen
        }
    }

    // ---- Processing of a video sample ----
    /**
     * Directly reads in RGBA and copies to a Skia Bitmap in the RGBA_8888 format
     * (non-premultiplied). This avoids redundant conversions to maintain accurate colors and performance.
     */
    private fun processSample(sample: Sample) {
        try {
            val caps = sample.caps ?: return
            val structure = caps.getStructure(0) ?: return

            val width = structure.getInteger("width")
            val height = structure.getInteger("height")

            if (width != frameWidth || height != frameHeight) {
                frameWidth = width
                frameHeight = height
                updateAspectRatio()
            }

            val buffer = sample.buffer ?: return
            val byteBuffer = buffer.map(false) ?: return
            byteBuffer.rewind()

            // Prepare a Skia Bitmap
            val imageInfo = ImageInfo(
                width,
                height,
                ColorType.RGBA_8888,
                ColorAlphaType.UNPREMUL
            )

            val bitmap = Bitmap()
            bitmap.allocPixels(imageInfo)

            // Direct copy of RGBA data into a byte array
            val totalPixels = width * height
            val byteArray = ByteArray(totalPixels * 4)
            var index = 0
            repeat(totalPixels) {
                // GStreamer provides RGBA in the order R, G, B, A
                val r = byteBuffer.get().toInt() and 0xFF
                val g = byteBuffer.get().toInt() and 0xFF
                val b = byteBuffer.get().toInt() and 0xFF
                val a = byteBuffer.get().toInt() and 0xFF
                byteArray[index++] = r.toByte()
                byteArray[index++] = g.toByte()
                byteArray[index++] = b.toByte()
                byteArray[index++] = a.toByte()
            }

            // Install these pixels into the Bitmap
            bitmap.installPixels(imageInfo, byteArray, width * 4)

            // Convert the Skia Bitmap into a Compose ImageBitmap
            val imageBitmap = bitmap.asComposeImageBitmap()

            // Update on the AWT thread
            EventQueue.invokeLater {
                _currentFrame = imageBitmap
            }

            buffer.unmap()

        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    // ---- Release resources ----
    override fun dispose() {
        sliderTimer.stop()
        playbin.stop()
        playbin.dispose()
        videoSink.dispose()
        Gst.deinit()
    }
}
