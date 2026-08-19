import Combine
import PlanteriorData
import PlanteriorDomain
import SwiftUI

@MainActor
final class HomeDashboardStore: ObservableObject {
    @Published private(set) var snapshot = HomeDashboardSnapshot(
        careItems: [],
        weather: .unavailable
    )
    @Published private(set) var miniHome: MiniHome?
    @Published private(set) var plannedNotificationCount = 0
    @Published private(set) var globalNotificationTime = "09:00"

    func reload(
        plants: [PlantRegistrationDraft],
        today: CalendarDate,
        weather: HomeWeatherState,
        miniHome: MiniHome?,
        notificationState: NotificationRuntimeState
    ) {
        let candidates: [HomeCareCandidate] = plants.enumerated().compactMap { index, plant in
            guard let plantID = try? PersonalPlantID.parse("local-\(index)") else {
                return nil
            }
            return HomeCareCandidate(
                plantID: plantID,
                displayName: plant.displayName,
                lastWateredDate: plant.lastWateredOn,
                intervalDays: plant.wateringIntervalDays ?? 10
            )
        }
        snapshot = (
            try? HomeDashboardCoordinator(today: today).snapshot(
                candidates: candidates,
                weather: weather
            )
        ) ?? HomeDashboardSnapshot(
            careItems: [],
            weather: weather
        )
        self.miniHome = miniHome
        plannedNotificationCount = plannedNotifications(
            snapshot: snapshot,
            notificationState: notificationState
        )
    }

    private func plannedNotifications(
        snapshot: HomeDashboardSnapshot,
        notificationState: NotificationRuntimeState
    ) -> Int {
        guard let global = LocalNotificationPreferenceStore.shared.global else {
            return 0
        }
        globalNotificationTime = displayTime(global.time)
        let dueDates = Dictionary(
            uniqueKeysWithValues: snapshot.careItems.compactMap { item in
                switch item.status {
                case let .overdue(nextDate),
                     let .due(nextDate),
                     let .upcoming(nextDate):
                    (item.plantID, nextDate)
                case .unavailable:
                    nil
                }
            }
        )
        let request = NotificationScheduleRequest(
            authorization: notificationState.authorization,
            endpoint: notificationState.endpoint,
            global: global,
            perPlant: LocalNotificationPreferenceStore.shared.overrides,
            dueDates: dueDates,
            completedPlantIDs: [],
            existingDeduplicationKeys: []
        )
        do {
            try LocalNotificationScheduleStore.shared.reconcile(request)
            return LocalNotificationScheduleStore.shared.scheduledCount
        } catch {
            return 0
        }
    }

    private func displayTime(_ time: LocalTime) -> String {
        let parts = time.rawValue.split(separator: ":")
        guard parts.count >= 2,
              let hour = Int(parts[0]),
              let minute = Int(parts[1])
        else {
            return time.rawValue
        }
        if minute == 0 {
            return hour < 12 ? "오전 \(hour)시" : "오후 \(hour - 12)시"
        }
        return String(
            format: "%02d:%02d",
            hour,
            minute
        )
    }
}
