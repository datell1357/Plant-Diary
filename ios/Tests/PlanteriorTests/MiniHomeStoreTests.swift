import Foundation
@testable import Planterior
import PlanteriorData
import PlanteriorDomain
import Testing

@MainActor
struct MiniHomeStoreTests {
    @Test
    func savesExplicitlyAndRestoresCommittedRoom() throws {
        let fixture = try fixture()
        let repository = LocalMiniHomeRepository(
            accountID: fixture.accountID,
            defaults: fixture.defaults,
            now: fixture.now
        )
        let initialRoom = try room(name: "처음 방", revision: 0)
        fixture.seed(room: initialRoom)
        let store = MiniHomeStore(repository: repository)
        store.mount()

        store.renameDraft("저장한 방")
        #expect(store.committed?.name == "처음 방")
        try store.save()

        let relaunched = MiniHomeStore(repository: repository)
        relaunched.mount()
        #expect(relaunched.committed?.name == "저장한 방")
        #expect(relaunched.committed?.revision.rawValue == 1)
    }

    @Test
    func failedSavePreservesCommittedRoomAndDraft() throws {
        let fixture = try fixture()
        let initialRoom = try room(name: "커밋 방", revision: 0)
        fixture.seed(room: initialRoom)
        let repository = LocalMiniHomeRepository(
            accountID: fixture.accountID,
            defaults: fixture.defaults,
            now: fixture.now,
            shouldFailSave: { true }
        )
        let store = MiniHomeStore(repository: repository)
        store.mount()

        store.renameDraft("실패한 초안")
        try store.save()

        #expect(store.committed?.name == "커밋 방")
        #expect(store.draft?.name == "실패한 초안")
        #expect(store.state == .failed)
    }

    @Test
    func conflictCancelThenSaveReappliesDraftAtServerRevision() throws {
        let fixture = try fixture()
        let initialRoom = try room(name: "기준 방", revision: 0)
        fixture.seed(room: initialRoom)
        let first = MiniHomeStore(repository: fixture.repository())
        let second = MiniHomeStore(repository: fixture.repository())
        first.mount()
        second.mount()
        first.renameDraft("첫 기기 방")
        try first.save()
        second.renameDraft("둘째 기기 초안")

        try second.save()
        #expect(second.committed?.name == "첫 기기 방")
        #expect(second.draft?.name == "둘째 기기 초안")
        #expect(second.state == .conflicted(serverRevision: 1))
        try second.resolveConflict(.cancel)
        #expect(second.state == .conflicted(serverRevision: 1))

        try second.resolveConflict(.save)
        #expect(second.committed?.name == "둘째 기기 초안")
        #expect(second.committed?.revision.rawValue == 2)
        #expect(second.state == .saved)
    }

    @Test
    func conflictDiscardRestoresCommittedRoom() throws {
        let fixture = try fixture()
        let initialRoom = try room(name: "기준 방", revision: 0)
        fixture.seed(room: initialRoom)
        let first = MiniHomeStore(repository: fixture.repository())
        let second = MiniHomeStore(repository: fixture.repository())
        first.mount()
        second.mount()
        first.renameDraft("서버 방")
        try first.save()
        second.renameDraft("버릴 초안")
        try second.save()

        try second.resolveConflict(.discard)

        #expect(second.committed?.name == "서버 방")
        #expect(second.draft == second.committed)
        #expect(second.state == .idle)
    }

    /// A brand-new account has no committed room, so Reset must fall back to
    /// the room the editor was mounted with rather than keeping the edits.
    @Test
    func resetRestoresMountDefaultWhenNoCommittedRoomExists() throws {
        let fixture = try fixture()
        let store = MiniHomeStore(repository: fixture.repository())
        let mountDefault = try room(name: "새 미니홈", revision: 0)
        store.mount(defaultDraft: mountDefault)
        #expect(store.committed == nil)

        store.renameDraft("편집한 방")
        try store.addDraftPlacement(placement(zIndex: 0))
        #expect(store.draft != mountDefault)

        store.resetDraft()

        #expect(store.draft == mountDefault)
        #expect(store.committed == nil)
        #expect(store.canUndoDraft == false)
        #expect(store.state == .idle)
    }

    /// Once a room is committed, Reset must return to that committed room and
    /// never to the original mount default.
    @Test
    func resetRestoresCommittedRoomAfterSave() throws {
        let fixture = try fixture()
        let store = MiniHomeStore(repository: fixture.repository())
        try store.mount(defaultDraft: room(name: "새 미니홈", revision: 0))
        store.renameDraft("저장한 방")
        try store.save()

        store.renameDraft("저장 후 편집")
        store.resetDraft()

        #expect(store.draft?.name == "저장한 방")
        #expect(store.draft == store.committed)
        #expect(store.hasUnsavedChanges == false)
    }

    private func fixture() throws -> MiniHomeStoreFixture {
        try MiniHomeStoreFixture()
    }

    private func placement(zIndex: Int) throws -> MiniHomePlacement {
        try MiniHomePlacement(
            id: MiniHomeGeometry.nextPlacementID(existing: []),
            plantID: PersonalPlantID.parse("test-plant"),
            itemID: nil,
            normalizedX: 0.5,
            normalizedY: 0.55,
            zIndex: zIndex
        )
    }

    private func room(name: String, revision: UInt64) throws -> MiniHome {
        let id = try MiniHomeID.parse("test-room")
        let parsedRevision = try Revision.parse(revision)
        let updatedAt = try Instant.parse("2026-08-11T00:00:00Z")
        return MiniHome(
            id: id,
            name: name,
            placements: [],
            revision: parsedRevision,
            updatedAt: updatedAt
        )
    }
}

@MainActor
private struct MiniHomeStoreFixture {
    let accountID = "mini-home-\(UUID())"
    let defaults: UserDefaults
    let now: Instant

    init() throws {
        let suiteName = "MiniHomeStoreTests-\(UUID())"
        defaults = try #require(UserDefaults(suiteName: suiteName))
        defaults.removePersistentDomain(forName: suiteName)
        now = try Instant.parse("2026-08-11T01:00:00Z")
    }

    func repository() -> LocalMiniHomeRepository {
        LocalMiniHomeRepository(
            accountID: accountID,
            defaults: defaults,
            now: now
        )
    }

    func seed(room: MiniHome) {
        defaults.set(
            try? JSONEncoder().encode(room),
            forKey: "home.\(accountID).committed-mini-home"
        )
    }
}
