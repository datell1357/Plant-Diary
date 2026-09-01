import PlanteriorDesignSystem
import SwiftUI

/// Collection detail chrome follows the 402x874 Figma canvas while the
/// reference simulator reports an 18pt taller top safe area.
enum PlantCareReferenceMetrics {
    static let topSafeAreaCorrection: CGFloat = -18
    static let heroTopInset: CGFloat = 8
    static let actionVisualSide = PlanteriorControl.compactVisualSize
    static let navigationGlyphFont = Font.system(size: 18, weight: .semibold)
    static let heroHeight: CGFloat = 220
    static let guideGlyphFont = Font.system(size: 13)
    static let guideGridSpacing: CGFloat = 10
    static let guideMinimumHeight: CGFloat = 200
    static let guideToWateringInset: CGFloat = 4.0 / 3.0
    static let wateringCardHeight: CGFloat = 200.0 / 3.0
    static let memoHeadingMinimumHeight: CGFloat = 20.333
    static let memoTopInset = PlanteriorSpacing.extraSmall
    static let memoBodyReferenceWidth: CGFloat = 340
    static let memoBodyMinimumHeight: CGFloat = 58
    static let memoCardMinimumHeight: CGFloat = 337.0 / 3.0
    static let remedyExpandedBottomInset: CGFloat = 18
    static let remedyExpandedMinimumHeight: CGFloat = 199
    /// Keeps destructive detail actions above the shell's persistent tab bar.
    static let detailBottomScrollClearance = PlanteriorLayout.tabBarHeight
        + PlanteriorSpacing.large
}

extension View {
    func remedyReferenceMinimumHeight(isExpanded: Bool) -> some View {
        frame(
            minHeight: isExpanded
                ? PlantCareReferenceMetrics.remedyExpandedMinimumHeight
                : nil,
            alignment: .top
        )
    }

    func remedyCardAccessibility(index: Int) -> some View {
        accessibilityElement(children: .contain)
            .accessibilityIdentifier("remedy.card.\(index)")
    }
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
                    .frame(
                        width: PlantCareReferenceMetrics.actionVisualSide,
                        height: PlantCareReferenceMetrics.actionVisualSide
                    )
                Image(systemName: "chevron.left")
                    .font(PlantCareReferenceMetrics.navigationGlyphFont)
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

private struct PlantCareLeadingTopBar<Leading: View, Trailing: View>: View {
    let title: String
    let titleIdentifier: String
    let centersTitle: Bool
    let leading: Leading
    let trailing: Trailing

    var body: some View {
        ZStack {
            HStack(spacing: PlanteriorSpacing.large) {
                leading
                if !centersTitle {
                    titleLabel
                }
                Spacer(minLength: PlanteriorSpacing.small)
                trailing
            }
            if centersTitle {
                titleLabel
            }
        }
        .padding(.horizontal, PlanteriorLayout.contentGutter)
        .frame(minHeight: PlanteriorLayout.topBarHeight)
        .background(PlanteriorPalette.canvas.color)
    }

    private var titleLabel: some View {
        Text(verbatim: title)
            .font(
                centersTitle
                    ? PlanteriorTypography.pageTitle
                    : PlanteriorTypography.screenTitle
            )
            .foregroundStyle(PlanteriorPalette.textPrimary.color)
            .lineLimit(1)
            .minimumScaleFactor(0.55)
            .accessibilityAddTraits(.isHeader)
            .accessibilityIdentifier(titleIdentifier)
    }
}

extension PlantSymptomRemedyView {
    var remedyTopBar: some View {
        PlantCareLeadingTopBar(
            title: "증상 대처법",
            titleIdentifier: "remedy.navigation-title",
            centersTitle: false,
            leading: PlantCareBackButton(identifier: "remedy.back") { dismiss() },
            trailing: Color.clear
                .frame(
                    width: PlanteriorControl.minimumTarget,
                    height: PlanteriorControl.minimumTarget
                )
                .accessibilityHidden(true)
        )
        .background {
            PlantCareTopBarFrame(identifier: "remedy.top-bar")
        }
        .plantCareReferenceTopBar()
    }
}

extension PlantCareDetailView {
    var detailTopBar: some View {
        PlantCareLeadingTopBar(
            title: trimmedNickname,
            titleIdentifier: "plant.detail.navigation-title",
            centersTitle: true,
            leading: PlantCareBackButton(identifier: "plant.detail.back") { dismiss() },
            trailing: detailEditButton
        )
        .background {
            PlantCareTopBarFrame(identifier: "plant.detail.top-bar")
        }
        .plantCareReferenceTopBar()
    }

    private var detailEditButton: some View {
        Button { showsEditing.toggle() } label: {
            ZStack {
                Circle()
                    .fill(PlanteriorPalette.surface.color)
                    .frame(
                        width: PlantCareReferenceMetrics.actionVisualSide,
                        height: PlantCareReferenceMetrics.actionVisualSide
                    )
                Image(systemName: "square.and.pencil")
                    .font(PlantCareReferenceMetrics.navigationGlyphFont)
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
        .frame(
            width: PlanteriorControl.minimumTarget,
            height: PlanteriorControl.minimumTarget
        )
        .accessibilityLabel(showsEditing ? "편집 닫기" : "식물 정보 편집")
        .accessibilityIdentifier("plant.detail.edit")
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
