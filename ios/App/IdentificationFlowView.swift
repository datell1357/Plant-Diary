import PlanteriorData
import PlanteriorDesignSystem
import PlanteriorDomain
import SwiftUI

struct IdentificationFlowView: View {
    @Environment(\.dismiss) private var dismiss
    @State private var state = IdentificationState.pending
    @State private var selectedCandidate: IdentificationCandidate?
    @State private var showsRegistration = false
    @State private var failureRetryCount = 0
    private let coordinator = PlantIdentificationCoordinator(
        service: LocalPlantIdentificationService()
    )

    var body: some View {
        VStack(spacing: 16) {
            Text("사진 분석 결과")
                .font(PlanteriorTypography.screenTitle)
            stateContent
            NavigationLink(
                "직접 입력",
                destination: PlantRegistrationView()
            )
            .accessibilityIdentifier("identification.manual")
        }
        .padding(24)
        .navigationDestination(isPresented: $showsRegistration) {
            PlantRegistrationView(
                method: .identified,
                candidate: selectedCandidate
            )
        }
        .task { await identifyDraft() }
    }

    @ViewBuilder
    private var stateContent: some View {
        switch state {
        case .awaitingPhoto:
            Text("사진을 다시 선택해 주세요.")
        case .pending:
            ProgressView("식물을 찾고 있어요")
                .accessibilityIdentifier("identification.pending")
        case let .candidates(candidates):
            candidateList(candidates)
        case .noCandidates:
            Text("비슷한 식물을 찾지 못했어요.")
                .accessibilityIdentifier("identification.empty")
            Button("다른 사진 선택") { dismiss() }
                .accessibilityIdentifier("identification.replace")
        case .failed:
            Text("식별에 실패했어요.")
                .accessibilityIdentifier("identification.failed")
            Button("다시 시도") {
                failureRetryCount += 1
                Task { await identifyDraft() }
            }
            .accessibilityIdentifier("identification.retry")
        }
    }

    @MainActor
    private func candidateList(
        _ candidates: IdentificationCandidates
    ) -> some View {
        VStack(spacing: 12) {
            Text("가장 비슷한 식물을 선택해 주세요.")
            ForEach(candidates.items.indices, id: \.self) { index in
                Button(
                    "후보 \(index + 1) \(Int(candidates.items[index].score * 100))%"
                ) {
                    selectedCandidate = candidates.items[index]
                }
                .accessibilityIdentifier("identification.candidate.\(index)")
            }
            PlanteriorPrimaryButton("선택 확인") {
                showsRegistration = selectedCandidate != nil
            }
            .disabled(selectedCandidate == nil)
            .accessibilityIdentifier("identification.confirm")
        }
    }

    @MainActor
    private func identifyDraft() async {
        guard let draft = await IdentificationDraftStore.shared.load() else {
            state = .awaitingPhoto
            return
        }
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
        #endif
        await coordinator.submit(draft.data)
        state = await coordinator.state
    }
}
