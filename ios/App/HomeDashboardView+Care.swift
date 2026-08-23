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
                        .padding(.top, 19)
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
            .padding(.top, -10)
        }
    }

    private var careHeader: some View {
        HStack(alignment: .firstTextBaseline, spacing: PlanteriorSpacing.small) {
            Text("오늘의 식물 관리")
                .font(PlanteriorTypography.sectionTitle)
                .foregroundStyle(PlanteriorPalette.textPrimary.color)
                .accessibilityIdentifier("home.care.header")
            Text(careBadgeText)
                .font(PlanteriorTypography.microLabel)
                .foregroundStyle(PlanteriorPalette.textOnAccent.color)
                .padding(.horizontal, PlanteriorSpacing.small)
                .padding(.vertical, PlanteriorSpacing.extraSmall)
                .background(PlanteriorPalette.accent.color)
                .clipShape(Capsule())
                .accessibilityIdentifier("home.care.badge")
            Spacer(minLength: PlanteriorSpacing.small)
            if showsCareScheduleAction {
                Button(action: requestMiniHomeOpen) {
                    Text("일정 더보기")
                        .font(PlanteriorTypography.caption)
                        .foregroundStyle(PlanteriorPalette.accent.color)
                        .frame(minHeight: PlanteriorControl.minimumTarget)
                }
                .buttonStyle(.plain)
                .accessibilityIdentifier("home.care.more")
            }
        }
        .accessibilityElement(children: .contain)
        .accessibilityAddTraits(.isHeader)
    }

    private func careRow(_ item: HomeCareItem, index: Int) -> some View {
        HStack(spacing: PlanteriorSpacing.medium) {
            Image(index == 0 ? .homePlantMonstera : .homePlantSnake)
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
                Text(item.displayName)
                    .font(PlanteriorTypography.cardTitle)
                    .foregroundStyle(PlanteriorPalette.textPrimary.color)
                    .lineLimit(1)
                    .minimumScaleFactor(0.8)
                    .accessibilityIdentifier("home.care.row.\(index)")
                careStatus(item.status, index: index)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            careTrailing(item, index: index)
        }
        .padding(.horizontal, 14)
        .frame(maxWidth: .infinity)
        .frame(height: 76)
        .background(PlanteriorPalette.surface.color)
        .clipShape(RoundedRectangle(cornerRadius: PlanteriorRadius.large))
        .accessibilityElement(children: .contain)
    }

    @ViewBuilder
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
            .frame(height: 32)
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
                    .frame(height: 32)
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
        calendar.timeZone = TimeZone(secondsFromGMT: 0)!
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
        VStack(spacing: 6) {
            Text("🌱")
                .font(.system(size: 32))
                .accessibilityHidden(true)
            Text("아직 등록된 식물이 없어요")
                .font(PlanteriorTypography.cardTitle)
                .foregroundStyle(PlanteriorPalette.textSecondary.color)
            Text("카메라로 식물을 촬영해 등록해 보세요")
                .font(PlanteriorTypography.caption)
                .foregroundStyle(PlanteriorPalette.textTertiary.color)
        }
        .frame(maxWidth: .infinity)
        .frame(height: 120)
        .background(Color(red: 245.0 / 255, green: 250.0 / 255, blue: 245.0 / 255))
        .clipShape(RoundedRectangle(cornerRadius: PlanteriorRadius.large))
        .accessibilityElement(children: .combine)
        .accessibilityIdentifier("home.care.empty")
    }

    var notificationSection: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("알림")
                .font(PlanteriorTypography.sectionTitle)
            PlanteriorCard {
                VStack(alignment: .leading, spacing: 8) {
                    notificationAuthorizationText
                    VStack(alignment: .leading, spacing: 2) {
                        Text("기본 알림")
                        Text(store.globalNotificationTime)
                    }
                    notificationEndpointText
                    if notificationState.endpoint == .registered {
                        Text("예정 알림 \(store.plannedNotificationCount)건")
                            .accessibilityIdentifier(
                                "home.notification.scheduled"
                            )
                    }
                }
            }
        }
    }

    @ViewBuilder
    var notificationAuthorizationText: some View {
        switch notificationState.authorization {
        case .notDetermined:
            Text("알림 권한 미선택")
                .accessibilityIdentifier("home.notification.status")
        case .denied:
            VStack(alignment: .leading, spacing: 2) {
                Text("알림 꺼짐")
                Text("돌봄 기능 유지")
            }
            .accessibilityIdentifier("home.notification.denied")
        case .authorized:
            Text("알림 켜짐")
                .accessibilityIdentifier("home.notification.status")
        }
    }

    var notificationEndpointText: some View {
        VStack(alignment: .leading, spacing: 2) {
            if notificationState.endpoint == .registered {
                Text("알림 기기")
                Text("등록 완료")
            } else {
                Text("서버 알림")
                Text("준비 중")
            }
        }
        .foregroundStyle(PlanteriorPalette.textSecondary.color)
    }

    var syncSection: some View {
        PlanteriorCard {
            Text(syncText)
                .foregroundStyle(PlanteriorPalette.textSecondary.color)
                .accessibilityIdentifier("home.sync.status")
        }
    }
}
