import Foundation

extension AuthoritativeInventoryResponseDecoder {
    static func validateMediaObject(_ raw: Any?) throws {
        try exactKeys(dictionary(raw), mediaKeys)
    }

    static func rootObject(_ data: Data) throws -> [String: Any] {
        do {
            return try dictionary(JSONSerialization.jsonObject(with: data))
        } catch let error as InventoryProviderError {
            throw error
        } catch {
            throw malformed()
        }
    }

    static func decode<T: Decodable>(
        _ type: T.Type,
        data: Data
    ) throws -> T {
        do {
            return try JSONDecoder().decode(type, from: data)
        } catch {
            throw malformed()
        }
    }

    static func dictionary(_ value: Any?) throws -> [String: Any] {
        guard let value = value as? [String: Any] else { throw malformed() }
        return value
    }

    static func array(_ value: Any?) throws -> [Any] {
        guard let value = value as? [Any] else { throw malformed() }
        return value
    }

    static func exactKeys(
        _ object: [String: Any],
        _ expected: Set<String>
    ) throws {
        guard Set(object.keys) == expected else { throw malformed() }
    }

    static func malformed() -> InventoryProviderError {
        .malformedResponse
    }
}
