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

public struct WeatherSnapshot: Codable, Equatable, Sendable {
    public let id: WeatherSnapshotID
    public let regionCode: String
    public let temperatureCelsius: Double
    public let humidityPercent: Int
    public let precipitationMillimeters: Double
    public let observedAt: Instant
    public let expiresAt: Instant
}

public struct WeatherRisk: Codable, Equatable, Sendable {
    public let id: WeatherRiskID
    public let plantID: PersonalPlantID
    public let snapshotID: WeatherSnapshotID
    public let type: RiskType
    public let action: String?
    public let detectedAt: Instant
    public let active: Bool
    public let revision: Revision
}

public struct MiniHome: Codable, Equatable, Sendable {
    public let id: MiniHomeID
    public let name: String
    public let placements: [MiniHomePlacement]
    public let revision: Revision
    public let updatedAt: Instant
}

public struct MiniHomePlacement: Codable, Equatable, Sendable {
    public let id: PlacementID
    public let plantID: PersonalPlantID?
    public let itemID: ItemID?
    public let normalizedX: Double
    public let normalizedY: Double
    public let zIndex: Int
}

public struct RiskGuidanceContent: Codable, Equatable, Sendable {
    public let id: String
    public let type: RiskType
    public let action: String
    public let publicationState: PublicationState
    public let revision: Revision
    public let updatedAt: Instant
}

public struct ShopItem: Codable, Equatable, Sendable {
    public let id: ItemID
    public let name: String
    public let category: ItemCategory
    public let assetPath: String
    public let acquisitionCondition: String?
    public let publicationState: PublicationState
    public let revision: Revision
}

public struct OwnedItem: Codable, Equatable, Sendable {
    public let itemID: ItemID
    public let acquiredAt: Instant
    public let applied: Bool
    public let revision: Revision
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
