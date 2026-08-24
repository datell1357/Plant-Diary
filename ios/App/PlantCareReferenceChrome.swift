import PlanteriorDesignSystem
import SwiftUI

/// Collection detail chrome follows the 402x874 Figma canvas while the
/// reference simulator reports an 18pt taller top safe area.
enum PlantCareReferenceMetrics {
    static let topSafeAreaCorrection: CGFloat = -18
    static let heroTopInset: CGFloat = 8
}

struct PlantCareTopBarFrame: View {
    let identifier: String

    var body: some View {
        Rectangle()
            .fill(PlanteriorPalette.canvas.color)
            .accessibilityElement()
            .accessibilityIdentifier(identifier)
    }
}

struct PlantCareBackButton: View {
    let identifier: String
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            ZStack {
                Circle()
                    .fill(PlanteriorPalette.surface.color)
                    .frame(width: 40, height: 40)
                Image(systemName: "chevron.left")
                    .font(.system(size: 18, weight: .semibold))
                    .foregroundStyle(PlanteriorPalette.textPrimary.color)
                    .accessibilityHidden(true)
            }
            .frame(
                width: PlanteriorControl.minimumTarget,
                height: PlanteriorControl.minimumTarget
            )
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityLabel("뒤로")
        .accessibilityIdentifier(identifier)
    }
}

extension PlantSymptomRemedyView {
    var remedyTopBar: some View {
        PlanteriorTopBar("증상 대처법", leading: {
            PlantCareBackButton(identifier: "remedy.back") { dismiss() }
        }, trailing: {
            EmptyView()
        })
        .background {
            PlantCareTopBarFrame(identifier: "remedy.top-bar")
        }
        .plantCareReferenceTopBar()
    }
}

extension PlantCareDetailView {
    var detailTopBar: some View {
        PlanteriorTopBar(LocalizedStringKey(trimmedNickname), leading: {
            PlantCareBackButton(identifier: "plant.detail.back") { dismiss() }
        }, trailing: {
            Button { showsEditing.toggle() } label: {
                ZStack {
                    Circle()
                        .fill(PlanteriorPalette.surface.color)
                        .frame(width: 40, height: 40)
                    Image(systemName: "square.and.pencil")
                        .foregroundStyle(PlanteriorPalette.accent.color)
                        .accessibilityHidden(true)
                }
                .frame(
                    width: PlanteriorControl.minimumTarget,
                    height: PlanteriorControl.minimumTarget
                )
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .frame(
                width: PlanteriorControl.minimumTarget,
                height: PlanteriorControl.minimumTarget
            )
            .accessibilityLabel(showsEditing ? "편집 닫기" : "식물 정보 편집")
            .accessibilityIdentifier("plant.detail.edit")
        })
        .background {
            PlantCareTopBarFrame(identifier: "plant.detail.top-bar")
        }
        .plantCareReferenceTopBar()
    }
}

extension View {
    func plantCareReferenceTopBar() -> some View {
        offset(y: PlantCareReferenceMetrics.topSafeAreaCorrection)
    }

    func plantCareReferenceBody() -> some View {
        padding(.top, PlantCareReferenceMetrics.topSafeAreaCorrection)
    }
}
