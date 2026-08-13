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
}

public struct OpaqueID<Tag>: RawRepresentable, Codable, Hashable, Sendable {
    public let rawValue: String

    public init(rawValue: String) {
        self.rawValue = rawValue
    }

    public static func parse(_ value: String) throws -> Self {
        guard value.range(
            of: #"^[A-Za-z0-9_-]{1,128}$"#,
            options: .regularExpression
        ) != nil else {
            throw DomainValidationError.invalidOpaqueID
        }
        return Self(rawValue: value)
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

public struct OperationID: RawRepresentable, Codable, Hashable, Sendable {
    public let rawValue: String

    public init(rawValue: String) {
        self.rawValue = rawValue
    }

    public static func parse(_ value: String) throws -> Self {
        guard value.range(
            of: #"^[A-Za-z0-9_-]{8,128}$"#,
            options: .regularExpression
        ) != nil else {
            throw DomainValidationError.invalidOperationID
        }
        return Self(rawValue: value)
    }
}

public struct Revision: RawRepresentable, Codable, Hashable, Sendable {
    public let rawValue: UInt64

    public init(rawValue: UInt64) {
        self.rawValue = rawValue
    }

    public func next() throws -> Revision {
        guard rawValue < UInt64.max else {
            throw DomainValidationError.revisionOverflow
        }
        return Revision(rawValue: rawValue + 1)
    }
}
