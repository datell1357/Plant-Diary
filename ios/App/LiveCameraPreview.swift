@preconcurrency import AVFoundation
import SwiftUI
import UIKit

struct LiveCameraPreview: UIViewRepresentable {
    let session: AVCaptureSession

    func makeUIView(context: Context) -> LiveCameraPreviewView {
        let view = LiveCameraPreviewView()
        view.previewLayer.session = session
        return view
    }

    func updateUIView(_ uiView: LiveCameraPreviewView, context: Context) {
        uiView.previewLayer.session = session
    }

    static func dismantleUIView(_ uiView: LiveCameraPreviewView, coordinator: Void) {
        uiView.previewLayer.session = nil
    }
}

final class LiveCameraPreviewView: UIView {
    let previewLayer = AVCaptureVideoPreviewLayer()

    override init(frame: CGRect) {
        super.init(frame: frame)
        configurePreviewLayer()
    }

    required init?(coder: NSCoder) {
        super.init(coder: coder)
        configurePreviewLayer()
    }

    override func layoutSubviews() {
        super.layoutSubviews()
        previewLayer.frame = bounds
    }

    private func configurePreviewLayer() {
        previewLayer.videoGravity = .resizeAspectFill
        layer.addSublayer(previewLayer)
    }
}
