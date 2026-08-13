import Foundation
import PlanteriorData
import Testing

struct BackendContractTests {
    private let decoder = JSONDecoder()

    @Test
    func pinnedManifestDecodesAndRejectsUnavailableLiveIntegration() throws {
        let manifest = try loadManifest()

        #expect(manifest.contractVersion == "android-firebase-boundary/v1")
        #expect(manifest.sourceCommit == "8f362c4de2bc76d16875ac80d0c8ad794e950340")
        #expect(manifest.pinnedFiles == expectedPinnedFiles)
        #expect(Set(manifest.unavailableIntegrations.map(\.id)) == expectedUnavailableIntegrations)
        #expect(Set(manifest.unavailablePolicies.map(\.id)) == expectedUnavailablePolicies)
        #expect(
            Dictionary(uniqueKeysWithValues: manifest.unavailablePolicies.map { ($0.id, $0.value) })
                == expectedUnavailablePolicyValues
        )
        #expect(throws: BackendContractError.integrationUnavailable) {
            try manifest.requireLiveIntegration("plantIdentification.submit")
        }
        #expect(throws: BackendContractError.integrationUnavailable) {
            try manifest.requireLiveIntegration("omitted.integration")
        }
        #expect(throws: BackendContractError.integrationUnavailable) {
            try manifest.requireEnforcedPolicy("weatherStaleAfter")
        }
    }

    @Test
    func validOwnerMutationRoundTripsAndForbiddenFixturesFailClosed() throws {
        let manifest = try loadManifest()
        let mutation = try #require(manifest.validOwnerMutations.first)
        let data = try JSONEncoder().encode(mutation)

        #expect(try decoder.decode(OwnerMutationFixture.self, from: data) == mutation)
        try manifest.validateForbiddenFixtures()
    }

    @Test
    func fakeReturnsOriginalDuplicateAndPreservesConflictState() throws {
        var fake = ProvisionalOwnerMutationFake()
        let create = try OwnerMutationRequest(
            ownerUID: "account-a",
            collection: "personalPlants",
            documentID: "plant-a",
            expectedRevision: 0,
            idempotencyKey: "operation-0001"
        )

        #expect(try fake.apply(create) == .applied(revision: 1))
        #expect(try fake.apply(create) == .duplicate(revision: 1))

        let stale = try OwnerMutationRequest(
            ownerUID: "account-a",
            collection: "personalPlants",
            documentID: "plant-a",
            expectedRevision: 0,
            idempotencyKey: "operation-0002"
        )
        #expect(try fake.apply(stale) == .conflict(actualRevision: 1))
        #expect(fake.revision(collection: "personalPlants", documentID: "plant-a") == 1)

        let anotherAccount = try OwnerMutationRequest(
            ownerUID: "account-b",
            collection: "personalPlants",
            documentID: "plant-a",
            expectedRevision: 0,
            idempotencyKey: "operation-0001"
        )
        #expect(try fake.apply(anotherAccount) == .applied(revision: 1))
    }

    private var expectedPinnedFiles: [PinnedContractFile] {
        let expected = """
        firebase.json=6994857f9ae052d2ccdd068c202eec7c26d481e76d3e87cb7cddd69ada48885a
        firestore.indexes.json=f06b33aebd2dac2a045754460f935f579cce4dd7aa606c6fffe044870ff569ef
        firestore.rules=5347dc93d463d09803c0e4d4cac137779fcf967bbca8bb78e2fbc2a0fa6a41b8
        storage.rules=bc8608904da0e171c4e90e5f791c3608e797e08177c89c5eed3c4c806cdf2d35
        functions/src/contracts.ts=331506c0ce569a616807a24a5840ce602f1be2848ec1d3e8c1e52427ae3e88c1
        """
        return expected.split(separator: "\n").map {
            let parts = $0.split(separator: "=", maxSplits: 1)
            return .init(path: String(parts[0]), sha256: String(parts[1]))
        }
    }

    private var expectedUnavailableIntegrations: Set<String> {
        [
            "plantIdentification.submit", "weather.refreshCanonicalSnapshot",
            "notifications.registerEndpoint", "notifications.dispatchWatering",
            "notifications.dispatchWeatherRisk", "items.acquire", "items.setApplied",
            "shares.create", "shares.revoke", "shares.resolveImage", "deletion.previewScope",
            "deletion.request", "deletion.cancel", "deletion.executeScheduled",
            "identificationOriginals.cleanup"
        ]
    }

    private var expectedUnavailablePolicies: Set<String> {
        [
            "identificationOriginalRetention", "weatherStaleAfter",
            "publicShareLifetime", "accountDeletionGrace"
        ]
    }

    private var expectedUnavailablePolicyValues: [String: String?] {
        [
            "identificationOriginalRetention": "PT24H",
            "weatherStaleAfter": "PT3H",
            "publicShareLifetime": "P30D",
            "accountDeletionGrace": "P7D"
        ]
    }

    private func loadManifest() throws -> BackendContractManifest {
        let url = try #require(
            Bundle.module.url(
                forResource: "backend-contract-v1",
                withExtension: "json",
                subdirectory: "Fixtures"
            )
        )
        return try decoder.decode(BackendContractManifest.self, from: Data(contentsOf: url))
    }
}
