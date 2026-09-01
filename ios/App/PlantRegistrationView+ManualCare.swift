import PlanteriorDesignSystem
import PlanteriorDomain
import SwiftUI

extension PlantRegistrationView {
    var manualCareOptions: [DomesticPlantCareProfile] {
        DomesticPlantCareCatalog.manualOptions(matching: speciesSearch)
    }

    func manualCareOption(
        _ profile: DomesticPlantCareProfile
    ) -> some View {
        Button {
            selectedManualScientificName = profile.scientificName
        } label: {
            HStack {
                VStack(alignment: .leading, spacing: PlanteriorSpacing.extraSmall) {
                    Text("몬스테라")
                    Text(profile.scientificName)
                        .font(PlanteriorTypography.caption)
                        .foregroundStyle(PlanteriorPalette.textSecondary.color)
                }
                Spacer()
                if selectedManualScientificName == profile.scientificName {
                    Label("선택됨", systemImage: "checkmark.circle.fill")
                        .font(PlanteriorTypography.caption)
                        .foregroundStyle(PlanteriorPalette.accent.color)
                }
            }
        }
        .accessibilityIdentifier("registration.care-option.monstera-deliciosa")
        .accessibilityValue(
            selectedManualScientificName == profile.scientificName
                ? "선택됨"
                : "선택되지 않음"
        )
    }
}

struct DuplicatePlantRoute: Identifiable, Hashable {
    let target: PlantRouteTarget

    var id: String {
        target.rawValue
    }
}
