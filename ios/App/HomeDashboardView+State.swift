import Foundation
import PlanteriorData
import PlanteriorDesignSystem
import PlanteriorDomain
import SwiftUI

extension HomeDashboardView {
    var effectiveSizeCategory: ContentSizeCategory {
        #if DEBUG
            if ProcessInfo.processInfo.environment[
                "QA_HOME_SIZE_CATEGORY"
            ] == "AX5" {
                return .accessibilityExtraExtraExtraLarge
            }
        #endif
        return sizeCategory
    }

    var authenticationState: HomeAuthenticationState {
        #if DEBUG
            if ProcessInfo.processInfo.environment["QA_HOME_AUTH_STATE"] == "signing-in" {
                return .signingIn
            }
            if ProcessInfo.processInfo.environment["QA_AUTHENTICATED"] == "1" {
                return .authenticated
            }
        #endif
        if auth.isRestoring {
            return .signingIn
        }
        return auth.isSignedIn ? .authenticated : .loggedOut
    }

    var syncText: String {
        let snapshot = auth.syncSnapshot
        if !snapshot.conflicts.isEmpty {
            return "동기화 충돌 \(snapshot.conflicts.count)건"
        }
        if !snapshot.queued.isEmpty {
            return "동기화 대기 \(snapshot.queued.count)건"
        }
        return "동기화 완료"
    }

    func reload() {
        guard let today = effectiveToday else {
            return
        }
        store.reload(
            plants: collection.plants,
            today: today,
            weather: weatherState,
            miniHome: miniHomeRepository.load(),
            notificationState: notificationState
        )
    }

    func remountAccount(_ accountID: String?) {
        collection.mount(accountID: accountID)
        LocalNotificationPreferenceStore.shared.mount(
            accountID: accountID
        )
        LocalNotificationScheduleStore.shared.mount(
            accountID: accountID
        )
        reload()
    }

    var miniHomeRepository: HomeCommittedMiniHomeRepository {
        HomeCommittedMiniHomeRepository(
            accountID: auth.accountID?.rawValue
        )
    }

    var effectiveToday: CalendarDate? {
        #if DEBUG
            let qaDate = ProcessInfo.processInfo.environment[
                "QA_WATERING_TODAY"
            ].flatMap { try? CalendarDate.parse($0) }
            if let qaDate {
                return qaDate
            }
        #endif
        return try? calendar.calendarDate(from: Date())
    }

    var weatherState: HomeWeatherState {
        #if DEBUG
            switch ProcessInfo.processInfo.environment["QA_HOME_WEATHER_STATE"] {
            case "failed": return .failed
            case "loading": return .loading
            case "content": return .content(summary: "맑음 · 돌봄 위험 없음")
            default: return .unavailable
            }
        #else
            return .unavailable
        #endif
    }

    func statusText(_ status: HomeCareStatus) -> String {
        switch status {
        case let .overdue(nextDate):
            "지연됨 · \(nextDate.rawValue)"
        case let .due(nextDate):
            "오늘 물 주기 · \(nextDate.rawValue)"
        case let .upcoming(nextDate):
            "예정 · \(nextDate.rawValue)"
        case .unavailable:
            "미설정"
        }
    }

    func statusColor(_ status: HomeCareStatus) -> Color {
        switch status {
        case .overdue:
            PlanteriorPalette.textPrimary.color
        case .due:
            PlanteriorPalette.accent.color
        case .upcoming, .unavailable:
            PlanteriorPalette.textSecondary.color
        }
    }
}

enum HomeAuthenticationState {
    case loggedOut
    case signingIn
    case authenticated
}
