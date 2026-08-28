import Foundation
import PlanteriorDomain

extension MiniHomeResponseDecoder {
    static func validID(_ raw: Any?) throws -> String {
        let value = try MiniHomeResponseJSON.string(raw)
        guard value.range(
            of: "^[A-Za-z0-9_-]{1,128}$",
            options: .regularExpression
        ) != nil else { throw malformed() }
        return value
    }

    static func validName(_ raw: Any?) throws -> String {
        let value = try MiniHomeResponseJSON.string(raw)
        guard !value.isEmpty,
              value.utf16.count <= 100,
              value == value.trimmingCharacters(in: .whitespacesAndNewlines)
        else { throw malformed() }
        return value
    }

    static func validHash(_ raw: Any?) throws -> String {
        let value = try MiniHomeResponseJSON.string(raw)
        guard value.range(
            of: "^[a-f0-9]{64}$",
            options: .regularExpression
        ) != nil else { throw malformed() }
        return value
    }

    static func requireVersion(_ raw: Any?) throws {
        guard try MiniHomeResponseJSON.uint(raw) == 1 else { throw malformed() }
    }

    static func instant(_ epochMillis: UInt64) throws -> Instant {
        let date = Date(timeIntervalSince1970: Double(epochMillis) / 1000)
        let formatter = ISO8601DateFormatter()
        formatter.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        return try Instant.parse(formatter.string(from: date))
    }

    static func malformed() -> MiniHomeAuthoritativeError {
        .malformedResponse
    }
}
