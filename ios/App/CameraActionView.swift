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
    @Environment(\.scenePhase) var scenePhase
    let dismiss: () -> Void
    let complete: () -> Void
    let manualRegistration: () -> Void
    let restoresReviewedPhoto: Bool
    @State var pickerItem: PhotosPickerItem?
    @State var draft: NormalizedPhoto?
    @State var photos: [NormalizedPhoto] = []
    @State var errorMessage: String?
    @State var showsLibrary = false
    @State var showsAcknowledgement = false
    @State var cameraDenied = false
    @State var isFlashEnabled = false
    @State var liveCamera = LiveCameraCapture()
    let cameraPresentationMode: CameraPresentationMode
    private let consent = PhotoConsentCoordinator(transfer: IdentificationDraftStore.shared)

    static var cameraBackdrop: Color {
        .black
    }

    init(
        dismiss: @escaping () -> Void,
        complete: @escaping () -> Void,
        manualRegistration: @escaping () -> Void,
        restoresReviewedPhoto: Bool = false,
        cameraPresentationMode: CameraPresentationMode = CameraPresentationPolicy.current
    ) {
        self.dismiss = dismiss
        self.complete = complete
        self.manualRegistration = manualRegistration
        self.restoresReviewedPhoto = restoresReviewedPhoto
        self.cameraPresentationMode = cameraPresentationMode
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
            Text("선택한 사진 \(photos.count)장은 식물 식별을 위해 처리되며 동의 전에는 전송되지 않습니다.")
        }
    }

    var usesFigmaPhotoFixture: Bool {
        #if DEBUG
            ProcessInfo.processInfo.environment["QA_PHOTO_FIXTURE"] == "valid"
        #else
            false
        #endif
    }

    var libraryControl: some View {
        Button {
            showsLibrary = true
        } label: {
            CameraControlLabel(
                systemImage: CaptureLayoutMetrics.gallerySystemImage,
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
        photos = []
        pickerItem = nil
        errorMessage = nil
        Task { await consent.cancelSelection() }
    }

    func captureMore() {
        draft = nil
        pickerItem = nil
        errorMessage = nil
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

    func review(_ data: Data) {
        liveCamera.stop()
        do {
            guard photos.count < Self.maximumPhotoCount else {
                return
            }
            let normalized = try PhotoImagePipeline().normalize(data)
            photos.append(normalized)
            draft = normalized
            if !photos.isEmpty {
                Task {
                    await consent.review(photos)
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
                review(cameraFixtureData)
            case "corrupt":
                review(Data("corrupt".utf8))
            default:
                break
            }
        #endif
        guard draft == nil,
              restoresReviewedPhoto,
              let retained = await IdentificationDraftStore.shared.load(),
              let latest = retained.last
        else {
            return
        }
        photos = retained
        draft = latest
        await consent.review(retained)
    }

    static let maximumPhotoCount = 5
}
