@preconcurrency import AVFoundation
import SwiftUI
import UIKit

struct SystemCameraPicker: UIViewControllerRepresentable {
    let completion: (Data) -> Void
    let cancel: () -> Void

    func makeUIViewController(context: Context) -> CameraCaptureViewController {
        let controller = CameraCaptureViewController()
        controller.completion = completion
        controller.cancel = cancel
        return controller
    }

    func updateUIViewController(
        _ uiViewController: CameraCaptureViewController,
        context: Context
    ) {}
}

final class CameraCaptureViewController: UIViewController {
    var completion: ((Data) -> Void)?
    var cancel: (() -> Void)?
    private let session = AVCaptureSession()
    private let output = AVCapturePhotoOutput()

    override func viewDidLoad() {
        super.viewDidLoad()
        configureCapture()
        configureControls()
    }

    override func viewWillAppear(_ animated: Bool) {
        super.viewWillAppear(animated)
        DispatchQueue.global(qos: .userInitiated).async { [session] in
            session.startRunning()
        }
    }

    override func viewWillDisappear(_ animated: Bool) {
        session.stopRunning()
        super.viewWillDisappear(animated)
    }

    private func configureCapture() {
        session.beginConfiguration()
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
            session.commitConfiguration()
            return
        }
        session.addInput(input)
        session.addOutput(output)
        session.commitConfiguration()

        let preview = AVCaptureVideoPreviewLayer(session: session)
        preview.videoGravity = .resizeAspectFill
        preview.frame = view.bounds
        view.layer.addSublayer(preview)
    }

    private func configureControls() {
        let captureButton = UIButton(type: .system)
        captureButton.setTitle("촬영", for: .normal)
        captureButton.addTarget(self, action: #selector(capture), for: .touchUpInside)
        captureButton.accessibilityIdentifier = "camera.capture"
        let cancelButton = UIButton(type: .system)
        cancelButton.setTitle("취소", for: .normal)
        cancelButton.addTarget(self, action: #selector(cancelCapture), for: .touchUpInside)
        cancelButton.accessibilityIdentifier = "camera.cancel"
        let controls = UIStackView(arrangedSubviews: [cancelButton, captureButton])
        controls.spacing = 40
        controls.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(controls)
        NSLayoutConstraint.activate([
            controls.centerXAnchor.constraint(equalTo: view.centerXAnchor),
            controls.bottomAnchor.constraint(
                equalTo: view.safeAreaLayoutGuide.bottomAnchor,
                constant: -24
            )
        ])
    }

    @objc private func capture() {
        output.capturePhoto(with: AVCapturePhotoSettings(), delegate: self)
    }

    @objc private func cancelCapture() {
        cancel?()
    }
}

extension CameraCaptureViewController: @preconcurrency AVCapturePhotoCaptureDelegate {
    nonisolated func photoOutput(
        _ output: AVCapturePhotoOutput,
        didFinishProcessingPhoto photo: AVCapturePhoto,
        error: Error?
    ) {
        guard error == nil, let data = photo.fileDataRepresentation() else {
            return
        }
        Task { @MainActor in
            completion?(data)
        }
    }
}
