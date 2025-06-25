import AVFoundation
import CoreGraphics
import CoreVideo
import Foundation
import AppKit

/// Class that manages video playback and frame capture into an optimized shared buffer.
/// Frame capture rate adapts to the lower of screen refresh rate and video frame rate.
class SharedVideoPlayer {
    private var player: AVPlayer?
    private var videoOutput: AVPlayerItemVideoOutput?

    // Timer for capturing frames at adaptive rate
    private var displayLink: Timer?

    // Track the video's native frame rate
    private var videoFrameRate: Float = 0.0

    // Track the screen's refresh rate
    private var screenRefreshRate: Float = 60.0

    // The actual capture frame rate (minimum of video and screen rates)
    private var captureFrameRate: Float = 0.0

    // Shared buffer to store the frame in BGRA format (no conversion needed)
    private var frameBuffer: UnsafeMutablePointer<UInt32>?
    private var bufferCapacity: Int = 0

    // Frame dimensions
    private var frameWidth: Int = 0
    private var frameHeight: Int = 0

    // Audio volume control (0.0 to 1.0)
    private var volume: Float = 1.0

    // Flag to track if playback is active
    private var isPlaying: Bool = false
    private var isReadyForPlayback = false
    private var pendingPlay = false

    ///Two properties to store the left and right audio levels.
    private var leftAudioLevel: Float = 0.0
    private var rightAudioLevel: Float = 0.0

    // Playback speed control (1.0 is normal speed)
    private var playbackSpeed: Float = 1.0

    // Metadata properties
    private var videoTitle: String? = nil
    private var videoBitrate: Int64 = 0
    private var videoMimeType: String? = nil
    private var audioChannels: Int = 0
    private var audioSampleRate: Int = 0

    init() {
        // Detect screen refresh rate
        detectScreenRefreshRate()
    }

    /// Detects the current screen refresh rate
    private func detectScreenRefreshRate() {
        if let mainScreen = NSScreen.main {
            // Use CoreVideo DisplayLink to get refresh rate on macOS
            var displayID: CGDirectDisplayID = CGMainDisplayID()
            if let screenNumber = mainScreen.deviceDescription[
                NSDeviceDescriptionKey("NSScreenNumber")] as? NSNumber
            {
                displayID = CGDirectDisplayID(screenNumber.uint32Value)
            }

            var displayLink: CVDisplayLink?
            let error = CVDisplayLinkCreateWithCGDisplay(displayID, &displayLink)

            if error == kCVReturnSuccess, let link = displayLink {
                let period = CVDisplayLinkGetNominalOutputVideoRefreshPeriod(link)
                let timeValue = period.timeValue
                let timeScale = period.timeScale

                if timeValue > 0 && timeScale > 0 {
                    // Convert to Hz (frames per second)
                    let refreshRate = Double(timeScale) / Double(timeValue)
                    screenRefreshRate = Float(refreshRate)
                }
                // No need to release the link as Core Foundation objects are automatically memory managed
            } else {
                // Fallback if we can't get the refresh rate
                screenRefreshRate = 60.0
            }
        } else {
            screenRefreshRate = 60.0
        }
    }

