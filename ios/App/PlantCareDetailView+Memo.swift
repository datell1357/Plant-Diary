import PlanteriorDesignSystem
import SwiftUI

extension PlantCareDetailView {
    var memoSection: some View {
        VStack(alignment: .leading, spacing: PlanteriorSpacing.small) {
            Text("관리 메모")
                .font(PlanteriorTypography.sectionTitle)
                .frame(
                    minHeight: PlantCareReferenceMetrics.memoHeadingMinimumHeight,
                    alignment: .leading
                )
            VStack(alignment: .leading, spacing: PlanteriorSpacing.small) {
                ReferenceMemoBodyText(text: memoBodyCopy)
                    .frame(
                        maxWidth: .infinity,
                        minHeight: PlantCareReferenceMetrics.memoBodyMinimumHeight,
                        alignment: .topLeading
                    )
                if let memoUpdatedOn {
                    Text("수정일: \(memoUpdatedOn)")
                        .font(PlanteriorTypography.caption)
                        .foregroundStyle(PlanteriorPalette.textTertiary.color)
                        .accessibilityIdentifier("plant.detail.memo-updated")
                }
            }
            .padding(.horizontal, PlanteriorSpacing.medium)
            .padding(.vertical, PlanteriorSpacing.large)
            .frame(
                maxWidth: .infinity,
                minHeight: PlantCareReferenceMetrics.memoCardMinimumHeight,
                alignment: .leading
            )
            .background(PlanteriorPalette.canvas.color)
            .clipShape(RoundedRectangle(cornerRadius: PlanteriorRadius.large))
            .accessibilityElement(children: .contain)
            .accessibilityIdentifier("plant.detail.memo.card")
            .overlay {
                RoundedRectangle(cornerRadius: PlanteriorRadius.large)
                    .stroke(
                        PlanteriorPalette.border.color,
                        lineWidth: PlanteriorControl.hairline
                    )
            }
        }
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("plant.detail.memo")
    }

    var memoBodyCopy: String {
        privateMemo.isEmpty
            ? "아직 작성한 관리 메모가 없어요."
            : privateMemo
    }

    var memoUpdatedOn: String? {
        #if DEBUG
            guard let value = ProcessInfo.processInfo.environment[
                "QA_PLANT_DETAIL_UPDATED_ON"
            ] else {
                return nil
            }
            let components = value.split(separator: "-")
            guard components.count == 3 else { return nil }
            return "\(components[0]). \(components[1]). \(components[2])"
        #else
            return nil
        #endif
    }

    var remedyLink: some View {
        NavigationLink {
            PlantSymptomRemedyView(
                displayName: trimmedNickname,
                scientificName: plant?.scientificName
            )
        } label: {
            HStack(spacing: PlanteriorSpacing.medium) {
                PlanteriorIconWell(systemImage: "cross.case")
                VStack(alignment: .leading, spacing: PlanteriorSpacing.extraSmall) {
                    Text("증상 대처법")
                        .font(PlanteriorTypography.cardTitle)
                    Text("잎과 흙 상태를 직접 확인하는 방법")
                        .font(PlanteriorTypography.caption)
                        .foregroundStyle(PlanteriorPalette.textSecondary.color)
                }
                Spacer(minLength: PlanteriorSpacing.small)
                Image(systemName: "chevron.right")
                    .foregroundStyle(PlanteriorPalette.textTertiary.color)
                    .accessibilityHidden(true)
            }
            .padding(PlanteriorSpacing.large)
            .background(PlanteriorPalette.surface.color)
            .clipShape(RoundedRectangle(cornerRadius: PlanteriorRadius.large))
            .overlay {
                RoundedRectangle(cornerRadius: PlanteriorRadius.large)
                    .stroke(
                        PlanteriorPalette.border.color,
                        lineWidth: PlanteriorControl.hairline
                    )
            }
        }
        .buttonStyle(.plain)
        .accessibilityIdentifier("plant.detail.remedy")
    }
}
