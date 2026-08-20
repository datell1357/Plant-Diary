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

    /// Figma §6.5/§6.6: section header + count chip, trailing schedule action
    /// only when there is something to schedule, then the row cards.
    var careSection: some View {
        VStack(alignment: .leading, spacing: 8) {
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
                            .foregroundStyle(PlanteriorPalette.textSecondary.color)
                            .frame(minHeight: PlanteriorControl.minimumTarget)
                    }
                    .buttonStyle(.plain)
                    .accessibilityIdentifier("home.care.more")
                }
            }
            .accessibilityElement(children: .contain)
            .accessibilityAddTraits(.isHeader)
            if store.snapshot.careItems.isEmpty {
                careEmptyState
            } else {
                ForEach(
                    Array(store.snapshot.careItems.enumerated()),
                    id: \.element.plantID
                ) { index, item in
                    PlanteriorCard {
                        VStack(alignment: .leading, spacing: 6) {
                            Text(item.displayName)
                                .font(PlanteriorTypography.sectionTitle)
                                .accessibilityIdentifier("home.care.row.\(index)")
                            Text(statusText(item.status))
                                .foregroundStyle(statusColor(item.status))
                        }
                    }
                }
            }
        }
    }

    /// Figma §6.6 Home zero state.
    var careEmptyState: some View {
        PlanteriorCard(variant: .subtle) {
            VStack(spacing: PlanteriorSpacing.extraSmall) {
                Text("🌱")
                    .font(.system(size: 32))
                    .accessibilityHidden(true)
                Text("아직 등록된 식물이 없어요")
                    .font(PlanteriorTypography.cardTitle)
                    .foregroundStyle(PlanteriorPalette.textSecondary.color)
                    .accessibilityIdentifier("home.care.empty")
                Text("카메라로 식물을 촬영해 등록해 보세요")
                    .font(PlanteriorTypography.caption)
                    .foregroundStyle(PlanteriorPalette.textTertiary.color)
            }
            .frame(maxWidth: .infinity)
            .padding(.vertical, PlanteriorSpacing.extraLarge)
        }
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
