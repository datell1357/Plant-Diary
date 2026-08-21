import Foundation
@testable import PlanteriorData
import PlanteriorDomain
import Security
import Testing

struct KeychainSessionMetadataStoreTests {
    @Test
    func deletionQueryContainsOnlyAttributesAcceptedBySecItemDelete() {
        // Given
        let service = "com.planterior.tests.delete-query"

        // When
        let query = KeychainSessionMetadataStore.deletionQuery(service: service)

        // Then
        #expect(Set(query.keys) == Set([
            kSecClass as String,
            kSecAttrService as String,
            kSecAttrAccount as String
        ]))
        #expect(query[kSecAttrAccessible as String] == nil)
    }

    @Test
    func roundTripsAndClearsMinimalSessionMetadata() async throws {
        let service = "com.planterior.tests.\(UUID().uuidString)"
        let store = KeychainSessionMetadataStore(service: service)
        let accountID = try AccountID.parse("account-42")
        let metadata = SessionMetadata(accountID: accountID, provider: .apple)

        try await store.save(metadata)
        #expect(try await store.load() == metadata)

        try await store.clear()
        #expect(try await store.load() == nil)
    }
}
