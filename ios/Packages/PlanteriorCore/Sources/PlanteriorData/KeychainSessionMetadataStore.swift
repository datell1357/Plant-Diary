import Foundation
import Security

public actor KeychainSessionMetadataStore: SessionMetadataPersisting {
    private let service: String
    private let account = "session-metadata"

    public init(service: String) {
        self.service = service
    }

    public func load() throws -> SessionMetadata? {
        var query = baseQuery
        query[kSecReturnData as String] = true
        query[kSecMatchLimit as String] = kSecMatchLimitOne
        var result: CFTypeRef?
        let status = SecItemCopyMatching(query as CFDictionary, &result)
        if status == errSecItemNotFound {
            return nil
        }
        guard status == errSecSuccess, let data = result as? Data else {
            throw KeychainSessionMetadataError.read(status)
        }
        return try JSONDecoder().decode(SessionMetadata.self, from: data)
    }

    public func save(_ metadata: SessionMetadata) throws {
        let data = try JSONEncoder().encode(metadata)
        let attributes = [kSecValueData as String: data]
        let updateStatus = SecItemUpdate(
            baseQuery as CFDictionary,
            attributes as CFDictionary
        )
        if updateStatus == errSecItemNotFound {
            var item = baseQuery
            item[kSecValueData as String] = data
            let addStatus = SecItemAdd(item as CFDictionary, nil)
            guard addStatus == errSecSuccess else {
                throw KeychainSessionMetadataError.write(addStatus)
            }
        } else if updateStatus != errSecSuccess {
            throw KeychainSessionMetadataError.write(updateStatus)
        }
    }

    public func clear() throws {
        let status = SecItemDelete(baseQuery as CFDictionary)
        guard status == errSecSuccess || status == errSecItemNotFound else {
            throw KeychainSessionMetadataError.delete(status)
        }
    }

    private var baseQuery: [String: Any] {
        [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
            kSecAttrAccessible as String: kSecAttrAccessibleWhenUnlockedThisDeviceOnly
        ]
    }
}

public enum KeychainSessionMetadataError: Error, Equatable, Sendable {
    case read(OSStatus)
    case write(OSStatus)
    case delete(OSStatus)
}
