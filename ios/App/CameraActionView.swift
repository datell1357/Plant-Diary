import AVFoundation
import PhotosUI
import PlanteriorData
import PlanteriorDesignSystem
import SwiftUI

struct CameraActionView: View {
    let dismiss: () -> Void
    let complete: () -> Void
    let manualRegistration: () -> Void
    @State private var pickerItem: PhotosPickerItem?
    @State private var draft: NormalizedPhoto?
    @State private var errorMessage: String?
    @State private var showsCamera = false
    @State private var showsAcknowledgement = false
    @State private var cameraDenied = false
    private let consent = PhotoConsentCoordinator(transfer: IdentificationDraftStore.shared)

    var body: some View {
        VStack(spacing: 20) {
            if let draft {
                Image(uiImage: UIImage(data: draft.data) ?? UIImage())
                    .resizable()
                    .scaledToFit()
                    .frame(maxHeight: 260)
                    .accessibilityIdentifier("photo.review")
                Text("이 사진을 식물 식별에 사용할까요?")
                    .font(PlanteriorTypography.body)
                actionButtons
            } else {
                sourceButtons
            }
            errorView
            if cameraDenied {
                Button("설정 열기") {
                    guard let url = URL(string: UIApplication.openSettingsURLString) else {
                        return
                    }
                    UIApplication.shared.open(url)
                }
                .accessibilityIdentifier("photo.settings")
            }
            Button("직접 등록") {
                manualRegistration()
            }
            .accessibilityIdentifier("photo.manual")
            Button("닫기", action: dismiss)
                .accessibilityIdentifier("camera.dismiss")
        }
        .padding(24)
        .presentationDetents([.large])
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("camera.sheet")
        .task {
            loadQAFixtureIfPresent()
        }
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

    private var sourceButtons: some View {
        VStack(spacing: 12) {
            Image(systemName: "camera.fill")
                .font(.system(size: 52))
                .foregroundStyle(PlanteriorPalette.accent.color)
                .accessibilityHidden(true)
            Text("식물 사진 추가")
                .font(PlanteriorTypography.screenTitle)
            PlanteriorPrimaryButton("카메라로 촬영") {
                requestCamera()
            }
            .accessibilityIdentifier("photo.camera")
            PhotosPicker(selection: $pickerItem, matching: .images) {
                Text("사진 선택")
                    .frame(maxWidth: .infinity, minHeight: 44)
            }
            .buttonStyle(.bordered)
            .accessibilityIdentifier("photo.library")
        }
    }

    @ViewBuilder
    private var errorView: some View {
        if let errorMessage {
            Text(errorMessage)
                .foregroundStyle(.red)
                .accessibilityIdentifier("photo.error")
        }
    }

    private var actionButtons: some View {
        VStack(spacing: 12) {
            HStack {
                Button("다시 촬영") {
                    requestCamera()
                }
                .accessibilityIdentifier("photo.retake")
                PhotosPicker(selection: $pickerItem, matching: .images) {
                    Text("다시 선택")
                }
                .accessibilityIdentifier("photo.replace")
            }
            PlanteriorPrimaryButton("이 사진 사용") {
                showsAcknowledgement = true
            }
            .accessibilityIdentifier("photo.acknowledge")
        }
    }

    private func requestCamera() {
        if ProcessInfo.processInfo.environment["QA_CAMERA_DENIED"] == "1" {
            showDenied()
            return
        }
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
        errorMessage = "카메라를 사용할 수 없어요. 설정을 확인하거나 사진 선택 또는 직접 등록을 이용하세요."
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

    private func loadQAFixtureIfPresent() {
        switch ProcessInfo.processInfo.environment["QA_PHOTO_FIXTURE"] {
        case "valid":
            review(PhotoQAFixture.data)
        case "corrupt":
            review(Data("corrupt".utf8))
        default:
            break
        }
    }
}
