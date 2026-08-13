public enum PublicationState: String, Codable, CaseIterable, Sendable {
    case draft = "DRAFT"
    case `public` = "PUBLIC"
    case `private` = "PRIVATE"
}

public enum RegistrationMethod: String, Codable, CaseIterable, Sendable {
    case identified = "IDENTIFIED"
    case identificationEdited = "IDENTIFICATION_EDITED"
    case manual = "MANUAL"
}

public enum RiskType: String, Codable, CaseIterable, Sendable {
    case highTemperature = "HIGH_TEMPERATURE"
    case lowTemperature = "LOW_TEMPERATURE"
    case dry = "DRY"
    case overwatered = "OVERWATERED"
}

public enum ItemCategory: String, Codable, CaseIterable, Sendable {
    case background = "BACKGROUND"
    case furniture = "FURNITURE"
    case decoration = "DECORATION"
}

public enum DeletionStatus: String, Codable, CaseIterable, Sendable {
    case received = "RECEIVED"
    case processing = "PROCESSING"
    case completed = "COMPLETED"
    case failed = "FAILED"
    case partiallyFailed = "PARTIALLY_FAILED"
    case cancelled = "CANCELLED"
}

public enum DeliveryStatus: String, Codable, CaseIterable, Sendable {
    case pending = "PENDING"
    case sent = "SENT"
    case opened = "OPENED"
    case failed = "FAILED"
}

public enum ConsentType: String, Codable, CaseIterable, Sendable {
    case identificationPhotoProcessing = "IDENTIFICATION_PHOTO_PROCESSING"
    case location = "LOCATION"
    case analytics = "ANALYTICS"
}
