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
