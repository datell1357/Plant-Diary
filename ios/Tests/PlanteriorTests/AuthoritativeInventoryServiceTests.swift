import Foundation
@testable import Planterior
import PlanteriorDomain
import Testing

@MainActor
struct AuthoritativeInventoryServiceTests {
    @Test
    func automaticRefreshUsesFreshSnapshotWithoutASecondServerRead() async throws {
        let fixture = try InventoryRepositoryFixture()
        let item = try Self.serverItem()
        let service = try InventoryServiceFake(
            loads: [Self.snapshot(item: item, owned: [], generation: 6, hash: "b")],
            receipt: InventoryOwnershipReceipt(
                kind: .alreadyOwned,
                ownerID: fixture.accountA,
                itemID: item.id,
                catalogRevision: item.revision,
                ownershipRevision: Revision.parse(1),
                acquiredAt: fixture.now
            )
        )
        let repository = InventoryRepository(
            defaults: fixture.defaults,
            now: fixture.now,
            allowsLocalAcquisition: false,
            authoritativeService: service
        )
        repository.mount(accountID: fixture.accountA)

        #expect(await repository.refreshAuthoritative())
        #expect(await repository.refreshAuthoritative())
        #expect(service.loadCount == 1)
    }

    @Test
    func forcedRefreshReadsAgainAfterAutomaticRefresh() async throws {
        let fixture = try InventoryRepositoryFixture()
        let item = try Self.serverItem()
        let service = try InventoryServiceFake(
            loads: [
                Self.snapshot(item: item, owned: [], generation: 6, hash: "b"),
                Self.snapshot(item: item, owned: [], generation: 7, hash: "c")
            ],
            receipt: InventoryOwnershipReceipt(
                kind: .alreadyOwned,
                ownerID: fixture.accountA,
                itemID: item.id,
                catalogRevision: item.revision,
                ownershipRevision: Revision.parse(1),
                acquiredAt: fixture.now
            )
        )
        let repository = InventoryRepository(
            defaults: fixture.defaults,
            now: fixture.now,
            allowsLocalAcquisition: false,
            authoritativeService: service
        )
        repository.mount(accountID: fixture.accountA)

        #expect(await repository.refreshAuthoritative())
        #expect(await repository.refreshAuthoritative(force: true))
        #expect(service.loadCount == 2)
        #expect(repository.provenance?.inventoryGeneration == 7)
    }

    @Test
    func decodesContractV3SnapshotAndRejectsTampering() throws {
        let snapshot = try AuthoritativeInventoryResponseDecoder.snapshot(
            data: Self.validSnapshotData(),
            expectedAccountID: "inventory-account-a"
        )

        #expect(snapshot.inventoryGeneration == 7)
        #expect(snapshot.catalog.map(\.id.rawValue) == ["item-lamp"])
        #expect(snapshot.ownedItems.map(\.itemID.rawValue) == ["item-lamp"])
        #expect(snapshot.ownedItems.first?.revision.rawValue == 5)

        let source = try #require(
            try String(data: Self.validSnapshotData(), encoding: .utf8)
        )
        try assertSnapshotTamperingIsRejected(source: source)

