import Foundation
@testable import Planterior
import Testing
import XCTest

@MainActor
struct MiniHomeStoreAccountTests {
    @Test
    func accountSwitchRejectsStaleLoadAndKeepsCachesIsolated() async throws {
        // Given
        let fixture = try MiniHomeStoreFixture()
        let accountA = try fixture.snapshot(
            accountID: fixture.accountA,
            name: "A 저장본",
            revision: 1
        )
        let accountB = try fixture.snapshot(
            accountID: fixture.accountB,
            name: "B 저장본",
            revision: 4
        )
        try fixture.cache.store(accountB)
        let service = MiniHomeStoreServiceFake(snapshots: [fixture.accountB: accountB])
        service.suspendedLoadAccount = fixture.accountA
        let store = fixture.store(service: service, operationIDs: [])
        let suspendedLoadEntered = service.expectSuspendedLoad()
        let loadA = Task { await store.mount(accountID: fixture.accountA, defaultDraft: nil) }
        let waitResult = await XCTWaiter.fulfillment(
            of: [suspendedLoadEntered],
            timeout: 1
        )
        #expect(waitResult == .completed)

        // When
        await store.mount(accountID: fixture.accountB, defaultDraft: nil)
        service.resumeSuspendedLoad(with: .snapshot(accountA))
        await loadA.value

        // Then
        #expect(store.accountID == fixture.accountB)
        #expect(store.committed == accountB.home)
        #expect(fixture.cache.load(accountID: fixture.accountA) == nil)
        #expect(fixture.cache.load(accountID: fixture.accountB) == accountB)
    }

    @Test
    func accountSwitchRejectsStaleSaveResponse() async throws {
        // Given
        let fixture = try MiniHomeStoreFixture()
        let accountA = try fixture.snapshot(
            accountID: fixture.accountA,
            name: "A 저장본",
            revision: 1
        )
        let accountB = try fixture.snapshot(
            accountID: fixture.accountB,
            name: "B 저장본",
            revision: 3
        )
        let staleCommit = try fixture.snapshot(
            accountID: fixture.accountA,
            name: "늦게 도착한 A 저장",
            revision: 2
        )
        let service = MiniHomeStoreServiceFake(snapshots: [
            fixture.accountA: accountA,
            fixture.accountB: accountB
        ])
        service.suspendsSave = true
        let store = fixture.store(service: service, operationIDs: ["stale-save"])
        await store.mount(accountID: fixture.accountA, defaultDraft: nil)
        store.renameDraft("늦게 도착한 A 저장")
        let suspendedSaveEntered = service.expectSuspendedSave()
        let saveA = Task { await store.save() }
        let waitResult = await XCTWaiter.fulfillment(
            of: [suspendedSaveEntered],
            timeout: 1
        )
        #expect(waitResult == .completed)

        // When
        await store.mount(accountID: fixture.accountB, defaultDraft: nil)
        service.resumeSuspendedSave(with: .committed(staleCommit))
        await saveA.value

        // Then
        #expect(store.accountID == fixture.accountB)
        #expect(store.committed == accountB.home)
        #expect(fixture.cache.load(accountID: fixture.accountA) == accountA)
        #expect(fixture.cache.load(accountID: fixture.accountB) == accountB)
    }

    @Test
    func signOutClearsMemoryWithoutDeletingVerifiedAccountCache() async throws {
        // Given
        let fixture = try MiniHomeStoreFixture()
        let accountA = try fixture.snapshot(name: "A 저장본", revision: 1)
        let service = MiniHomeStoreServiceFake(snapshots: [fixture.accountA: accountA])
        let store = fixture.store(service: service, operationIDs: [])
        await store.mount(accountID: fixture.accountA, defaultDraft: nil)

        // When
        await store.mount(accountID: nil, defaultDraft: nil)

        // Then
        #expect(store.accountID == nil)
        #expect(store.committed == nil)
        #expect(store.draft == nil)
        #expect(fixture.cache.load(accountID: fixture.accountA) == accountA)
    }

    @Test
    func signedOutMountNeverMigratesOrUploadsLocalCandidate() async throws {
        // Given
        let fixture = try MiniHomeStoreFixture()
        let local = try fixture.room(name: "로그아웃 로컬 후보", revision: 0)
        try fixture.defaults.set(
            JSONEncoder().encode(local),
            forKey: "home.signed-out.committed-mini-home"
        )
        let service = MiniHomeStoreServiceFake()
        let store = fixture.store(service: service, operationIDs: [])

        // When
        await store.mount(accountID: nil, defaultDraft: local)

        // Then
        #expect(store.accountID == nil)
        #expect(store.committed == nil)
        #expect(store.draft == nil)
        #expect(store.localCandidate == nil)
        #expect(service.loadAccounts.isEmpty)
        #expect(service.requests.isEmpty)
        #expect(fixture.defaults.data(forKey: "home.signed-out.committed-mini-home") != nil)
    }
}
