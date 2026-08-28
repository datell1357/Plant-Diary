@preconcurrency import AVFoundation
import Foundation

/// One session shared by the app-owned preview and shutter. Blocking session
/// work and mutable capture state are confined to `sessionQueue`.
final class LiveCameraCapture: @unchecked Sendable {
    let session = AVCaptureSession()

    private enum ConfigurationState {
        case unconfigured
        case configured
        case failed
    }

    private let output = AVCapturePhotoOutput()
    private let sessionQueue = DispatchQueue(
        label: "com.planterior.camera.capture-session",
        qos: .userInitiated
    )
    private var configurationState = ConfigurationState.unconfigured
    private var isCapturing = false
    private var processors: [Int64: LivePhotoCaptureProcessor] = [:]

    func start(
        onUnavailable: @escaping @MainActor @Sendable () -> Void
    ) {
        sessionQueue.async { [self] in
            guard configureIfNeeded() else {
                Task { @MainActor in onUnavailable() }
                return
            }
            guard !session.isRunning else {
                return
            }
            session.startRunning()
        }
    }

    func stop() {
        sessionQueue.async { [session] in
            guard session.isRunning else {
                return
            }
            session.stopRunning()
        }
    }

    func capture(
        flashMode: AVCaptureDevice.FlashMode,
        completion: @escaping @MainActor @Sendable (Data?) -> Void
    ) {
        sessionQueue.async { [self] in
            guard configurationState == .configured,
                  session.isRunning,
                  !isCapturing
            else {
                Task { @MainActor in completion(nil) }
                return
            }

            isCapturing = true
            let settings = AVCapturePhotoSettings()
            if output.supportedFlashModes.contains(flashMode) {
                settings.flashMode = flashMode
            }
            settings.photoQualityPrioritization = .quality
            if let connection = output.connection(with: .video) {
                let portraitRotation: CGFloat = 90
                if connection.isVideoRotationAngleSupported(portraitRotation) {
                    connection.videoRotationAngle = portraitRotation
                }
            }

            let identifier = settings.uniqueID
            let processor = LivePhotoCaptureProcessor(
                completion: completion
            ) { [weak self] in
                self?.sessionQueue.async { [weak self] in
                    self?.processors[identifier] = nil
                    self?.isCapturing = false
                }
            }
            processors[identifier] = processor
            output.capturePhoto(with: settings, delegate: processor)
        }
    }

    private func configureIfNeeded() -> Bool {
        switch configurationState {
        case .configured:
            return true
        case .failed:
            return false
        case .unconfigured:
            break
        }

        session.beginConfiguration()
        defer { session.commitConfiguration() }
        session.sessionPreset = .photo
        guard let camera = AVCaptureDevice.default(
            .builtInWideAngleCamera,
            for: .video,
            position: .back
        ),
            let input = try? AVCaptureDeviceInput(device: camera),
            session.canAddInput(input),
            session.canAddOutput(output)
        else {
            configurationState = .failed
            return false
        }

        session.addInput(input)
        session.addOutput(output)
        output.maxPhotoQualityPrioritization = .quality
        configurationState = .configured
        return true
    }
}

private final class LivePhotoCaptureProcessor: NSObject, @unchecked Sendable {
    private let completion: @MainActor @Sendable (Data?) -> Void
    private let finished: @Sendable () -> Void
    private var photoData: Data?

    init(
        completion: @escaping @MainActor @Sendable (Data?) -> Void,
        finished: @escaping @Sendable () -> Void
    ) {
        self.completion = completion
        self.finished = finished
    }
}

extension LivePhotoCaptureProcessor: AVCapturePhotoCaptureDelegate {
    func photoOutput(
        _ output: AVCapturePhotoOutput,
        didFinishProcessingPhoto photo: AVCapturePhoto,
        error: Error?
    ) {
        if error == nil {
            photoData = photo.fileDataRepresentation()
        }
    }

    func photoOutput(
        _ output: AVCapturePhotoOutput,
        didFinishCaptureFor resolvedSettings: AVCaptureResolvedPhotoSettings,
        error: Error?
    ) {
        let data = error == nil ? photoData : nil
        Task { @MainActor in completion(data) }
        finished()
    }
}
