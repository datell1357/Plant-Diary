import Foundation

public struct CalendarDate: RawRepresentable, Codable, Hashable, Sendable {
    public let rawValue: String

    public init(rawValue: String) {
        self.rawValue = rawValue
    }

    public static func parse(
        _ value: String,
        calendar: Calendar = Calendar(identifier: .gregorian)
    ) throws -> CalendarDate {
        let pattern = #"^\d{4}-\d{2}-\d{2}$"#
        guard value.range(of: pattern, options: .regularExpression) != nil else {
            throw DomainValidationError.invalidCalendarDate
        }
        let parts = value.split(separator: "-").compactMap { Int($0) }
        guard parts.count == 3 else {
            throw DomainValidationError.invalidCalendarDate
        }
        var components = DateComponents()
        components.calendar = calendar
        components.timeZone = TimeZone(secondsFromGMT: 0)
        components.year = parts[0]
        components.month = parts[1]
        components.day = parts[2]
        guard let date = calendar.date(from: components) else {
            throw DomainValidationError.invalidCalendarDate
        }
        let resolved = calendar.dateComponents([.year, .month, .day], from: date)
        guard resolved.year == parts[0], resolved.month == parts[1], resolved.day == parts[2] else {
            throw DomainValidationError.invalidCalendarDate
        }
        return CalendarDate(rawValue: value)
    }
}

public struct LocalTime: RawRepresentable, Codable, Hashable, Sendable {
    public let rawValue: String

    public init(rawValue: String) {
        self.rawValue = rawValue
    }

    public static func parse(_ value: String) throws -> LocalTime {
        guard value.range(
            of: #"^(?:[01]\d|2[0-3]):[0-5]\d:[0-5]\d$"#,
            options: .regularExpression
        ) != nil else {
            throw DomainValidationError.invalidLocalTime
        }
        return LocalTime(rawValue: value)
    }
}

public struct Instant: RawRepresentable, Codable, Hashable, Sendable {
    public let rawValue: String

    public init(rawValue: String) {
        self.rawValue = rawValue
    }

    public static func parse(_ value: String) throws -> Instant {
        let formatter = ISO8601DateFormatter()
        formatter.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        let date = formatter.date(from: value) ??
            ISO8601DateFormatter().date(from: value)
        guard date != nil else {
            throw DomainValidationError.invalidInstant
        }
        return Instant(rawValue: value)
    }
}

public struct TimeZoneID: RawRepresentable, Codable, Hashable, Sendable {
    public let rawValue: String

    public init(rawValue: String) {
        self.rawValue = rawValue
    }

    public static func parse(_ value: String) throws -> TimeZoneID {
        guard TimeZone(identifier: value) != nil else {
            throw DomainValidationError.invalidTimeZone
        }
        return TimeZoneID(rawValue: value)
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