    /// Extracts metadata from the asset
    private func extractMetadata(from asset: AVAsset) {
        // Reset metadata values
        videoTitle = nil
        videoBitrate = 0
        videoMimeType = nil
        audioChannels = 0
        audioSampleRate = 0

        // Extract title from metadata
        if let commonMetadata = asset.commonMetadata as? [AVMetadataItem] {
            if let titleItem = AVMetadataItem.metadataItems(from: commonMetadata, filteredByIdentifier: .commonIdentifierTitle).first,
               let title = titleItem.value as? String {
                videoTitle = title
            }
        }

        // Try to get bitrate from the asset directly
        if let urlAsset = asset as? AVURLAsset {
            // Try to get file size
            do {
                let fileAttributes = try FileManager.default.attributesOfItem(atPath: urlAsset.url.path)
                if let fileSize = fileAttributes[.size] as? NSNumber {
                    let fileSizeInBytes = fileSize.int64Value

                    // Get duration in seconds
                    let durationInSeconds = CMTimeGetSeconds(asset.duration)

                    if durationInSeconds > 0 {
                        // Calculate bitrate: (fileSize * 8) / durationInSeconds
                        let calculatedBitrate = Int64(Double(fileSizeInBytes * 8) / durationInSeconds)
                        videoBitrate = calculatedBitrate
                        print("Calculated bitrate from file size: \(calculatedBitrate) bits/s")
                    }
                }
            } catch {
                print("Error getting file attributes: \(error.localizedDescription)")
            }
        }

        // Extract format information
        if #available(macOS 13.0, *) {
            Task {
                do {
                    // Load tracks asynchronously
                    let videoTracks = try await asset.loadTracks(withMediaType: .video)
                    let audioTracks = try await asset.loadTracks(withMediaType: .audio)

                    // Extract video bitrate and format
                    if let videoTrack = videoTracks.first {
                        // Try to get estimated data rate directly from the track
                        if #available(macOS 13.0, *) {
                            do {
                                let estimatedDataRate = try await videoTrack.load(.estimatedDataRate)
                                if estimatedDataRate > 0 {
                                    videoBitrate = Int64(estimatedDataRate)
                                    print("Got bitrate from estimatedDataRate: \(videoBitrate) bits/s")
                                }
                            } catch {
                                print("Error getting estimatedDataRate: \(error.localizedDescription)")
                            }
                        }

                        // Get estimated data rate (bitrate) from format description
                        let formatDescriptions = try await videoTrack.load(.formatDescriptions)
                        if let formatDescription = formatDescriptions.first {
                            let extensions = CMFormatDescriptionGetExtensions(formatDescription) as Dictionary?
                            if let dict = extensions,
                               let bitrate = dict[kCMFormatDescriptionExtension_VerbatimSampleDescription] as? Dictionary<String, Any>,
                               let avgBitrate = bitrate["avg-bitrate"] as? Int64 {
                                videoBitrate = avgBitrate
                                print("Got bitrate from format description: \(videoBitrate) bits/s")
                            }

                            // Get MIME type
                            let mediaSubType = CMFormatDescriptionGetMediaSubType(formatDescription)
                            let mediaType = CMFormatDescriptionGetMediaType(formatDescription)

                            if mediaType == kCMMediaType_Video {
                                switch mediaSubType {
                                case kCMVideoCodecType_H264:
                                    videoMimeType = "video/h264"
                                case kCMVideoCodecType_HEVC:
                                    videoMimeType = "video/hevc"
                                case kCMVideoCodecType_MPEG4Video:
                                    videoMimeType = "video/mp4v-es"
                                case kCMVideoCodecType_MPEG2Video:
                                    videoMimeType = "video/mpeg2"
                                default:
                                    videoMimeType = "video/mp4"
                                }
                            }
                        }
                    }

                    // Extract audio channels and sample rate
                    if let audioTrack = audioTracks.first {
                        let formatDescriptions = try await audioTrack.load(.formatDescriptions)
                        if let formatDescription = formatDescriptions.first  {
                            let basicDescription = CMAudioFormatDescriptionGetStreamBasicDescription(formatDescription)
                            if let basicDesc = basicDescription {
                                audioChannels = Int(basicDesc.pointee.mChannelsPerFrame)
                                audioSampleRate = Int(basicDesc.pointee.mSampleRate)
                            }
                        }
                    }
                } catch {
                    print("Error extracting metadata: \(error.localizedDescription)")
                }
            }
        } else {
            // Fallback for older OS versions
            // Extract video bitrate and format
            if let videoTrack = asset.tracks(withMediaType: .video).first {
                // Try to get estimated data rate directly from the track
                let estimatedDataRate = videoTrack.estimatedDataRate
                if estimatedDataRate > 0 {
                    videoBitrate = Int64(estimatedDataRate)
                    print("Got bitrate from estimatedDataRate (legacy): \(videoBitrate) bits/s")
                }

                if let formatDescriptions = videoTrack.formatDescriptions as? [CMFormatDescription],
                   let formatDescription = formatDescriptions.first {
                    let extensions = CMFormatDescriptionGetExtensions(formatDescription) as Dictionary?
                    if let dict = extensions,
                       let bitrate = dict[kCMFormatDescriptionExtension_VerbatimSampleDescription] as? Dictionary<String, Any>,
                       let avgBitrate = bitrate["avg-bitrate"] as? Int64 {
                        videoBitrate = avgBitrate
                        print("Got bitrate from format description (legacy): \(videoBitrate) bits/s")
                    }

                    // Get MIME type
                    let mediaSubType = CMFormatDescriptionGetMediaSubType(formatDescription)
                    let mediaType = CMFormatDescriptionGetMediaType(formatDescription)

                    if mediaType == kCMMediaType_Video {
                        switch mediaSubType {
                        case kCMVideoCodecType_H264:
                            videoMimeType = "video/h264"
                        case kCMVideoCodecType_HEVC:
                            videoMimeType = "video/hevc"
                        case kCMVideoCodecType_MPEG4Video:
                            videoMimeType = "video/mp4v-es"
                        case kCMVideoCodecType_MPEG2Video:
                            videoMimeType = "video/mpeg2"
                        default:
                            videoMimeType = "video/mp4"
                        }
                    }
                }
            }

            // Extract audio channels and sample rate
            if let audioTrack = asset.tracks(withMediaType: .audio).first {
                if let formatDescriptions = audioTrack.formatDescriptions as? [CMAudioFormatDescription],
                   let formatDescription = formatDescriptions.first {
                    let basicDescription = CMAudioFormatDescriptionGetStreamBasicDescription(formatDescription)
                    if let basicDesc = basicDescription {
                        audioChannels = Int(basicDesc.pointee.mChannelsPerFrame)
                        audioSampleRate = Int(basicDesc.pointee.mSampleRate)
                    }
                }
            }
        }
    }

    /// Detects the video's native frame rate from its asset
    private func detectVideoFrameRate(from asset: AVAsset) {
        asset.loadTracks(withMediaType: .video) { [self] tracks, error in
            guard let videoTrack = tracks?.first, error == nil else {
                print(
                    "Erreur lors du chargement des pistes vidéo : \(error?.localizedDescription ?? "Inconnue")"
                )
                return
            }

            // Replace deprecated nominalFrameRate property
            if #available(macOS 13.0, *) {
                Task {
                    do {
                        let frameRate = try await videoTrack.load(.nominalFrameRate)
                        self.videoFrameRate = Float(frameRate)
                        if self.videoFrameRate <= 0 {
                            // Fallback to common default if detection fails
                            self.videoFrameRate = 30.0
                        }

                        // Set capture rate to the lower of the two rates
                        self.updateCaptureFrameRate()
                    } catch {
                        print("Error loading nominal frame rate: \(error.localizedDescription)")
                        // Fallback to common default if detection fails
                        self.videoFrameRate = 30.0
                        self.updateCaptureFrameRate()
                    }
                }
            } else {
                // Use deprecated property for older OS versions
                videoFrameRate = Float(videoTrack.nominalFrameRate)
                if videoFrameRate <= 0 {
                    // Fallback to common default if detection fails
                    videoFrameRate = 30.0
                }

                // Set capture rate to the lower of the two rates
                updateCaptureFrameRate()
            }
        }
    }

    /// Updates the capture frame rate based on screen and video rates
    private func updateCaptureFrameRate() {
        captureFrameRate = min(screenRefreshRate, videoFrameRate)
        // Update display link if it exists
        if isPlaying {
            configureDisplayLink()
        }
    }

    /// Opens the video from the given URI (local or network)
    func openUri(_ uri: String) {
        isReadyForPlayback = false
        pendingPlay = false

        // Determine the URL (local or network)
        let url: URL = {
            if let parsedURL = URL(string: uri), parsedURL.scheme != nil {
                return parsedURL
            } else {
                return URL(fileURLWithPath: uri)
            }
        }()

        let asset = AVURLAsset(url: url)

        // Extract metadata from the asset
        extractMetadata(from: asset)

        // Detect video frame rate
        detectVideoFrameRate(from: asset)

        // Retrieve the video track to obtain the actual dimensions
        asset.loadTracks(withMediaType: .video) { [self] tracks, error in
            guard let videoTrack = tracks?.first, error == nil else {
                print(
                    "Erreur lors du chargement des pistes vidéo : \(error?.localizedDescription ?? "Inconnue")"
                )
                return
            }

            if #available(macOS 13.0, *) {
                Task {
                    do {
                        // Use the modern API to load naturalSize and preferredTransform
                        let naturalSize = try await videoTrack.load(.naturalSize)
                        let transform = try await videoTrack.load(.preferredTransform)

                        let effectiveSize = naturalSize.applying(transform)
                        frameWidth = Int(abs(effectiveSize.width))
                        frameHeight = Int(abs(effectiveSize.height))

                        // Continue with buffer allocation and setup
                        setupFrameBuffer()
                        setupVideoOutputAndPlayer(with: asset)
                    } catch {
                        print("Error loading video track properties: \(error.localizedDescription)")
                    }
                }
            } else {
                // Fallback for older OS versions using deprecated properties
                let naturalSize = videoTrack.naturalSize
                let transform = videoTrack.preferredTransform

                let effectiveSize = naturalSize.applying(transform)
                frameWidth = Int(abs(effectiveSize.width))
                frameHeight = Int(abs(effectiveSize.height))

                // Continue with buffer allocation and setup
                setupFrameBuffer()
                setupVideoOutputAndPlayer(with: asset)
            }
        }
    }

    // Helper method to setup frame buffer
    private func setupFrameBuffer() {
        // Allocate or reuse the shared buffer if capacity matches
        let totalPixels = frameWidth * frameHeight
        if let buffer = frameBuffer, bufferCapacity == totalPixels {
            buffer.initialize(repeating: 0, count: totalPixels)
        } else {
            frameBuffer?.deallocate()
            frameBuffer = UnsafeMutablePointer<UInt32>.allocate(capacity: totalPixels)
            frameBuffer?.initialize(repeating: 0, count: totalPixels)
            bufferCapacity = totalPixels
        }
    }

    // Helper method to setup video output and player
    private func setupVideoOutputAndPlayer(with asset: AVAsset) {
        // Create attributes for the CVPixelBuffer (BGRA format) with IOSurface for better performance
        let pixelBufferAttributes: [String: Any] = [
            kCVPixelBufferPixelFormatTypeKey as String: kCVPixelFormatType_32BGRA,
            kCVPixelBufferWidthKey as String: frameWidth,
            kCVPixelBufferHeightKey as String: frameHeight,
            kCVPixelBufferIOSurfacePropertiesKey as String: [:],
        ]
        videoOutput = AVPlayerItemVideoOutput(pixelBufferAttributes: pixelBufferAttributes)

        let item = AVPlayerItem(asset: asset)
        if let output = videoOutput {
            item.add(output)
        }
        player = AVPlayer(playerItem: item)

        setupAudioTap(for: item)

        // Set initial volume
        player?.volume = volume

        // Capture initial frame
        captureInitialFrame()

        // Mark as ready for playback
        self.isReadyForPlayback = true

        // If playback was pending, start playback
        if self.pendingPlay {
            DispatchQueue.main.async {
                self.play()
            }
        }
    }

    /// Captures initial frame to display without starting the display link
    private func captureInitialFrame() {
        guard let output = videoOutput, player?.currentItem != nil else { return }

        // Seek to the beginning to ensure we have a frame
        let zeroTime = CMTime.zero
        player?.seek(to: zeroTime)

        // Try to get the first frame
        if output.hasNewPixelBuffer(forItemTime: zeroTime),
            let pixelBuffer = output.copyPixelBuffer(forItemTime: zeroTime, itemTimeForDisplay: nil)
        {
            updateLatestFrameData(from: pixelBuffer)
        }
    }

    /// Configures the timer with the appropriate frame rate
    private func configureDisplayLink() {
        stopDisplayLink()  // Ensure previous link is invalidated

        // For macOS, use a timer with the appropriate interval
        let interval = 1.0 / Double(captureFrameRate)
        Timer.scheduledTimer(withTimeInterval: interval, repeats: true) { [weak self] _ in
            self?.captureFrame()
        }
    }

    /// Stops the timer
    private func stopDisplayLink() {
        displayLink?.invalidate()
        displayLink = nil
    }

    /// Captures the latest frame from the video output if available.
    @objc private func captureFrame() {
        guard let output = videoOutput,
            let item = player?.currentItem,
            isPlaying == true
        else { return }  // Skip capture if video is not playing

        let currentTime = item.currentTime()
        if output.hasNewPixelBuffer(forItemTime: currentTime),
            let pixelBuffer = output.copyPixelBuffer(
                forItemTime: currentTime, itemTimeForDisplay: nil)
        {
            updateLatestFrameData(from: pixelBuffer)
        }
    }

    /// Directly copies the content of the pixelBuffer into the shared buffer without conversion.
    private func updateLatestFrameData(from pixelBuffer: CVPixelBuffer) {
        guard let destBuffer = frameBuffer else { return }

        CVPixelBufferLockBaseAddress(pixelBuffer, .readOnly)
        defer { CVPixelBufferUnlockBaseAddress(pixelBuffer, .readOnly) }

        guard let srcBaseAddress = CVPixelBufferGetBaseAddress(pixelBuffer) else { return }
        let width = CVPixelBufferGetWidth(pixelBuffer)
        let height = CVPixelBufferGetHeight(pixelBuffer)
        let srcBytesPerRow = CVPixelBufferGetBytesPerRow(pixelBuffer)

        guard width == frameWidth, height == frameHeight else {
            print("Unexpected dimensions: \(width)x\(height)")
            return
        }

        if srcBytesPerRow == width * 4 {
            memcpy(destBuffer, srcBaseAddress, height * srcBytesPerRow)
        } else {
            for row in 0..<height {
                let srcRow = srcBaseAddress.advanced(by: row * srcBytesPerRow)
                let destRow = destBuffer.advanced(by: row * width)
                memcpy(destRow, srcRow, width * 4)
            }
        }
    }

    /// Retrieve the audio levels.
    func getLeftAudioLevel() -> Float {
        return leftAudioLevel
    }

    func getRightAudioLevel() -> Float {
        return rightAudioLevel
    }

    // MARK: - Audio Tap Callbacks

    /// Callback: Initialization of the tap.
    private let tapInit: MTAudioProcessingTapInitCallback = { (tap, clientInfo, tapStorageOut) in
        // Initialize tap storage (e.g. to store cumulative values if needed)
        tapStorageOut.pointee = clientInfo
    }

    /// Callback: Finalize the tap.
    private let tapFinalize: MTAudioProcessingTapFinalizeCallback = { (tap) in
        // Cleanup if necessary.
    }

    /// Callback: Prepare the tap (called before processing).
    private let tapPrepare: MTAudioProcessingTapPrepareCallback = {
        (tap, maxFrames, processingFormat) in
        // You can set up buffers or other resources here if needed.
    }

    /// Callback: Unprepare the tap (called after processing).
    private let tapUnprepare: MTAudioProcessingTapUnprepareCallback = { (tap) in
        // Release any resources allocated in prepare.
    }

    /// Callback: Process audio. This is where you calculate the audio levels.
 private let tapProcess: MTAudioProcessingTapProcessCallback = {
     (tap, numberFrames, flags, bufferListInOut, numberFramesOut, flagsOut) in

     // Get the tap context (the SharedVideoPlayer instance)
     let opaqueSelf = MTAudioProcessingTapGetStorage(tap)
     let mySelf = Unmanaged<SharedVideoPlayer>.fromOpaque(opaqueSelf).takeUnretainedValue()

     var localFrames = numberFrames

     // Retrieve the audio buffers
     let status = MTAudioProcessingTapGetSourceAudio(
         tap, localFrames, bufferListInOut, flagsOut, nil, nil)
     if status != noErr {
         print("MTAudioProcessingTapGetSourceAudio failed with status: \(status)")
         return
     }

     // Process the audio buffers to calculate left and right channel levels.
     let bufferList = bufferListInOut.pointee

     // Vérifier que les buffers sont valides
     guard bufferList.mNumberBuffers > 0 else {
         print("No audio buffers available")
         return
     }

     // Vérifier le format audio (nous attendons du Float32)
     guard let mBuffers = bufferList.mBuffers.mData,
           bufferList.mBuffers.mDataByteSize > 0 else {
         print("Invalid audio buffer data")
         return
     }

     // Log des informations sur le format audio pour le débogage
     //print("Audio buffer: numBuffers=\(bufferList.mNumberBuffers), size=\(bufferList.mBuffers.mDataByteSize), frames=\(localFrames)")

     // Assuming interleaved float data (adjust if using a different format)
     let data = mBuffers.bindMemory(
         to: Float.self, capacity: Int(bufferList.mBuffers.mDataByteSize / 4))
     let frameCount = Int(localFrames)
     var leftSum: Float = 0.0
     var rightSum: Float = 0.0
     var leftCount = 0
     var rightCount = 0

     // Assuming stereo (2 channels)
     if frameCount > 0 {
         for frame in 0..<frameCount {
             if frame * 2 + 1 < Int(bufferList.mBuffers.mDataByteSize / 4) {
                 let leftSample = data[frame * 2]
                 let rightSample = data[frame * 2 + 1]
                 leftSum += abs(leftSample)
                 rightSum += abs(rightSample)
                 leftCount += 1
                 rightCount += 1
             }
         }

         // Calculate average level for each channel
         let avgLeft = leftCount > 0 ? leftSum / Float(leftCount) : 0.0
         let avgRight = rightCount > 0 ? rightSum / Float(rightCount) : 0.0

         // Update the properties
         //print("Audio levels: L=\(avgLeft), R=\(avgRight)")
         mySelf.leftAudioLevel = avgLeft
         mySelf.rightAudioLevel = avgRight
     } else {
         print("No audio frames to process")
     }

     numberFramesOut.pointee = localFrames
 }

    // Dans la méthode setupAudioTap, ajoutez une vérification du format audio et un log
    private func setupAudioTap(for playerItem: AVPlayerItem) {
        guard let asset = playerItem.asset as? AVURLAsset else {
            print("Asset is not an AVURLAsset")
            return
        }

        // Load audio tracks asynchronously
        asset.loadTracks(withMediaType: .audio) { tracks, error in
            guard let audioTrack = tracks?.first, error == nil else {
                print("No audio track found or error: \(error?.localizedDescription ?? "unknown")")
                return
            }

            print("Audio track found, setting up tap")

            // Create input parameters with a processing tap
            let inputParams = AVMutableAudioMixInputParameters(track: audioTrack)

            var callbacks = MTAudioProcessingTapCallbacks(
                version: kMTAudioProcessingTapCallbacksVersion_0,
                clientInfo: UnsafeMutableRawPointer(Unmanaged.passUnretained(self).toOpaque()),
                init: self.tapInit,
                finalize: self.tapFinalize,
                prepare: self.tapPrepare,
                unprepare: self.tapUnprepare,
                process: self.tapProcess
            )

            var tap: Unmanaged<MTAudioProcessingTap>?
            // Create the audio processing tap
            let status = MTAudioProcessingTapCreate(
                kCFAllocatorDefault, &callbacks, kMTAudioProcessingTapCreationFlag_PostEffects, &tap
            )
            if status == noErr, let tap = tap {
                print("Audio tap created successfully")
                inputParams.audioTapProcessor = tap.takeRetainedValue()
                let audioMix = AVMutableAudioMix()
                audioMix.inputParameters = [inputParams]
                playerItem.audioMix = audioMix
            } else {
                print("Audio Tap creation failed with status: \(status)")
            }
        }
    }

    /// Starts video playback and begins frame capture at the optimized frame rate.
    func play() {
        if isReadyForPlayback {
            isPlaying = true
            player?.play()
            configureDisplayLink()
        } else {
            // Marquer qu'une lecture est en attente
            pendingPlay = true
        }
    }

    /// Pauses video playback and stops frame capture.
    func pause() {
        isPlaying = false
        player?.pause()
        stopDisplayLink()

        // Capture the current frame to display while paused
        if let output = videoOutput, let item = player?.currentItem {
            let currentTime = item.currentTime()
            if output.hasNewPixelBuffer(forItemTime: currentTime),
                let pixelBuffer = output.copyPixelBuffer(
                    forItemTime: currentTime, itemTimeForDisplay: nil)
            {
                updateLatestFrameData(from: pixelBuffer)
            }
        }
    }

    /// Sets the volume level (0.0 to 1.0)
    func setVolume(level: Float) {
        volume = max(0.0, min(1.0, level))  // Clamp between 0.0 and 1.0

        // Manage the multi-channel case (>2 channels)
        if let playerItem = player?.currentItem, audioChannels > 2 {
            // Apply volume via an AudioMix if we have more than 2 channels
            if let audioTrack = playerItem.asset.tracks(withMediaType: .audio).first {
                let parameters = AVMutableAudioMixInputParameters(track: audioTrack)
                parameters.setVolume(volume, at: CMTime.zero)

                let audioMix = AVMutableAudioMix()
                audioMix.inputParameters = [parameters]
                playerItem.audioMix = audioMix
            }
        } else {
            // For stereo and mono channels, use the standard method
            player?.volume = volume
        }
    }

    /// Gets the current volume level (0.0 to 1.0)
    func getVolume() -> Float {
        return volume
    }

    /// Sets the playback speed (0.5 to 2.0, where 1.0 is normal speed)
    func setPlaybackSpeed(speed: Float) {
        playbackSpeed = max(0.5, min(2.0, speed))  // Clamp between 0.5 and 2.0
        player?.rate = playbackSpeed
    }

    /// Gets the current playback speed (0.5 to 2.0, where 1.0 is normal speed)
    func getPlaybackSpeed() -> Float {
        return playbackSpeed
    }

    /// Returns a pointer to the shared frame buffer. The caller should not free this pointer.
    func getLatestFramePointer() -> UnsafeMutablePointer<UInt32>? {
        return frameBuffer
    }

    /// Returns the width of the video frame in pixels
    func getFrameWidth() -> Int { return frameWidth }

    /// Returns the height of the video frame in pixels
    func getFrameHeight() -> Int { return frameHeight }

    /// Returns the detected video frame rate
    func getVideoFrameRate() -> Float { return videoFrameRate }

    /// Returns the detected screen refresh rate
    func getScreenRefreshRate() -> Float { return screenRefreshRate }

    /// Returns the current capture frame rate (minimum of video and screen rates)
    func getCaptureFrameRate() -> Float { return captureFrameRate }

    /// Returns the video title if available
    func getVideoTitle() -> String? { return videoTitle }

    /// Returns the video bitrate in bits per second
    func getVideoBitrate() -> Int64 { return videoBitrate }

    /// Returns the video MIME type if available
    func getVideoMimeType() -> String? { return videoMimeType }

    /// Returns the number of audio channels
    func getAudioChannels() -> Int { return audioChannels }

    /// Returns the audio sample rate in Hz
    func getAudioSampleRate() -> Int { return audioSampleRate }

    /// Returns the duration of the video in seconds.
    func getDuration() -> Double {
        guard let item = player?.currentItem else { return 0 }

        if #available(macOS 13.0, *) {
            // Use the modern API with async/await
            Task {
                do {
                    let duration = try await item.asset.load(.duration)
                    return CMTimeGetSeconds(duration)
                } catch {
                    print("Error loading duration: \(error.localizedDescription)")
                    return 0
                }
            }
            // Return the current value while the task is running
            return CMTimeGetSeconds(item.asset.duration)
        } else {
            // Use deprecated property for older OS versions
            return CMTimeGetSeconds(item.asset.duration)
        }
    }

    /// Returns the current playback time in seconds.
    func getCurrentTime() -> Double {
        guard let item = player?.currentItem else { return 0 }
        return CMTimeGetSeconds(item.currentTime())
    }

    /// Seeks to the specified time (in seconds).
    func seekTo(time: Double) {
        guard let player = player else { return }
        let newTime = CMTime(seconds: time, preferredTimescale: 600)
        player.seek(to: newTime)

        // Update frame at the new position if paused
        if !isPlaying, let output = videoOutput {
            if output.hasNewPixelBuffer(forItemTime: newTime),
                let pixelBuffer = output.copyPixelBuffer(
                    forItemTime: newTime, itemTimeForDisplay: nil)
            {
                updateLatestFrameData(from: pixelBuffer)
            }
        }
    }

    /// Disposes of the video player and releases resources.
    func dispose() {
        pause()
        player = nil
        videoOutput = nil
        if let buffer = frameBuffer {
            buffer.deallocate()
            frameBuffer = nil
            bufferCapacity = 0
        }
    }
}

