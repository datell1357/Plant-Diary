import Foundation
import PlanteriorData
import PlanteriorDesignSystem
import PlanteriorDomain
import SwiftUI

extension HomeDashboardView {
    /// §6.5: the trailing schedule action is absent in the signed-out/zero state.
    var showsCareScheduleAction: Bool {
        authenticationState == .authenticated
            && !store.snapshot.careItems.isEmpty
    }

    /// Figma §6.5/§6.6: compact header followed by 76pt media-led care rows.
    var careSection: some View {
        VStack(alignment: .leading, spacing: 0) {
            careHeader
            Group {
                if store.snapshot.careItems.isEmpty {
                    careEmptyState
                        .padding(.top, HomeReferenceMetrics.careEmptyTopInset)
                } else {
                    VStack(spacing: PlanteriorSpacing.small) {
                        ForEach(
                            Array(store.snapshot.careItems.enumerated()),
                            id: \.element.plantID
                        ) { index, item in
                            careRow(item, index: index)
                        }
                    }
                }
            }
            .padding(.top, HomeReferenceMetrics.careContentTopOffset)
        }
    }

    private func careRow(_ item: HomeCareItem, index: Int) -> some View {
        HStack(spacing: PlanteriorSpacing.medium) {
            Image(index == 0 ? .homePlantSnake : .homePlantMonstera)
                .resizable()
                .scaledToFill()
                .frame(
                    width: PlanteriorLayout.mediaThumbnailSize,
                    height: PlanteriorLayout.mediaThumbnailSize
                )
                .clipShape(RoundedRectangle(cornerRadius: PlanteriorRadius.medium))
                .accessibilityLabel("\(item.displayName) 사진")
                .accessibilityIdentifier("home.care.media.\(index)")
            VStack(alignment: .leading, spacing: PlanteriorSpacing.extraSmall) {
                Text(KoreanTypography.atomicParentheticalSpecies(in: item.displayName))
                    .font(PlanteriorTypography.cardTitle)
                    .accessibilityLabel(item.displayName)
                    .foregroundStyle(PlanteriorPalette.textPrimary.color)
                    .lineLimit(1)
                    .minimumScaleFactor(HomeReferenceMetrics.careNameMinimumScale)
                    .accessibilityIdentifier("home.care.row.\(index)")
                careStatus(item.status, index: index)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            careTrailing(item, index: index)
        }
        .padding(.horizontal, HomeReferenceMetrics.careRowHorizontalInset)
        .frame(maxWidth: .infinity)
        .frame(height: HomeReferenceMetrics.careRowHeight)
        .background(PlanteriorPalette.surface.color)
        .clipShape(RoundedRectangle(cornerRadius: PlanteriorRadius.large))
        .accessibilityElement(children: .contain)
    }

    private func careStatus(_ status: HomeCareStatus, index: Int) -> some View {
        HStack(spacing: PlanteriorSpacing.extraSmall) {
            if case .due = status {
                Image(systemName: "drop.fill")
                    .font(PlanteriorTypography.caption)
                    .accessibilityHidden(true)
            } else if case .upcoming = status {
                Image(systemName: "clock.fill")
                    .font(PlanteriorTypography.caption)
                    .accessibilityHidden(true)
            }
            Text(careStatusText(status))
                .font(PlanteriorTypography.caption)
                .lineLimit(1)
                .accessibilityIdentifier("home.care.status.\(index)")
        }
        .foregroundStyle(statusColor(status))
    }

    @ViewBuilder
    private func careTrailing(_ item: HomeCareItem, index: Int) -> some View {
        switch item.status {
        case .overdue, .due:
            Button("물주기 완료") {
                completeCare(item)
            }
            .font(PlanteriorTypography.caption.weight(.semibold))
            .foregroundStyle(PlanteriorPalette.textOnAccent.color)
            .padding(.horizontal, PlanteriorSpacing.medium)
            .frame(height: HomeReferenceMetrics.careTrailingHeight)
            .background(PlanteriorPalette.accent.color)
            .clipShape(Capsule())
            .buttonStyle(.plain)
            .frame(
                minWidth: PlanteriorControl.minimumTarget,
                minHeight: PlanteriorControl.minimumTarget
            )
            .accessibilityIdentifier("home.care.complete.\(index)")
        case let .upcoming(nextDate):
            if let days = daysUntil(nextDate) {
                Text("D-\(days)")
                    .font(PlanteriorTypography.caption.weight(.semibold))
                    .foregroundStyle(PlanteriorPalette.accent.color)
                    .padding(.horizontal, PlanteriorSpacing.medium)
                    .frame(height: HomeReferenceMetrics.careTrailingHeight)
                    .background(PlanteriorPalette.successSurface.color)
                    .clipShape(Capsule())
                    .accessibilityIdentifier("home.care.trailing.\(index)")
            }
        case .unavailable:
            EmptyView()
        }
    }

    private func careStatusText(_ status: HomeCareStatus) -> String {
        switch status {
        case .overdue:
            return "물주기가 늦었어요"
        case .due:
            return "오늘 물 주는 날"
        case let .upcoming(nextDate):
            guard let days = daysUntil(nextDate) else {
                return statusText(status)
            }
            return "\(days)일 후 물주기"
        case .unavailable:
            return "물주기 일정 미설정"
        }
    }

    private func daysUntil(_ date: CalendarDate) -> Int? {
        guard let today = effectiveToday else {
            return nil
        }
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = .gmt
        let formatter = DateFormatter()
        formatter.calendar = calendar
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.timeZone = calendar.timeZone
        formatter.dateFormat = "yyyy-MM-dd"
        guard let start = formatter.date(from: today.rawValue),
              let end = formatter.date(from: date.rawValue)
        else {
            return nil
        }
        return calendar.dateComponents([.day], from: start, to: end).day
    }

    private func completeCare(_ item: HomeCareItem) {
        guard let today = effectiveToday,
              let index = collection.weatherPlantIDs.firstIndex(of: item.plantID)
        else {
            return
        }
        do {
            _ = try collection.recordWateredToday(
                at: index,
                today: today,
                intervalDays: collection.wateringIntervalDays(at: index)
            )
            reload()
        } catch {
            assertionFailure("A visible due care item must have a watering schedule: \(error)")
        }
    }

    /// Figma §6.6 Home zero state.
    var careEmptyState: some View {
        VStack(spacing: HomeReferenceMetrics.careEmptySpacing) {
            Text("🌱")
                .font(HomeReferenceMetrics.careEmptyGlyphFont)
                .accessibilityHidden(true)
            Text("아직 등록된 식물이 없어요")
                .font(PlanteriorTypography.cardTitle)
                .foregroundStyle(PlanteriorPalette.textSecondary.color)
            Text("카메라로 식물을 촬영해 등록해 보세요")
                .font(PlanteriorTypography.caption)
                .foregroundStyle(PlanteriorPalette.textAccessibleCaption.color)
        }
        .frame(maxWidth: .infinity)
        .frame(height: HomeReferenceMetrics.careEmptyHeight)
        .background(PlanteriorPalette.homeCareEmptySurface.color)
        .clipShape(RoundedRectangle(cornerRadius: PlanteriorRadius.large))
        .accessibilityElement(children: .combine)
        .accessibilityIdentifier("home.care.empty")
    }
}
