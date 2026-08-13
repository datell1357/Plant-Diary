import Foundation
@testable import PlanteriorData
import PlanteriorDomain
import Testing

struct KeychainSessionMetadataStoreTests {
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