/// MARK: - C Exported Functions for JNA

@_cdecl("createVideoPlayer")
public func createVideoPlayer() -> UnsafeMutableRawPointer? {
    let player = SharedVideoPlayer()
    return Unmanaged.passRetained(player).toOpaque()
}

@_cdecl("openUri")
public func openUri(_ context: UnsafeMutableRawPointer?, _ uri: UnsafePointer<CChar>?) {
    guard let context = context,
        let uriCStr = uri,
        let swiftUri = String(validatingUTF8: uriCStr)
    else {
        print("Invalid parameters for openUri")
        return
    }
    let player = Unmanaged<SharedVideoPlayer>.fromOpaque(context).takeUnretainedValue()
    // Use a background queue for heavy operations to avoid blocking the main thread
    DispatchQueue.global(qos: .userInitiated).async {
        player.openUri(swiftUri)
    }
}

@_cdecl("playVideo")
public func playVideo(_ context: UnsafeMutableRawPointer?) {
    guard let context = context else { return }
    let player = Unmanaged<SharedVideoPlayer>.fromOpaque(context).takeUnretainedValue()
    DispatchQueue.main.async {
        player.play()
    }
}

@_cdecl("pauseVideo")
public func pauseVideo(_ context: UnsafeMutableRawPointer?) {
    guard let context = context else { return }
    let player = Unmanaged<SharedVideoPlayer>.fromOpaque(context).takeUnretainedValue()
    DispatchQueue.main.async {
        player.pause()
    }
}

