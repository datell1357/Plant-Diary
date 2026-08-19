import PlanteriorDesignSystem
import PlanteriorDomain
import SwiftUI

struct PlantMiniatureOption: Identifiable {
    let id: PersonalPlantID
    let name: String
}

struct PlantMiniaturePicker: View {
    let options: [PlantMiniatureOption]
    let select: (PlantMiniatureOption) -> Void
    let requestRegistration: () -> Void
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            List {
                if options.isEmpty {
                    Text("배치할 수 있는 등록 식물이 없어요.")
                    Button("식물 등록 안내") {
                        requestRegistration()
                    }
                    .frame(minHeight: PlanteriorControl.minimumTarget)
                } else {
                    ForEach(options) { option in
                        Button {
                            select(option)
                        } label: {
                            Label(
                                option.name,
                                systemImage: "leaf.fill"
                            )
                            .frame(
                                minHeight: PlanteriorControl.minimumTarget
                            )
                        }
                        .accessibilityIdentifier(
                            "minihome.plant.\(option.id.rawValue)"
                        )
                    }
                }
            }
            .navigationTitle("식물 미니어처")
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("취소") { dismiss() }
                }
            }
        }
        .accessibilityIdentifier("minihome.plant-picker")
    }
}
