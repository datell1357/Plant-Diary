import PlanteriorDesignSystem
import SwiftUI

extension MilestoneProgressView {
    var summary: some View {
        PlanteriorCard {
            VStack(alignment: .leading, spacing: 10) {
                Text("서버 경험치 \(repository.projection?.serverXP ?? 0)")
                    .font(PlanteriorTypography.sectionTitle)
                    .accessibilityIdentifier("milestones.xp.server")
                Text("동기화 대기 \(repository.projection?.pendingCount ?? 0)건")
                    .accessibilityIdentifier("milestones.xp.queued")
                Text("중복 영수증 \(repository.duplicateCount)건")
                    .accessibilityIdentifier("milestones.duplicate-count")
                Text(syncText)
                    .foregroundStyle(
                        PlanteriorPalette.textSecondary.color
                    )
                    .accessibilityIdentifier("milestones.sync.status")
                Text(lastOutcome)
                    .accessibilityIdentifier("milestones.last-outcome")
                Text("숨김 보상 0개")
                    .accessibilityIdentifier("milestones.unpublished-count")
            }
        }
    }
}
