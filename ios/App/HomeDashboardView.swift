import PlanteriorData
import PlanteriorDesignSystem
import PlanteriorDomain
import SwiftUI

struct HomeDashboardView: View {
    let openCamera: () -> Void
    @Environment(\.sizeCategory) var sizeCategory
    @EnvironmentObject var auth: AuthRuntime
    @ObservedObject var collection = LocalPlantCollectionStore.shared
    @StateObject var store = HomeDashboardStore()
    @State var notificationState = NotificationRuntimeState.initial
    let calendar = PlantCareCalendar()

    var body: some View {
        ScrollView {
            LazyVStack(alignment: .leading, spacing: 20) {
                authenticationContent
                if authenticationState == .authenticated {
                    miniHomeSection
                    weatherSection
                    careSection
                    notificationSection
                    syncSection
                }
            }
            .padding(20)
        }
        .background(PlanteriorPalette.canvas.color)
        .environment(\.sizeCategory, effectiveSizeCategory)
        .navigationTitle("홈")
        .task {
            collection.loadQAFixtureIfNeeded()
            miniHomeRepository.seedQAIfNeeded()
            notificationState = await NotificationRuntimeState.current()
            reload()
        }
        .onChange(of: collection.plants) {
            reload()
        }
        .onChange(of: auth.accountID?.rawValue) { _, accountID in
            remountAccount(accountID)
        }
    }

    private var miniHomeSection: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("나의 미니홈")
                .font(PlanteriorTypography.sectionTitle)
            PlanteriorCard {
                Text(
                    store.miniHome.map { "\($0.name) · 저장됨" }
                        ?? "아직 저장된 미니홈이 없어요."
                )
                .accessibilityIdentifier("home.minhome.preview")
            }
        }
    }

    private var careSection: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("오늘의 돌봄")
                .font(PlanteriorTypography.sectionTitle)
            if store.snapshot.careItems.isEmpty {
                PlanteriorCard {
                    Text("예정된 돌봄이 없어요.")
                        .foregroundStyle(
                            PlanteriorPalette.textSecondary.color
                        )
                }
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

    private var weatherSection: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("날씨 기반 안내")
                .font(PlanteriorTypography.sectionTitle)
            PlanteriorCard {
                switch store.snapshot.weather {
                case let .content(summary):
                    Text(summary)
                        .accessibilityIdentifier("home.weather.content")
                case .loading:
                    ProgressView("날씨를 불러오는 중")
                        .accessibilityIdentifier("home.weather.loading")
                case .failed:
                    VStack(alignment: .leading, spacing: 4) {
                        Text("날씨 정보를 불러오지 못했어요.")
                            .accessibilityIdentifier("home.weather.failed")
                        Text("돌봄 일정은 계속 사용할 수 있어요.")
                    }
                case .unavailable:
                    Text("지역을 설정하면 날씨 안내가 표시돼요.")
                        .accessibilityIdentifier("home.weather.unavailable")
                }
            }
        }
    }

    private var notificationSection: some View {
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
    private var notificationAuthorizationText: some View {
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

    private var notificationEndpointText: some View {
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

    private var syncSection: some View {
        PlanteriorCard {
            Text(syncText)
                .foregroundStyle(PlanteriorPalette.textSecondary.color)
                .accessibilityIdentifier("home.sync.status")
        }
    }
}
