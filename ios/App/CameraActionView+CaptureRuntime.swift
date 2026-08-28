import AVFoundation
import SwiftUI
import UIKit

extension CameraActionView {
    /// Captures from the session already driving the app-owned preview. Only
    /// Simulator and explicit DEBUG QA routes use deterministic image data.
    func requestCamera() {
        #if DEBUG
            if ProcessInfo.processInfo.environment["QA_CAMERA_DENIED"] == "1" {
                showDenied()
                return
            }
        #endif
        guard cameraPresentationMode == .liveCapture else {
            review(cameraFixtureData)
            return
        }
        authorizeCamera { captureLivePhoto() }
    }

    func prepareCameraPreview() {
        guard cameraPresentationMode == .liveCapture else {
            return
        }
        #if DEBUG
            if ProcessInfo.processInfo.environment["QA_CAMERA_DENIED"] == "1" {
                return
            }
        #endif
        authorizeCamera {
            cameraDenied = false
            errorMessage = nil
            liveCamera.start { showCameraUnavailable() }
        }
    }

    private func authorizeCamera(
        _ authorized: @escaping @MainActor @Sendable () -> Void
    ) {
        switch AVCaptureDevice.authorizationStatus(for: .video) {
        case .authorized:
            authorized()
        case .notDetermined:
            AVCaptureDevice.requestAccess(for: .video) { granted in
                Task { @MainActor in
                    granted ? authorized() : showDenied()
                }
            }
        case .denied, .restricted:
            showDenied()
        @unknown default:
            showDenied()
        }
    }

    private func captureLivePhoto() {
        liveCamera.start { showCameraUnavailable() }
        liveCamera.capture(flashMode: isFlashEnabled ? .on : .off) { data in
            guard let data else {
                showCameraUnavailable()
                return
            }
            review(data)
        }
    }

    var cameraFixtureData: Data {
        UIImage(named: FigmaAsset.capturePhoto.resourceName)?.pngData()
            ?? PhotoQAFixture.data
    }

    private func showDenied() {
        liveCamera.stop()
        cameraDenied = true
        errorMessage = "카메라를 사용할 수 없어요. 설정을 확인하거나 사진 보관함 또는 직접 등록을 이용하세요."
    }

    private func showCameraUnavailable() {
        liveCamera.stop()
        cameraDenied = false
        errorMessage = "카메라를 시작할 수 없어요. 사진 보관함 또는 직접 등록을 이용하세요."
    }
}