        let placementOnly = source.replacingOccurrences(
            of: "\"applied\":false",
            with: "\"applied\":true"
        )
        let placementResponse = try JSONDecoder().decode(
            InventorySnapshotResponse.self,
            from: Data(placementOnly.utf8)
        )
        let baselineResponse = try JSONDecoder().decode(
            InventorySnapshotResponse.self,
            from: Data(source.utf8)
        )
        #expect(
            InventorySnapshotHasher.hash(placementResponse)
                == InventorySnapshotHasher.hash(baselineResponse)
        )
        _ = try AuthoritativeInventoryResponseDecoder.snapshot(
            data: Data(placementOnly.utf8),
            expectedAccountID: "inventory-account-a"
        )

        #expect(throws: InventoryProviderError.malformedResponse) {
            try AuthoritativeInventoryResponseDecoder.snapshot(
                data: Self.validSnapshotData(),
                expectedAccountID: "inventory-account-b"
            )
        }
    }

    @Test
    func validatesServerIssuedAcquisitionReceiptAndCondition() throws {
        let itemID = try ItemID.parse("item-lamp")
        let receipt = try AuthoritativeInventoryResponseDecoder.receipt(
            data: Self.validReceiptData(),
            expectedAccountID: "inventory-account-a",
            expectedItemID: itemID
        )
        #expect(receipt.kind == .acquired)
        #expect(receipt.catalogRevision.rawValue == 3)
        #expect(receipt.ownershipRevision.rawValue == 5)

        #expect(throws: InventoryProviderError.malformedResponse) {
            try AuthoritativeInventoryResponseDecoder.receipt(
                data: Self.validReceiptData(),
                expectedAccountID: "inventory-account-b",
                expectedItemID: itemID
            )
        }
        #expect(throws: InventoryConditionNotMet(condition: "registered-plant")) {
            try AuthoritativeInventoryResponseDecoder.receipt(
                data: Self.conditionReceiptData,
                expectedAccountID: "inventory-account-a",
                expectedItemID: itemID
            )
        }
    }

    @Test
    func productionAcquisitionPersistsOnlyFreshServerAuthorizedOwnership() async throws {
        let fixture = try InventoryRepositoryFixture()
        let item = try Self.serverItem()
        let owned = try OwnedItem(
            itemID: item.id,
            acquiredAt: fixture.now,
            applied: false,
            revision: Revision.parse(5)
        )
        let service = InventoryServiceFake(
            loads: [
                Self.snapshot(item: item, owned: [], generation: 6, hash: "b"),
                Self.snapshot(item: item, owned: [owned], generation: 7, hash: "c")
            ],
            receipt: InventoryOwnershipReceipt(
                kind: .acquired,
                ownerID: fixture.accountA,
                itemID: item.id,
                catalogRevision: item.revision,
                ownershipRevision: owned.revision,
                acquiredAt: fixture.now
            )
        )
        let repository = InventoryRepository(
            defaults: fixture.defaults,
            now: fixture.now,
            allowsLocalAcquisition: false,
            authoritativeService: service
        )
        repository.mount(accountID: fixture.accountA)

        #expect(await repository.refreshAuthoritative())
        #expect(repository.ownedItems.isEmpty)
        #expect(
            await repository.acquire(itemID: item.id, metConditions: [])
                == .acquired
        )
        #expect(repository.ownedItems == [owned])
        let persisted = try #require(repository.persistedSnapshot())
        #expect(persisted.source == .serverAuthorized)
        #expect(persisted.provenance?.ownerID == fixture.accountA)
        #expect(persisted.provenance?.inventoryGeneration == 7)
        #expect(persisted.provenance?.snapshotHash == String(repeating: "c", count: 64))
    }

    @Test
    func cachedOrForgedOwnershipCannotAuthorizeAfterRemount() async throws {
        let fixture = try InventoryRepositoryFixture()
        let item = try Self.serverItem()
        let forged = try OwnedItem(
            itemID: item.id,
            acquiredAt: fixture.now,
            applied: false,
            revision: Revision.parse(99)
        )
        let key = "inventory.\(fixture.accountA).snapshot"
        let encoded = try JSONEncoder().encode(
            InventorySnapshot(
                catalog: [item],
                ownedItems: [forged],
                source: .serverAuthorized,
                provenance: InventorySnapshotProvenance(
                    ownerID: fixture.accountA,
                    inventoryGeneration: 99,
                    snapshotHash: String(repeating: "f", count: 64)
                )
            )
        )
        fixture.defaults.set(encoded, forKey: key)
        let repository = InventoryRepository(
            defaults: fixture.defaults,
            now: fixture.now,
            allowsLocalAcquisition: false,
            authoritativeService: UnavailableAuthoritativeInventoryService()
        )
        repository.mount(accountID: fixture.accountA)

        #expect(repository.ownedItems == [forged])
        #expect(repository.allowsPlacementMutation == false)
        #expect(
            await repository.acquire(itemID: item.id, metConditions: [])
                == .failed(.providerUnavailable)
        )
        #expect(repository.ownedItems == [forged])
        #expect(repository.persistedSnapshot()?.ownedItems == [forged])
    }

    @Test
    func productionFactoryFailsClosedWithoutFirebaseConfiguration() {
        let unavailable = AuthoritativeInventoryServiceFactory.make(
            firebaseConfigured: false
        )
        #expect(unavailable is UnavailableAuthoritativeInventoryService)
        let configured = AuthoritativeInventoryServiceFactory.make(
            firebaseConfigured: true
        )
        #expect(configured is FirebaseAuthoritativeInventoryService)
    }
}
