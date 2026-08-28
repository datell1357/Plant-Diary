import PlanteriorDesignSystem
import SwiftUI

extension HomeDashboardView {
    var notificationSection: some View {
        VStack(alignment: .leading, spacing: PlanteriorSpacing.small) {
            Text("알림")
                .font(PlanteriorTypography.sectionTitle)
            PlanteriorCard {
                VStack(alignment: .leading, spacing: PlanteriorSpacing.small) {
                    notificationAuthorizationText
                    VStack(
                        alignment: .leading,
                        spacing: HomeReferenceMetrics.notificationDetailSpacing
                    ) {
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
            VStack(
                alignment: .leading,
                spacing: HomeReferenceMetrics.notificationDetailSpacing
            ) {
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
        VStack(
            alignment: .leading,
            spacing: HomeReferenceMetrics.notificationDetailSpacing
        ) {
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
