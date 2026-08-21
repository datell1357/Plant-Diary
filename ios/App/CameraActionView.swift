import AVFoundation
import PhotosUI
import PlanteriorData
import PlanteriorDesignSystem
import SwiftUI

/// Figma `plant-capture-flow-board` steps 1–2 (figma-analysis §6.11). Step 1 is
/// the black camera chrome, step 2 the photo review. Steps 3–4 are owned by
/// `IdentificationFlowView`, which this view hands off to on consent.
struct CameraActionView: View {
    @Environment(\.sizeCategory) var sizeCategory
    let dismiss: () -> Void
    let complete: () -> Void
    let manualRegistration: () -> Void
    let restoresReviewedPhoto: Bool
    @State var pickerItem: PhotosPickerItem?
    @State var draft: NormalizedPhoto?
    @State var errorMessage: String?
    @State var showsCamera = false
    @State var showsLibrary = false
    @State var showsAcknowledgement = false
    @State var cameraDenied = false
    private let consent = PhotoConsentCoordinator(transfer: IdentificationDraftStore.shared)

    init(
        dismiss: @escaping () -> Void,
        complete: @escaping () -> Void,
        manualRegistration: @escaping () -> Void,
        restoresReviewedPhoto: Bool = false
    ) {
        self.dismiss = dismiss
        self.complete = complete
        self.manualRegistration = manualRegistration
        self.restoresReviewedPhoto = restoresReviewedPhoto
    }

    var body: some View {
        Group {
            if draft == nil {
                cameraSurface
            } else {
                photoReviewSurface
            }
        }
        .task {
            await restoreReviewedPhoto()
        }
        .photosPicker(isPresented: $showsLibrary, selection: $pickerItem, matching: .images)
        .onChange(of: pickerItem) { _, item in
            guard let item else {
                return
            }
            Task {
                await load(item)
            }
        }
        .fullScreenCover(isPresented: $showsCamera) {
            SystemCameraPicker { data in
                showsCamera = false
                review(data)
            } cancel: {
                showsCamera = false
            }
        }
        .alert("사진 처리 안내", isPresented: $showsAcknowledgement) {
            Button("동의하고 계속") {
                Task {
                    await consent.acknowledgeAndTransfer()
                    complete()
                }
            }
            Button("취소", role: .cancel) {
                Task {
                    await consent.declineAcknowledgement()
                }
            }
        } message: {
            Text("선택한 사진은 식물 식별을 위해 처리되며 동의 전에는 전송되지 않습니다.")
        }
    }

    var libraryControl: some View {
        Button {
            showsLibrary = true
        } label: {
            captureControlLabel(
                systemImage: "photo.on.rectangle",
                title: "사진 보관함",
                labelID: "capture.library.label"
            )
        }
        .accessibilityLabel("사진 보관함")
        .accessibilityIdentifier("capture.library")
    }

    func openSettings() {
        guard let url = URL(string: UIApplication.openSettingsURLString) else {
            return
        }
        UIApplication.shared.open(url)
    }

    /// Returns from review to the camera step without discarding permission or
    /// consent state.
    func discardDraft() {
        draft = nil
        pickerItem = nil
        errorMessage = nil
        Task { await consent.cancelSelection() }
    }

    /// The shutter and the switch control invoke the real capture stack. The app
    /// never draws a substitute camera: on denial it surfaces recovery instead.
    func requestCamera() {
        #if DEBUG
            if ProcessInfo.processInfo.environment["QA_CAMERA_DENIED"] == "1" {
                showDenied()
                return
            }
        #endif
        switch AVCaptureDevice.authorizationStatus(for: .video) {
        case .authorized:
            showsCamera = true
        case .notDetermined:
            AVCaptureDevice.requestAccess(for: .video) { granted in
                Task { @MainActor in
                    granted ? (showsCamera = true) : showDenied()
                }
            }
        case .denied, .restricted:
            showDenied()
        @unknown default:
            showDenied()
        }
    }

    private func showDenied() {
        cameraDenied = true
        errorMessage = "카메라를 사용할 수 없어요. 설정을 확인하거나 사진 보관함 또는 직접 등록을 이용하세요."
    }

    private func load(_ item: PhotosPickerItem) async {
        do {
            guard let data = try await item.loadTransferable(type: Data.self) else {
                throw PhotoInputError.emptyAsset
            }
            review(data)
        } catch {
            errorMessage = "사진을 읽을 수 없어요. 다른 사진을 선택하세요."
        }
    }

    private func review(_ data: Data) {
        do {
            draft = try PhotoImagePipeline().normalize(data)
            if let draft {
                Task {
                    await consent.review(draft)
                }
            }
            errorMessage = nil
        } catch {
            errorMessage = "사진 형식이나 크기를 확인하고 다시 선택하세요."
        }
    }

    @MainActor
    private func restoreReviewedPhoto() async {
        #if DEBUG
            switch ProcessInfo.processInfo.environment["QA_PHOTO_FIXTURE"] {
            case "valid":
                let fixture = UIImage(named: FigmaAsset.capturePhoto.resourceName)?
                    .pngData() ?? PhotoQAFixture.data
                review(fixture)
            case "corrupt":
                review(Data("corrupt".utf8))
            default:
                break
            }
        #endif
        guard draft == nil,
              restoresReviewedPhoto,
              let retained = await IdentificationDraftStore.shared.load()
        else {
            return
        }
        draft = retained
        await consent.review(retained)
    }
}
