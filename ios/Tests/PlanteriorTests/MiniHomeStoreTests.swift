import Foundation
@testable import Planterior
import PlanteriorDomain
import Testing

@MainActor
struct MiniHomeStoreTests {
    @Test
    func unchangedSaveDoesNotCallServerOrAdvanceRevision() async throws {
        let fixture = try MiniHomeStoreFixture()
        let initial = try fixture.snapshot(name: "기준 저장본", revision: 1)
        let service = MiniHomeStoreServiceFake(snapshots: [fixture.accountA: initial])
        let store = fixture.store(service: service, operationIDs: ["operation-edit"])
        await store.mount(accountID: fixture.accountA, defaultDraft: nil)

        await store.save()
        #expect(service.requests.isEmpty)
        #expect(store.committed?.revision.rawValue == 1)

        store.renameDraft("변경된 저장본")
        await store.save()
        await store.save()
        #expect(service.requests.count == 1)
        #expect(store.committed?.revision.rawValue == 2)
    }

    @Test
    func twoClientsConflictAndExplicitReapplyCommitsRevisionThree() async throws {
        // Given
        let fixture = try MiniHomeStoreFixture()
        let initial = try fixture.snapshot(name: "기준 저장본", revision: 1)
        let service = MiniHomeStoreServiceFake(snapshots: [fixture.accountA: initial])
        let first = fixture.store(service: service, operationIDs: ["operation-a"])
        let second = fixture.store(
            service: service,
            operationIDs: ["operation-b", "operation-reapply"]
        )
        await first.mount(accountID: fixture.accountA, defaultDraft: nil)
        await second.mount(accountID: fixture.accountA, defaultDraft: nil)
        first.renameDraft("첫 기기 저장본")
        second.renameDraft("둘째 기기 초안")

        // When
        await first.save()
        await second.save()

        // Then
        #expect(first.committed?.revision.rawValue == 2)
        #expect(second.state == .conflicted(latestRevision: 2))
        #expect(second.committed == initial.home)
        #expect(second.draft?.name == "둘째 기기 초안")
        #expect(second.conflictSnapshot?.home.name == "첫 기기 저장본")

        // When
        await second.resolveConflict(.save)

        // Then
        #expect(second.committed?.name == "둘째 기기 초안")
        #expect(second.committed?.revision.rawValue == 3)
        #expect(second.state == .saved)
        #expect(service.requests.map(\.operationID.rawValue) == [
            "operation-a", "operation-b", "operation-reapply"
        ])
    }

    @Test
    func discardAppliesExactConflictSnapshotAndPreservesPlacementOrder() async throws {
        // Given
        let fixture = try MiniHomeStoreFixture()
        let initial = try fixture.snapshot(name: "기준 저장본", revision: 1)
        let returned = try fixture.snapshot(
            name: "서버 저장본",
            revision: 2,
            placements: fixture.placements.reversed()
        )
        let service = MiniHomeStoreServiceFake(snapshots: [fixture.accountA: initial])
        service.nextSaveResult = .conflict(returned)
        let store = fixture.store(service: service, operationIDs: ["operation-conflict"])
        await store.mount(accountID: fixture.accountA, defaultDraft: nil)
        store.renameDraft("보존할 초안")

        // When
        await store.save()
        await store.resolveConflict(.discard)

        // Then
        #expect(store.committed == returned.home)
        #expect(store.draft == returned.home)
        #expect(store.committed?.placements.map(\.id) == returned.home.placements.map(\.id))
        #expect(fixture.cache.load(accountID: fixture.accountA) == returned)
    }

    @Test
    func transportRetryReusesOperationButChangedSaveUsesANewOperation() async throws {
        // Given
        let fixture = try MiniHomeStoreFixture()
        let initial = try fixture.snapshot(name: "기준 저장본", revision: 1)
        let service = MiniHomeStoreServiceFake(snapshots: [fixture.accountA: initial])
        service.saveFailures = [.transport]
        let store = fixture.store(
            service: service,
            operationIDs: ["operation-retry", "operation-changed"]
        )
        await store.mount(accountID: fixture.accountA, defaultDraft: nil)
        store.renameDraft("재시도 초안")

        // When
        await store.save()
        await store.save()
        store.renameDraft("변경된 저장")
        await store.save()

        // Then
        #expect(service.requests.map(\.operationID.rawValue) == [
            "operation-retry", "operation-retry", "operation-changed"
        ])
        #expect(store.committed?.revision.rawValue == 3)
        #expect(store.committed?.name == "변경된 저장")
    }

    @Test
    func failedSavePreservesDraftCommittedAndVerifiedCache() async throws {
        // Given
        let fixture = try MiniHomeStoreFixture()
        let initial = try fixture.snapshot(name: "검증된 저장본", revision: 1)
        try fixture.cache.store(initial)
        let service = MiniHomeStoreServiceFake(snapshots: [fixture.accountA: initial])
        service.saveFailures = [.transport]
        let store = fixture.store(service: service, operationIDs: ["operation-failure"])
        await store.mount(accountID: fixture.accountA, defaultDraft: nil)
        store.renameDraft("실패한 초안")

        // When
        await store.save()

        // Then
        #expect(store.state == .failed)
        #expect(store.committed == initial.home)
        #expect(store.draft?.name == "실패한 초안")
        #expect(fixture.cache.load(accountID: fixture.accountA) == initial)
    }

    @Test
    func authenticatedLegacyCandidateIsExplicitOnlyAndEmptyServerDoesNotUpload() async throws {
        // Given
        let fixture = try MiniHomeStoreFixture()
        let local = try fixture.room(name: "명시적 이전 후보", revision: 1)
        try fixture.defaults.set(
            JSONEncoder().encode(local),
            forKey: "home.\(fixture.accountA).committed-mini-home"
        )
        let defaultDraft = try fixture.room(name: "새 미니홈", revision: 0)
        let service = MiniHomeStoreServiceFake()
        let store = fixture.store(service: service, operationIDs: [])

        // When
        await store.mount(accountID: fixture.accountA, defaultDraft: defaultDraft)

        // Then
        #expect(store.committed == nil)
        #expect(store.draft == defaultDraft)
        #expect(store.localCandidate?.home == local)
        #expect(service.requests.isEmpty)
        #expect(fixture.defaults.data(
            forKey: "home.\(fixture.accountA).committed-mini-home"
        ) != nil)
    }
}