@_cdecl("setVolume")
public func setVolume(_ context: UnsafeMutableRawPointer?, _ volume: Float) {
    guard let context = context else { return }
    let player = Unmanaged<SharedVideoPlayer>.fromOpaque(context).takeUnretainedValue()
    DispatchQueue.main.async {
        player.setVolume(level: volume)
    }
}

@_cdecl("getVolume")
public func getVolume(_ context: UnsafeMutableRawPointer?) -> Float {
    guard let context = context else { return 0.0 }
    let player = Unmanaged<SharedVideoPlayer>.fromOpaque(context).takeUnretainedValue()
    return player.getVolume()
}

@_cdecl("getLatestFrame")
public func getLatestFrame(_ context: UnsafeMutableRawPointer?) -> UnsafeMutableRawPointer? {
    guard let context = context else { return nil }
    let player = Unmanaged<SharedVideoPlayer>.fromOpaque(context).takeUnretainedValue()
    if let ptr = player.getLatestFramePointer() {
        return UnsafeMutableRawPointer(ptr)
    }
    return nil
}

@_cdecl("getFrameWidth")
public func getFrameWidth(_ context: UnsafeMutableRawPointer?) -> Int32 {
    guard let context = context else { return 0 }
    let player = Unmanaged<SharedVideoPlayer>.fromOpaque(context).takeUnretainedValue()
    return Int32(player.getFrameWidth())
}

