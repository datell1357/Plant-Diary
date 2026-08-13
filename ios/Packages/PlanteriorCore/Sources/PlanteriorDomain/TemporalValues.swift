import Foundation

public protocol ValidatedStringValue: Codable {
    var rawValue: String { get }
    static func parse(_ value: String) throws -> Self
}

public extension ValidatedStringValue {
    init(from decoder: Decoder) throws {
        let value = try decoder.singleValueContainer().decode(String.self)
        self = try Self.parse(value)
    }

    func encode(to encoder: Encoder) throws {
        var container = encoder.singleValueContainer()
        try container.encode(rawValue)
    }
}

public struct CalendarDate: ValidatedStringValue, Hashable, Sendable {
    public let rawValue: String

    private init(validated value: String) {
        rawValue = value
    }

    public static func parse(_ value: String) throws -> CalendarDate {
        guard value.range(
            of: #"^\d{4}-(?:0[1-9]|1[0-2])-(?:0[1-9]|[12]\d|3[01])$"#,
            options: .regularExpression
        ) != nil else {
            throw DomainValidationError.invalidCalendarDate
        }
        let parts = value.split(separator: "-").compactMap { Int($0) }
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = TimeZone(secondsFromGMT: 0)!
        let components = DateComponents(
            calendar: calendar,
            timeZone: calendar.timeZone,
            year: parts[0],
            month: parts[1],
            day: parts[2]
        )
        guard let date = calendar.date(from: components) else {
            throw DomainValidationError.invalidCalendarDate
        }
        let resolved = calendar.dateComponents([.year, .month, .day], from: date)
        guard resolved.year == parts[0], resolved.month == parts[1], resolved.day == parts[2] else {
            throw DomainValidationError.invalidCalendarDate
        }
        return CalendarDate(validated: value)
    }
}

public struct LocalTime: ValidatedStringValue, Hashable, Sendable {
    public let rawValue: String

    private init(validated value: String) {
        rawValue = value
    }

    public static func parse(_ value: String) throws -> LocalTime {
        let pattern = #"^(?:[01]\d|2[0-3]):[0-5]\d(?::[0-5]\d)?$"#
        guard value.range(of: pattern, options: .regularExpression) != nil else {
            throw DomainValidationError.invalidLocalTime
        }
        return LocalTime(validated: value.count == 5 ? "\(value):00" : value)
    }
}

public struct Instant: ValidatedStringValue, Hashable, Sendable {
    public let rawValue: String

    private init(validated value: String) {
        rawValue = value
    }

    public static func parse(_ value: String) throws -> Instant {
        let date = #"\d{4}-(?:0[1-9]|1[0-2])-(?:0[1-9]|[12]\d|3[01])"#
        let time = #"(?:[01]\d|2[0-3]):[0-5]\d:[0-5]\d(?:\.\d{1,9})?"#
        let pattern = "^\(date)T\(time)Z$"
        guard value.range(of: pattern, options: .regularExpression) != nil else {
            throw DomainValidationError.invalidInstant
        }
        let datePart = String(value.prefix(10))
        _ = try CalendarDate.parse(datePart)
        return Instant(validated: value)
    }
}

public struct TimeZoneID: ValidatedStringValue, Hashable, Sendable {
    public let rawValue: String

    private init(validated value: String) {
        rawValue = value
    }

    public static func parse(_ value: String) throws -> TimeZoneID {
        guard value == "UTC" || (
            value.contains("/") &&
                TimeZone.knownTimeZoneIdentifiers.contains(value)
        ) else {
            throw DomainValidationError.invalidTimeZone
        }
        return TimeZoneID(validated: value)
    }
}

public protocol DomainClock: Sendable {
    func now() -> Instant
}

public struct FixedClock: DomainClock {
    private let instant: Instant

    public init(instant: Instant) {
        self.instant = instant
    }

    public func now() -> Instant {
        instant
    }
}
