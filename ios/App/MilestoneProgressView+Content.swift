import PlanteriorDesignSystem
import SwiftUI

extension MilestoneProgressView {
    var milestoneRows: some View {
        ForEach(publicDefinitions, id: \.id) { definition in
            PlanteriorCard {
                VStack(alignment: .leading, spacing: 10) {
                    Text(title(for: definition.id))
                        .font(PlanteriorTypography.sectionTitle)
                    Text("\(definition.thresholdXP) XP")
                    let state = state(for: definition.id)
                    Text(stateText(state))
                        .accessibilityIdentifier(
                            "milestone.state.\(definition.id.rawValue)"
                        )
                        .accessibilityValue(state.rawValue.lowercased())
                    if state == .earned {
                        PlanteriorPrimaryButton("보상 받기") {
                            claim(definition.id)
                        }
                        .accessibilityIdentifier(
                            "milestone.claim.\(definition.id.rawValue)"
                        )
                    }
                }
            }
            .accessibilityElement(children: .contain)
            .accessibilityIdentifier(
                "milestone.row.\(definition.id.rawValue)"
            )
        }
    }
}