@_cdecl("getFrameHeight")
public func getFrameHeight(_ context: UnsafeMutableRawPointer?) -> Int32 {
    guard let context = context else { return 0 }
    let player = Unmanaged<SharedVideoPlayer>.fromOpaque(context).takeUnretainedValue()
    return Int32(player.getFrameHeight())
}

@_cdecl("getVideoFrameRate")
public func getVideoFrameRate(_ context: UnsafeMutableRawPointer?) -> Float {
    guard let context = context else { return 0.0 }
    let player = Unmanaged<SharedVideoPlayer>.fromOpaque(context).takeUnretainedValue()
    return player.getVideoFrameRate()
}

@_cdecl("getScreenRefreshRate")
public func getScreenRefreshRate(_ context: UnsafeMutableRawPointer?) -> Float {
    guard let context = context else { return 0.0 }
    let player = Unmanaged<SharedVideoPlayer>.fromOpaque(context).takeUnretainedValue()
    return player.getScreenRefreshRate()
}

@_cdecl("getCaptureFrameRate")
public func getCaptureFrameRate(_ context: UnsafeMutableRawPointer?) -> Float {
    guard let context = context else { return 0.0 }
    let player = Unmanaged<SharedVideoPlayer>.fromOpaque(context).takeUnretainedValue()
    return player.getCaptureFrameRate()
}

