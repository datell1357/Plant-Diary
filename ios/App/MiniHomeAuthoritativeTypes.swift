import Foundation
import PlanteriorDomain

struct MiniHomeSnapshotWire: Sendable {
    let accountID: String
    let home: MiniHome
    let updatedAtEpochMillis: UInt64
    let snapshotHash: String
}

struct MiniHomeVerifiedSnapshot: Equatable, Sendable {
    let accountID: String
    let home: MiniHome
    let updatedAtEpochMillis: UInt64
    let snapshotHash: String

    init(
        wire: MiniHomeSnapshotWire,
        verification: MiniHomeServerVerification
    ) {
        accountID = wire.accountID
        home = wire.home
        updatedAtEpochMillis = wire.updatedAtEpochMillis
        snapshotHash = wire.snapshotHash
    }

    static func verified(
        accountID: String,
        home: MiniHome,
        updatedAtEpochMillis: UInt64
    ) -> MiniHomeVerifiedSnapshot {
        let canonicalHome = MiniHome(
            id: home.id,
            name: home.name,
            placements: MiniHomeCanonicalEncoding.sortedPlacements(home.placements),
            revision: home.revision,
            updatedAt: home.updatedAt
        )
        let provisional = MiniHomeVerifiedSnapshot(
            wire: MiniHomeSnapshotWire(
                accountID: accountID,
                home: canonicalHome,
                updatedAtEpochMillis: updatedAtEpochMillis,
                snapshotHash: String(repeating: "0", count: 64)
            ),
            verification: MiniHomeServerVerification()
        )
        return MiniHomeVerifiedSnapshot(
            wire: MiniHomeSnapshotWire(
                accountID: accountID,
                home: canonicalHome,
                updatedAtEpochMillis: updatedAtEpochMillis,
                snapshotHash: MiniHomeCanonicalEncoding.snapshotHash(provisional)
            ),
            verification: MiniHomeServerVerification()
        )
    }
}

enum MiniHomeAuthoritativeLoadResult: Equatable, Sendable {
    case empty(accountID: String)
    case snapshot(MiniHomeVerifiedSnapshot)
}

enum MiniHomeAuthoritativeSaveResult: Equatable, Sendable {
    case committed(MiniHomeVerifiedSnapshot)
    case duplicate(MiniHomeVerifiedSnapshot)
    case conflict(MiniHomeVerifiedSnapshot?)
}

struct MiniHomeAuthoritativeSaveRequest: Sendable {
    let accountID: String
    let draft: MiniHome
    let expectedRevision: Revision
    let operationID: OperationID
}

enum MiniHomeAuthoritativeError: Error, Equatable, Sendable {
    case transport
    case unauthenticated
    case forbidden
    case invalidRequest
    case failedPrecondition
    case malformedResponse
    case dataLoss
}

@MainActor
protocol MiniHomeAuthoritativeService {
    func load(accountID: String) async throws -> MiniHomeAuthoritativeLoadResult
    func save(
        _ request: MiniHomeAuthoritativeSaveRequest
    ) async throws -> MiniHomeAuthoritativeSaveResult
}

@MainActor
protocol MiniHomeCallableClient {
    func call(
        name: String,
        payload: sending [String: Any]
    ) async throws -> Data
}

@MainActor
struct UnavailableMiniHomeAuthoritativeService: MiniHomeAuthoritativeService {
    func load(accountID: String) async throws -> MiniHomeAuthoritativeLoadResult {
        throw MiniHomeAuthoritativeError.transport
    }

    func save(
        _ request: MiniHomeAuthoritativeSaveRequest
    ) async throws -> MiniHomeAuthoritativeSaveResult {
        throw MiniHomeAuthoritativeError.transport
    }
}
