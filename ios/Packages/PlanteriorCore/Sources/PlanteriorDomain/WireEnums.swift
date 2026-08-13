public protocol WireEnum:
    RawRepresentable,
    Codable,
    CaseIterable,
    Sendable
    where RawValue == String {}

public extension WireEnum {
    init(from decoder: Decoder) throws {
        let value = try decoder.singleValueContainer().decode(String.self)
        guard let decoded = Self(rawValue: value) else {
            throw DomainValidationError.unknownEnum(
                type: String(describing: Self.self),
                value: value
            )
        }
        self = decoded
    }

    func encode(to encoder: Encoder) throws {
        var container = encoder.singleValueContainer()
        try container.encode(rawValue)
    }
}

public enum PublicationState: String, WireEnum {
    case draft = "DRAFT"
    case `public` = "PUBLIC"
    case `private` = "PRIVATE"
}

public enum RegistrationMethod: String, WireEnum {
    case identified = "IDENTIFIED"
    case identificationEdited = "IDENTIFICATION_EDITED"
    case manual = "MANUAL"
}

public enum RiskType: String, WireEnum {
    case highTemperature = "HIGH_TEMPERATURE"
    case lowTemperature = "LOW_TEMPERATURE"
    case dry = "DRY"
    case overwatered = "OVERWATERED"
}

public enum ItemCategory: String, WireEnum {
    case background = "BACKGROUND"
    case furniture = "FURNITURE"
    case decoration = "DECORATION"
}

public enum DeletionStatus: String, WireEnum {
    case received = "RECEIVED"
    case processing = "PROCESSING"
    case completed = "COMPLETED"
    case failed = "FAILED"
    case partiallyFailed = "PARTIALLY_FAILED"
    case cancelled = "CANCELLED"
}

public enum DeliveryStatus: String, WireEnum {
    case pending = "PENDING"
    case sent = "SENT"
    case opened = "OPENED"
    case failed = "FAILED"
}

public enum ConsentType: String, WireEnum {
    case identificationPhotoProcessing = "IDENTIFICATION_PHOTO_PROCESSING"
    case location = "LOCATION"
    case analytics = "ANALYTICS"
}