@_cdecl("getVideoDuration")
public func getVideoDuration(_ context: UnsafeMutableRawPointer?) -> Double {
    guard let context = context else { return 0 }
    let player = Unmanaged<SharedVideoPlayer>.fromOpaque(context).takeUnretainedValue()
    return player.getDuration()
}

@_cdecl("getCurrentTime")
public func getCurrentTime(_ context: UnsafeMutableRawPointer?) -> Double {
    guard let context = context else { return 0 }
    let player = Unmanaged<SharedVideoPlayer>.fromOpaque(context).takeUnretainedValue()
    return player.getCurrentTime()
}

@_cdecl("seekTo")
public func seekTo(_ context: UnsafeMutableRawPointer?, _ time: Double) {
    guard let context = context else { return }
    let player = Unmanaged<SharedVideoPlayer>.fromOpaque(context).takeUnretainedValue()
    DispatchQueue.main.async {
        player.seekTo(time: time)
    }
}

@_cdecl("disposeVideoPlayer")
public func disposeVideoPlayer(_ context: UnsafeMutableRawPointer?) {
    guard let context = context else { return }
    let player = Unmanaged<SharedVideoPlayer>.fromOpaque(context).takeRetainedValue()
    DispatchQueue.main.async {
        player.dispose()
    }
}

