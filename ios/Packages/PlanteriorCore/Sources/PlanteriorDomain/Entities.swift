public struct UserAccount: Codable, Equatable, Sendable {
    public let id: AccountID
    public let displayName: String?
    public let zoneID: TimeZoneID
    public let createdAt: Instant
    public let revision: Revision
}

public struct PersonalPlant: Codable, Equatable, Sendable {
    public let id: PersonalPlantID
    public let displayName: String
    public let contentID: PlantContentID?
    public let registrationMethod: RegistrationMethod
    public let representativePhotoPath: String?
    public let location: String?
    public let note: String?
    public let lastWateredDate: CalendarDate?
    public let revision: Revision
    public let updatedAt: Instant

    public init(
        id: PersonalPlantID,
        displayName: String,
        contentID: PlantContentID?,
        registrationMethod: RegistrationMethod,
        representativePhotoPath: String?,
        location: String?,
        note: String?,
        lastWateredDate: CalendarDate?,
        revision: Revision,
        updatedAt: Instant
    ) {
        self.id = id
        self.displayName = displayName
        self.contentID = contentID
        self.registrationMethod = registrationMethod
        self.representativePhotoPath = representativePhotoPath
        self.location = location
        self.note = note
        self.lastWateredDate = lastWateredDate
        self.revision = revision
        self.updatedAt = updatedAt
    }
}

public struct SymptomGuidance: Codable, Equatable, Sendable {
    public let id: String
    public let symptom: String
    public let possibleCause: String
    public let action: String
}

public struct PlantContent: Codable, Equatable, Sendable {
    public let id: PlantContentID
    public let name: String
    public let wateringIntervalDays: Int?
    public let lightGuidance: String
    public let minimumTemperatureCelsius: Double?
    public let maximumTemperatureCelsius: Double?
    public let minimumHumidityPercent: Int?
    public let maximumHumidityPercent: Int?
    public let symptoms: [SymptomGuidance]
    public let publicationState: PublicationState
    public let revision: Revision
    public let updatedAt: Instant
}

public struct WateringSchedule: Codable, Equatable, Sendable {
    public let id: WateringScheduleID
    public let plantID: PersonalPlantID
    public let dueDate: CalendarDate
    public let reminderTime: LocalTime
    public let zoneID: TimeZoneID
    public let enabled: Bool
    public let revision: Revision
    public let updatedAt: Instant
}

public struct WateringRecord: Codable, Equatable, Sendable {
    public let id: WateringRecordID
    public let plantID: PersonalPlantID
    public let wateredDate: CalendarDate
    public let recordedAt: Instant
    public let operationID: OperationID
    public let revision: Revision
}

public struct NotificationSetting: Codable, Equatable, Sendable {
    public let wateringEnabled: Bool
    public let weatherEnabled: Bool
    public let defaultTime: LocalTime
    public let zoneID: TimeZoneID
    public let revision: Revision
}

public struct RiskGuidanceContent: Codable, Equatable, Sendable {
    public let id: String
    public let type: RiskType
    public let action: String
    public let publicationState: PublicationState
    public let revision: Revision
    public let updatedAt: Instant
}

public struct ShareLink: Codable, Equatable, Sendable {
    public let id: ShareLinkID
    public let miniHomeID: MiniHomeID
    public let sourceRevision: Revision
    public let snapshotPath: String
    public let createdAt: Instant
    public let expiresAt: Instant
    public let revokedAt: Instant?
}

public struct ConsentRecord: Codable, Equatable, Sendable {
    public let id: ConsentID
    public let type: ConsentType
    public let granted: Bool
    public let recordedAt: Instant
    public let revision: Revision
}

public struct AccountDeletionRequest: Codable, Equatable, Sendable {
    public let id: DeletionRequestID
    public let requestedAt: Instant
    public let scheduledFor: Instant
    public let status: DeletionStatus
    public let completedAt: Instant?
    public let revision: Revision
}
