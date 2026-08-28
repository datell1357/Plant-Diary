import CoreFoundation
import Foundation

enum MiniHomeResponseJSON {
    static func root(_ data: Data) throws -> [String: Any] {
        do {
            return try object(JSONSerialization.jsonObject(with: data))
        } catch let error as MiniHomeAuthoritativeError {
            throw error
        } catch {
            throw MiniHomeAuthoritativeError.malformedResponse
        }
    }

    static func object(_ value: Any?) throws -> [String: Any] {
        guard let object = value as? [String: Any] else {
            throw MiniHomeAuthoritativeError.malformedResponse
        }
        return object
    }

    static func array(_ value: Any?) throws -> [Any] {
        guard let array = value as? [Any] else {
            throw MiniHomeAuthoritativeError.malformedResponse
        }
        return array
    }

    static func exactKeys(
        _ object: [String: Any],
        _ expected: Set<String>
    ) throws {
        guard Set(object.keys) == expected else {
            throw MiniHomeAuthoritativeError.malformedResponse
        }
    }

    static func string(_ value: Any?) throws -> String {
        guard let string = value as? String else {
            throw MiniHomeAuthoritativeError.malformedResponse
        }
        return string
    }

    static func uint(_ value: Any?) throws -> UInt64 {
        guard let number = value as? NSNumber,
              CFGetTypeID(number) != CFBooleanGetTypeID()
        else {
            throw MiniHomeAuthoritativeError.malformedResponse
        }
        let double = number.doubleValue
        guard double.isFinite,
              double >= 0,
              double.rounded(.towardZero) == double,
              double <= Double(RevisionWire.maximum)
        else {
            throw MiniHomeAuthoritativeError.malformedResponse
        }
        return UInt64(double)
    }

    static func int(_ value: Any?) throws -> Int {
        let unsigned = try uint(value)
        guard unsigned <= UInt64(Int.max) else {
            throw MiniHomeAuthoritativeError.malformedResponse
        }
        return Int(unsigned)
    }

    static func double(_ value: Any?) throws -> Double {
        guard let number = value as? NSNumber,
              CFGetTypeID(number) != CFBooleanGetTypeID()
        else {
            throw MiniHomeAuthoritativeError.malformedResponse
        }
        let double = number.doubleValue
        guard double.isFinite else {
            throw MiniHomeAuthoritativeError.malformedResponse
        }
        return double
    }

    static func bool(_ value: Any?) throws -> Bool {
        guard let number = value as? NSNumber,
              CFGetTypeID(number) == CFBooleanGetTypeID()
        else {
            throw MiniHomeAuthoritativeError.malformedResponse
        }
        return number.boolValue
    }
}

private enum RevisionWire {
    static let maximum: UInt64 = 9_007_199_254_740_991
}