@_cdecl("getLeftAudioLevel")
public func getLeftAudioLevel(_ context: UnsafeMutableRawPointer?) -> Float {
    guard let context = context else { return 0.0 }
    let player = Unmanaged<SharedVideoPlayer>.fromOpaque(context).takeUnretainedValue()
    return player.getLeftAudioLevel()
}

@_cdecl("getRightAudioLevel")
public func getRightAudioLevel(_ context: UnsafeMutableRawPointer?) -> Float {
    guard let context = context else { return 0.0 }
    let player = Unmanaged<SharedVideoPlayer>.fromOpaque(context).takeUnretainedValue()
    return player.getRightAudioLevel()
}

@_cdecl("setPlaybackSpeed")
public func setPlaybackSpeed(_ context: UnsafeMutableRawPointer?, _ speed: Float) {
    guard let context = context else { return }
    let player = Unmanaged<SharedVideoPlayer>.fromOpaque(context).takeUnretainedValue()
    DispatchQueue.main.async {
        player.setPlaybackSpeed(speed: speed)
    }
}

@_cdecl("getPlaybackSpeed")
public func getPlaybackSpeed(_ context: UnsafeMutableRawPointer?) -> Float {
    guard let context = context else { return 1.0 }
    let player = Unmanaged<SharedVideoPlayer>.fromOpaque(context).takeUnretainedValue()
    return player.getPlaybackSpeed()
}

