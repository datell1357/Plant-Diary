import Foundation
import PlanteriorData
import PlanteriorDomain
import UserNotifications

enum LocalNotificationScheduleSupport {
    static func localPlanningRequest(
        _ request: NotificationScheduleRequest
    ) -> NotificationScheduleRequest {
        NotificationScheduleRequest(
            authorization: .authorized,
            endpoint: request.endpoint,
            global: request.global,
            perPlant: request.perPlant,
            dueDates: request.dueDates,
            completedPlantIDs: request.completedPlantIDs,
            existingDeduplicationKeys: []
        )
    }

    static func restore<Schedule>(
        defaults: UserDefaults,
        key: String,
        decode: (Data) -> [Schedule]?
    ) -> [Schedule] {
        guard let data = defaults.data(forKey: key) else {
            return []
        }
        return decode(data) ?? []
    }

    static func persist<Schedule>(
        _ schedules: [Schedule],
        defaults: UserDefaults,
        key: String,
        encode: ([Schedule]) -> Data?
    ) {
        guard let data = encode(schedules) else {
            return
        }
        defaults.set(data, forKey: key)
    }

    static func deliverySchedules<Schedule>(
        _ schedules: [Schedule],
        authorization: NotificationAuthorizationState,
        quietHours: QuietHoursPreference,
        time: (Schedule) -> String,
        date: (Schedule) -> String
    ) -> [Schedule] {
        guard authorization == .authorized else {
            return []
        }
        return schedules.filter { schedule in
            guard let localTime = try? LocalTime.parse(time(schedule)),
                  (try? CalendarDate.parse(date(schedule))) != nil
            else {
                return false
            }
            return !quietHours.contains(localTime)
        }
    }

    static func notificationRequest(
        plantID: String,
        date: String,
        time: String,
        identifier: String
    ) -> UNNotificationRequest {
        let content = UNMutableNotificationContent()
        content.title = "물 주기 알림"
        content.body = "오늘 물 주기 일정이 있어요."
        content.sound = .default
        content.userInfo = ["route": "plant-care", "plantID": plantID]
        let date = date.split(separator: "-").compactMap { Int($0) }
        let time = time.split(separator: ":").compactMap { Int($0) }
        let trigger = UNCalendarNotificationTrigger(
            dateMatching: DateComponents(
                calendar: .current,
                timeZone: .current,
                year: date[0],
                month: date[1],
                day: date[2],
                hour: time[0],
                minute: time[1]
            ),
            repeats: false
        )
        return UNNotificationRequest(identifier: identifier, content: content, trigger: trigger)
    }

    static func matches(
        _ pending: UNNotificationRequest?,
        _ desired: UNNotificationRequest
    ) -> Bool {
        guard let pending,
              let pendingTrigger = pending.trigger as? UNCalendarNotificationTrigger,
              let desiredTrigger = desired.trigger as? UNCalendarNotificationTrigger
        else {
            return false
        }
        return pending.content.title == desired.content.title
            && pending.content.body == desired.content.body
            && NSDictionary(dictionary: pending.content.userInfo).isEqual(
                to: desired.content.userInfo
            )
            && pendingTrigger.dateComponents == desiredTrigger.dateComponents
    }
}
