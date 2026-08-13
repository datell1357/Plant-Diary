import Foundation

public enum DomainValidationError: Error, Equatable, Sendable {
    case invalidOpaqueID
    case invalidOperationID
    case negativeRevision
    case revisionOverflow
    case invalidCalendarDate
    case invalidLocalTime
    case invalidInstant
    case invalidTimeZone
    case unknownEnum(type: String, value: String)
}

public struct OpaqueID<Tag>: Codable, Hashable, Sendable {
    public let rawValue: String

    private init(validated value: String) {
        rawValue = value
    }

    public static func parse(_ value: String) throws -> Self {
        guard value.range(
            of: #"^[A-Za-z0-9_-]{1,128}$"#,
            options: .regularExpression
        ) != nil else {
            throw DomainValidationError.invalidOpaqueID
        }
        return Self(validated: value)
    }

    public init(from decoder: Decoder) throws {
        let value = try decoder.singleValueContainer().decode(String.self)
        self = try Self.parse(value)
    }

    public func encode(to encoder: Encoder) throws {
        var container = encoder.singleValueContainer()
        try container.encode(rawValue)
    }
}

public enum AccountTag: Sendable {}
public enum PersonalPlantTag: Sendable {}
public enum PlantContentTag: Sendable {}
public enum WateringScheduleTag: Sendable {}
public enum WateringRecordTag: Sendable {}
public enum WeatherSnapshotTag: Sendable {}
public enum WeatherRiskTag: Sendable {}
public enum MiniHomeTag: Sendable {}
public enum PlacementTag: Sendable {}
public enum ItemTag: Sendable {}
public enum ShareLinkTag: Sendable {}
public enum ConsentTag: Sendable {}
public enum DeletionRequestTag: Sendable {}
public enum NotificationDeliveryTag: Sendable {}
public enum IdentificationRequestTag: Sendable {}

public typealias AccountID = OpaqueID<AccountTag>
public typealias PersonalPlantID = OpaqueID<PersonalPlantTag>
public typealias PlantContentID = OpaqueID<PlantContentTag>
public typealias WateringScheduleID = OpaqueID<WateringScheduleTag>
public typealias WateringRecordID = OpaqueID<WateringRecordTag>
public typealias WeatherSnapshotID = OpaqueID<WeatherSnapshotTag>
public typealias WeatherRiskID = OpaqueID<WeatherRiskTag>
public typealias MiniHomeID = OpaqueID<MiniHomeTag>
public typealias PlacementID = OpaqueID<PlacementTag>
public typealias ItemID = OpaqueID<ItemTag>
public typealias ShareLinkID = OpaqueID<ShareLinkTag>
public typealias ConsentID = OpaqueID<ConsentTag>
public typealias DeletionRequestID = OpaqueID<DeletionRequestTag>
public typealias NotificationDeliveryID = OpaqueID<NotificationDeliveryTag>
public typealias IdentificationRequestID = OpaqueID<IdentificationRequestTag>

public struct OperationID: Codable, Hashable, Sendable {
    public let rawValue: String

    private init(validated value: String) {
        rawValue = value
    }

    public static func parse(_ value: String) throws -> Self {
        guard value.range(
            of: #"^[A-Za-z0-9_-]{8,128}$"#,
            options: .regularExpression
        ) != nil else {
            throw DomainValidationError.invalidOperationID
        }
        return Self(validated: value)
    }

    public init(from decoder: Decoder) throws {
        let value = try decoder.singleValueContainer().decode(String.self)
        self = try Self.parse(value)
    }

    public func encode(to encoder: Encoder) throws {
        var container = encoder.singleValueContainer()
        try container.encode(rawValue)
    }
}

public struct Revision: Codable, Hashable, Sendable {
    public static let maximumWireValue: UInt64 = 9_007_199_254_740_991
    public let rawValue: UInt64

    private init(validated value: UInt64) {
        rawValue = value
    }

    public static func parse(_ value: UInt64) throws -> Revision {
        guard value <= maximumWireValue else {
            throw DomainValidationError.revisionOverflow
        }
        return Revision(validated: value)
    }

    public init(from decoder: Decoder) throws {
        let value = try decoder.singleValueContainer().decode(UInt64.self)
        self = try Self.parse(value)
    }

    public func encode(to encoder: Encoder) throws {
        var container = encoder.singleValueContainer()
        try container.encode(rawValue)
    }

    public func next() throws -> Revision {
        guard rawValue < Self.maximumWireValue else {
            throw DomainValidationError.revisionOverflow
        }
        return Revision(validated: rawValue + 1)
    }
}