@_cdecl("getVideoTitle")
public func getVideoTitle(_ context: UnsafeMutableRawPointer?) -> UnsafePointer<CChar>? {
    guard let context = context else { return nil }
    let player = Unmanaged<SharedVideoPlayer>.fromOpaque(context).takeUnretainedValue()
    if let title = player.getVideoTitle() {
        let cString = strdup(title)
        return UnsafePointer<CChar>(cString)
    }
    return nil
}

@_cdecl("getVideoBitrate")
public func getVideoBitrate(_ context: UnsafeMutableRawPointer?) -> Int64 {
    guard let context = context else { return 0 }
    let player = Unmanaged<SharedVideoPlayer>.fromOpaque(context).takeUnretainedValue()
    return player.getVideoBitrate()
}

@_cdecl("getVideoMimeType")
public func getVideoMimeType(_ context: UnsafeMutableRawPointer?) -> UnsafePointer<CChar>? {
    guard let context = context else { return nil }
    let player = Unmanaged<SharedVideoPlayer>.fromOpaque(context).takeUnretainedValue()
    if let mimeType = player.getVideoMimeType() {
        let cString = strdup(mimeType)
        return UnsafePointer<CChar>(cString)
    }
    return nil
}

@_cdecl("getAudioChannels")
public func getAudioChannels(_ context: UnsafeMutableRawPointer?) -> Int32 {
    guard let context = context else { return 0 }
    let player = Unmanaged<SharedVideoPlayer>.fromOpaque(context).takeUnretainedValue()
    return Int32(player.getAudioChannels())
}

@_cdecl("getAudioSampleRate")
public func getAudioSampleRate(_ context: UnsafeMutableRawPointer?) -> Int32 {
    guard let context = context else { return 0 }
    let player = Unmanaged<SharedVideoPlayer>.fromOpaque(context).takeUnretainedValue()
    return Int32(player.getAudioSampleRate())
}
