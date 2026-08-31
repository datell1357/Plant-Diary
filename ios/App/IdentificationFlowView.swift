import PlanteriorData
import PlanteriorDesignSystem
import PlanteriorDomain
import SwiftUI

/// Figma `plant-capture-flow-board` steps 3–4 (figma-analysis §6.11): the AI
/// identifying state and the identification result. Empty, failure, and retry
/// remain first-class states rather than dead ends.
struct IdentificationFlowView: View {
    @Environment(\.dismiss) var dismiss
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Environment(\.sizeCategory) var sizeCategory
    @ScaledMetric(relativeTo: .headline) var identifyingHeadlineFontSize: CGFloat = 19
    @ScaledMetric(relativeTo: .caption) var identifyingHintFontSize: CGFloat = 15
    let revisePhoto: (() -> Void)?
    let completeRegistration: (() -> Void)?
    @State var state = IdentificationState.pending
    @State var selectedCandidate: IdentificationCandidate?
    @State var showsRegistration = false
    @State var showsManualRegistration = false
    @State var showsBlankManualRegistration = false
    @State var submittedPhoto: Data?
    @State private var failureRetryCount = 0
    private let coordinator = PlantIdentificationCoordinator(
        service: PlantIdentificationServiceFactory.current()
    )

    init(
        revisePhoto: (() -> Void)? = nil,
        completeRegistration: (() -> Void)? = nil
    ) {
        self.revisePhoto = revisePhoto
        self.completeRegistration = completeRegistration
    }

    var body: some View {
        stateContent
            // The whole flow owns its own Figma chrome, so the bar stays hidden
            // for every state. Toggling it per state animates the navigation bar
            // in and out mid-transition, which never settles into idle.
            .navigationBarBackButtonHidden(true)
            .toolbar(.hidden, for: .navigationBar)
            .navigationDestination(isPresented: $showsRegistration) {
                PlantRegistrationView(
                    method: .identified,
                    candidate: selectedCandidate,
                    onRegistered: completeRegistration
                )
            }
            .navigationDestination(isPresented: $showsManualRegistration) {
                PlantRegistrationView(
                    method: .identified,
                    candidate: selectedCandidate,
                    onRegistered: completeRegistration
                )
            }
            .navigationDestination(isPresented: $showsBlankManualRegistration) {
                PlantRegistrationView(onRegistered: completeRegistration)
            }
            .task { await identifyDraft() }
    }

    var analysisCompleteLabel: some View {
        Text("분석 완료")
            .font(PlanteriorTypography.caption)
            .foregroundStyle(PlanteriorPalette.textSecondary.color)
            .fixedSize(horizontal: false, vertical: true)
    }

    func returnToReviewedPhoto() {
        if let revisePhoto {
            revisePhoto()
        } else {
            dismiss()
        }
    }

    var effectiveReduceMotion: Bool {
        reduceMotion
            || ProcessInfo.processInfo.environment["QA_REDUCE_MOTION"] == "1"
    }

    var usesStaticCapturePhase: Bool {
        effectiveReduceMotion
            || ProcessInfo.processInfo.environment["QA_CAPTURE_STATIC_PHASE"] == "1"
    }

    var usesFigmaPhotoFixture: Bool {
        #if DEBUG
            ProcessInfo.processInfo.environment["QA_PHOTO_FIXTURE"] == "valid"
        #else
            false
        #endif
    }

    @ViewBuilder
    private var stateContent: some View {
        switch state {
        case .pending:
            identifyingSurface
        case let .candidates(candidates):
            resultSurface(candidates)
        case .awaitingPhoto:
            fallbackState(
                message: "사진을 다시 선택해 주세요.",
                identifier: nil
            )
        case .noCandidates:
            fallbackState(
                message: "비슷한 식물을 찾지 못했어요.",
                identifier: "identification.empty"
            ) {
                PlanteriorSecondaryButton("다른 사진 선택") { dismiss() }
                    .accessibilityIdentifier("identification.replace")
            }
        case .failed:
            fallbackState(
                message: "식별에 실패했어요.",
                identifier: "identification.failed"
            ) {
                PlanteriorSecondaryButton("다시 시도") {
                    state = .pending
                    failureRetryCount += 1
                    Task { await identifyDraft() }
                }
                .accessibilityIdentifier("identification.retry")
            }
        }
    }

    private func fallbackState(
        message: String,
        identifier: String?,
        @ViewBuilder actions: () -> some View = { EmptyView() }
    ) -> some View {
        VStack(spacing: PlanteriorSpacing.large) {
            Text(message)
                .font(PlanteriorTypography.body)
                .multilineTextAlignment(.center)
                .accessibilityIdentifier(identifier ?? "identification.state")
            actions()
            NavigationLink("직접 입력", destination: PlantRegistrationView())
                .accessibilityIdentifier("identification.manual")
            // The navigation bar is hidden for this flow, so every terminal
            // state keeps its own way back.
            Button("돌아가기") { dismiss() }
                .font(PlanteriorTypography.caption)
                .foregroundStyle(PlanteriorPalette.accent.color)
                .frame(minHeight: PlanteriorControl.minimumTarget)
                .accessibilityIdentifier("identification.back")
        }
        .padding(PlanteriorSpacing.huge)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(PlanteriorPalette.canvas.color)
    }

    @MainActor
    private func identifyDraft() async {
        guard let draft = await IdentificationDraftStore.shared.load() else {
            state = .awaitingPhoto
            return
        }
        submittedPhoto = draft.data
        #if DEBUG
            switch ProcessInfo.processInfo.environment["QA_IDENTIFICATION_STATE"] {
            case "empty":
                state = .noCandidates
                return
            case "failure":
                if failureRetryCount == 0 {
                    state = .failed(.providerUnavailable)
                    return
                }
            case "pending":
                state = .pending
                return
            default:
                break
            }
            if failureRetryCount > 0,
               let delayMilliseconds = Int(
                   ProcessInfo.processInfo.environment[
                       "QA_IDENTIFICATION_RETRY_DELAY_MS"
                   ] ?? ""
               ),
               delayMilliseconds > 0
            {
                try? await Task.sleep(for: .milliseconds(delayMilliseconds))
            }
        #endif
        await coordinator.submit(draft.data)
        state = await coordinator.state
    }
}
