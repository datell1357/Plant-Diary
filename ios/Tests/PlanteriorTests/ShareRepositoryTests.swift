import Foundation
@testable import Planterior
import PlanteriorData
import PlanteriorDomain
import Testing

@MainActor
struct ShareRepositoryTests {
    @Test
    func provisionalLinksAreUniqueImmutableAndRevocable() throws {
        var counter: UInt8 = 1
        let createdAt = try Instant.parse("2026-08-11T00:00:00Z")
        let repository = ShareRepository(
            allowsProvisionalLinks: true,
            now: createdAt,
            randomBytes: {
                defer { counter += 1 }
                return Data(repeating: counter, count: 24)
            }
        )
        let originalRoom = try shareRoom(name: "저장된 방", revision: 2)
        let changedRoom = try shareRoom(name: "나중 수정", revision: 3)
        let original = ShareSnapshotPolicy.snapshot(committed: originalRoom)
        let changed = ShareSnapshotPolicy.snapshot(committed: changedRoom)
        let first = try created(
            repository.createLink(
                snapshot: original,
                digest: "digest-a",
                online: true
            )
        )
        let second = try created(
            repository.createLink(
                snapshot: changed,
                digest: "digest-b",
                online: true
            )
        )

        #expect(first.token != second.token)
        #expect(first.snapshot.roomName == "저장된 방")
        #expect(first.snapshot.sourceRevision.rawValue == 2)
        #expect(first.expiresAt.rawValue == "2026-09-10T00:00:00Z")
        guard case let .revoked(revoked) = repository.revoke(first.id) else {
            Issue.record("Expected revoke result")
            return
        }
        #expect(revoked.revokedAt != nil)
        let resolvedAt = try Instant.parse("2026-08-12T00:00:00Z")
        #expect(
            repository.resolve(
                first.token,
                now: resolvedAt
            ) == nil
        )
        #expect(repository.revoke(first.id) == .alreadyRevoked(revoked))
    }

    @Test
    func offlineAndProductionLinkCreationFailClosed() throws {
        let room = try shareRoom(name: "저장된 방", revision: 1)
        let snapshot = ShareSnapshotPolicy.snapshot(committed: room)
        let createdAt = try Instant.parse("2026-08-11T00:00:00Z")
        let local = ShareRepository(
            allowsProvisionalLinks: true,
            now: createdAt,
            randomBytes: { Data(repeating: 1, count: 24) }
        )
        let production = ShareRepository(
            allowsProvisionalLinks: false,
            now: createdAt
        )

        #expect(
            local.createLink(
                snapshot: snapshot,
                digest: "digest",
                online: false
            ) == .offline
        )
        #expect(
            production.createLink(
                snapshot: snapshot,
                digest: "digest",
                online: true
            ) == .unavailable
        )
    }

    private func created(
        _ outcome: ShareRepositoryOutcome
    ) throws -> ProvisionalShareLink {
        guard case let .created(link) = outcome else {
            throw ShareSnapshotError.invalidToken
        }
        return link
    }
}
