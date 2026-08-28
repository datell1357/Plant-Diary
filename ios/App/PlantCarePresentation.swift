import Foundation
import PlanteriorData
import PlanteriorDesignSystem
import PlanteriorDomain
import SwiftUI

struct PlantCareStatus {
    let title: String
    let variant: PlanteriorStatusVariant
    let needsAttention: Bool
}

extension PlantCollectionView {
    func careStatus(
        for item: (offset: Int, element: PlantRegistrationDraft)
    ) -> PlantCareStatus {
        guard let today else {
            return PlantCareStatus(
                title: "일정 확인 필요",
                variant: .neutral,
                needsAttention: false
            )
        }
        switch collection.wateringStatus(
            at: item.offset,
            lastWateredOn: item.element.lastWateredOn,
            today: today,
            intervalDays: collection.wateringIntervalDays(at: item.offset)
        ) {
        case .unavailable:
            return PlantCareStatus(
                title: "물 주기 미설정",
                variant: .neutral,
                needsAttention: false
            )
        case .overdue:
            return PlantCareStatus(
                title: "물주기 지연",
                variant: .warning,
                needsAttention: false
            )
        case .due:
            return PlantCareStatus(
                title: "오늘 물주기",
                variant: .warning,
                needsAttention: true
            )
        case let .upcoming(nextDate):
            return PlantCareStatus(
                title: "D-\(max(daysBetween(today, nextDate), 0))",
                variant: .neutral,
                needsAttention: false
            )
        }
    }

    func careMetadata(for plant: PlantRegistrationDraft) -> String {
        guard let date = plant.lastWateredOn else {
            return "마지막 물 주기 기록 없음"
        }
        return "마지막 물 주기 · \(date.rawValue)"
    }

    @ViewBuilder
    var stateBanner: some View {
        switch collection.snapshotState {
        case .loading:
            ProgressView("도감을 불러오는 중")
                .frame(maxWidth: .infinity)
                .accessibilityIdentifier("collection.loading")
        case .error:
            statusMessage("도감을 불러오지 못했어요", icon: "exclamationmark.triangle")
                .accessibilityIdentifier("collection.error")
        case .partial:
            statusMessage("일부 식물 정보만 표시 중이에요.", icon: "leaf")
                .accessibilityIdentifier("collection.partial")
        case .stale:
            statusMessage("저장된 정보를 표시하고 있어요.", icon: "clock.arrow.circlepath")
                .accessibilityIdentifier("collection.stale")
        case .content:
            EmptyView()
        }
    }

    private func statusMessage(_ text: String, icon: String) -> some View {
        Label(text, systemImage: icon)
            .font(PlanteriorTypography.caption)
            .foregroundStyle(PlanteriorPalette.textSecondary.color)
            .padding(PlanteriorSpacing.medium)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(PlanteriorPalette.subtle.color)
            .clipShape(RoundedRectangle(cornerRadius: PlanteriorRadius.medium))
    }

    var searchEmptyState: some View {
        VStack(spacing: PlanteriorSpacing.small) {
            Image(systemName: "leaf")
                .font(CollectionReferenceMetrics.searchEmptyGlyphFont)
                .foregroundStyle(PlanteriorPalette.accent.color)
                .accessibilityHidden(true)
            Text("검색 결과가 없어요")
                .font(PlanteriorTypography.sectionTitle)
            Text("다른 검색어를 입력해 주세요.")
                .foregroundStyle(PlanteriorPalette.textSecondary.color)
        }
        .padding(.top, PlanteriorSpacing.section)
        .accessibilityIdentifier("collection.empty")
    }
}
