import Foundation
import PlanteriorDomain

public enum NotificationAuthorizationState: Equatable, Sendable {
    case notDetermined
    case denied
    case authorized
}

public enum NotificationEndpointState: Equatable, Sendable {
    case unavailable
    case registering
    case registered
}

public struct NotificationPreference: Equatable, Sendable {
    public let enabled: Bool
    public let time: LocalTime

    public init(enabled: Bool, time: LocalTime) {
        self.enabled = enabled
        self.time = time
    }
}

public struct PlantNotificationOverride: Equatable, Sendable {
    public let enabled: Bool?
    public let time: LocalTime?

    public init(enabled: Bool?, time: LocalTime?) {
        self.enabled = enabled
        self.time = time
    }
}

public struct NotificationScheduleRequest: Sendable {
    public let authorization: NotificationAuthorizationState
    public let endpoint: NotificationEndpointState
    public let global: NotificationPreference
    public let perPlant: [PersonalPlantID: PlantNotificationOverride]
    public let dueDates: [PersonalPlantID: CalendarDate]
    public let completedPlantIDs: Set<PersonalPlantID>
    public let existingDeduplicationKeys: Set<String>

    public init(
        authorization: NotificationAuthorizationState,
        endpoint: NotificationEndpointState,
        global: NotificationPreference,
        perPlant: [PersonalPlantID: PlantNotificationOverride],
        dueDates: [PersonalPlantID: CalendarDate],
        completedPlantIDs: Set<PersonalPlantID>,
        existingDeduplicationKeys: Set<String>
    ) {
        self.authorization = authorization
        self.endpoint = endpoint
        self.global = global
        self.perPlant = perPlant
        self.dueDates = dueDates
        self.completedPlantIDs = completedPlantIDs
        self.existingDeduplicationKeys = existingDeduplicationKeys
    }
}

public enum NotificationScheduleKind: Equatable, Sendable {
    case dueDay
    case nextDay
}

public struct PlannedNotification: Equatable, Sendable {
    public let plantID: PersonalPlantID
    public let date: CalendarDate
    public let time: LocalTime
    public let kind: NotificationScheduleKind
    public let deduplicationKey: String
}

public struct NotificationCoordinator: Sendable {
    public init() {}

    public func schedules(
        _ request: NotificationScheduleRequest
    ) throws -> [PlannedNotification] {
        guard
            request.authorization == .authorized,
            request.endpoint == .registered
        else {
            return []
        }
        return try plannedSchedules(request)
    }

    public func localSchedules(
        _ request: NotificationScheduleRequest,
        now: Date = Date(),
        calendar: Calendar = .current
    ) throws -> [PlannedNotification] {
        guard request.authorization == .authorized else {
            return []
        }
        return try plannedSchedules(request)
            .compactMap { schedule -> (Date, PlannedNotification)? in
                guard let triggerDate = scheduledDate(
                    schedule,
                    calendar: calendar
                ), triggerDate > now else {
                    return nil
                }
                return (triggerDate, schedule)
            }
            .sorted { lhs, rhs in
                lhs.0 == rhs.0
                    ? lhs.1.deduplicationKey < rhs.1.deduplicationKey
                    : lhs.0 < rhs.0
            }
            .prefix(60)
            .map(\.1)
    }

    private func plannedSchedules(
        _ request: NotificationScheduleRequest
    ) throws -> [PlannedNotification] {
        try request.dueDates
            .sorted { $0.key.rawValue < $1.key.rawValue }
            .filter { !request.completedPlantIDs.contains($0.key) }
            .flatMap { entry -> [PlannedNotification] in
                let plantID = entry.key
                let override = request.perPlant[plantID]
                guard override?.enabled ?? request.global.enabled else {
                    return []
                }
                let time = override?.time ?? request.global.time
                let dueDate = entry.value
                let nextDate = try addingOneDay(to: dueDate)
                return [
                    planned(
                        plantID: plantID,
                        date: dueDate,
                        time: time,
                        kind: .dueDay
                    ),
                    planned(
                        plantID: plantID,
                        date: nextDate,
                        time: time,
                        kind: .nextDay
                    )
                ]
            }
            .filter {
                !request.existingDeduplicationKeys.contains($0.deduplicationKey)
            }
    }

    private func planned(
        plantID: PersonalPlantID,
        date: CalendarDate,
        time: LocalTime,
        kind: NotificationScheduleKind
    ) -> PlannedNotification {
        let suffix = kind == .dueDay ? "due" : "next"
        return PlannedNotification(
            plantID: plantID,
            date: date,
            time: time,
            kind: kind,
            deduplicationKey:
            "\(plantID.rawValue)|\(date.rawValue)|\(suffix)"
        )
    }

    private func addingOneDay(to calendarDate: CalendarDate) throws -> CalendarDate {
        let parts = calendarDate.rawValue
            .split(separator: "-")
            .compactMap { Int($0) }
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = .gmt
        guard
            parts.count == 3,
            let date = calendar.date(
                from: DateComponents(
                    calendar: calendar,
                    timeZone: calendar.timeZone,
                    year: parts[0],
                    month: parts[1],
                    day: parts[2]
                )
            ),
            let next = calendar.date(byAdding: .day, value: 1, to: date)
        else {
            throw DomainValidationError.invalidCalendarDate
        }
        let components = calendar.dateComponents(
            [.year, .month, .day],
            from: next
        )
        guard
            let year = components.year,
            let month = components.month,
            let day = components.day
        else {
            throw DomainValidationError.invalidCalendarDate
        }
        return try CalendarDate.parse(
            String(format: "%04d-%02d-%02d", year, month, day)
        )
    }

    private func scheduledDate(
        _ schedule: PlannedNotification,
        calendar: Calendar
    ) -> Date? {
        let date = schedule.date.rawValue
            .split(separator: "-")
            .compactMap { Int($0) }
        let time = schedule.time.rawValue
            .split(separator: ":")
            .compactMap { Int($0) }
        guard date.count == 3, time.count >= 2 else {
            return nil
        }
        return calendar.date(from: DateComponents(
            calendar: calendar,
            timeZone: calendar.timeZone,
            year: date[0],
            month: date[1],
            day: date[2],
            hour: time[0],
            minute: time[1]
        ))
    }
}
