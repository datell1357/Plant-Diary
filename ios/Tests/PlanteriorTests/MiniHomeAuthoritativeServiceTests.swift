import Foundation
@testable import Planterior
import PlanteriorDomain
import Testing

@MainActor
struct MiniHomeAuthoritativeServiceTests {
    @Test
    func exactCallableRequestsAreSent_whenLoadingAndSaving() async throws {
        // Given
        let recorder = try MiniHomeCallableRecorder(responses: [
            "loadMiniHome": Self.fixtureData("loadResponse"),
            "saveMiniHome": Self.fixtureData("saveResponse")
        ])
        let service = FirebaseMiniHomeAuthoritativeService(client: recorder)
        let draft = try Self.draft()

        // When
        _ = try await service.load(accountID: Self.accountID)
        _ = try await service.save(MiniHomeAuthoritativeSaveRequest(
            accountID: Self.accountID,
            draft: draft,
            expectedRevision: Revision.parse(6),
            operationID: OperationID.parse("operation-fixture-0001")
        ))

        // Then
        #expect(recorder.calls.map(\.name) == ["loadMiniHome", "saveMiniHome"])
        #expect(Set(recorder.calls[0].payload.keys) == ["expectedOwnerUid"])
        #expect(recorder.calls[0].payload["expectedOwnerUid"] as? String == Self.accountID)
        let save = recorder.calls[1].payload
        #expect(Set(save.keys) == [
            "expectedOwnerUid", "expectedRevision", "operationId",
            "roomId", "name", "placements"
        ])
        #expect(save["expectedOwnerUid"] as? String == Self.accountID)
        #expect(save["expectedRevision"] as? UInt64 == 6)
        #expect(save["operationId"] as? String == "operation-fixture-0001")
        #expect(save["roomId"] as? String == "room-main")
        #expect(save["name"] as? String == "나의 미니홈")
        let placements = try #require(save["placements"] as? [[String: Any]])
        #expect(placements.compactMap { $0["placementId"] as? String } == [
            "placement-lamp", "placement-plant"
        ])
        #expect(Set(placements[0].keys) == [
            "placementId", "itemId", "normalizedX", "normalizedY", "zIndex"
        ])
        #expect(Set(placements[1].keys) == [
            "placementId", "plantId", "normalizedX", "normalizedY", "zIndex"
        ])
    }

    @Test
    func typedResultsArePreserved_whenCallableReturnsEveryVariant() async throws {
        // Given
        let root = try Self.fixtureRoot()
        let snapshot = try #require(root["snapshot"] as? [String: Any])
        let recorder = MiniHomeCallableRecorder()
        let service = FirebaseMiniHomeAuthoritativeService(client: recorder)
        let draft = try Self.draft()

        // When / Then
        for kind in ["committed", "duplicate", "conflict"] {
            recorder.responses["saveMiniHome"] = try JSONSerialization.data(
                withJSONObject: ["kind": kind, "snapshot": snapshot]
            )
            let result = try await service.save(MiniHomeAuthoritativeSaveRequest(
                accountID: Self.accountID,
                draft: draft,
                expectedRevision: Revision.parse(6),
                operationID: OperationID.parse("operation-fixture-0001")
            ))
            switch result {
            case .committed: #expect(kind == "committed")
            case .duplicate: #expect(kind == "duplicate")
            case .conflict: #expect(kind == "conflict")
            }
        }
    }

    @Test
    func invalidDraftNeverReachesCallable_whenSaveBoundaryRejectsIt() async throws {
        // Given
        let recorder = MiniHomeCallableRecorder()
        let service = FirebaseMiniHomeAuthoritativeService(client: recorder)
        let valid = try Self.draft()
        let invalid = MiniHome(
            id: valid.id,
            name: " surrounded ",
            placements: valid.placements,
            revision: valid.revision,
            updatedAt: valid.updatedAt
        )
        let request = try MiniHomeAuthoritativeSaveRequest(
            accountID: Self.accountID,
            draft: invalid,
            expectedRevision: Revision.parse(6),
            operationID: OperationID.parse("operation-fixture-0001")
        )

        // When / Then
        await #expect(throws: MiniHomeAuthoritativeError.invalidRequest) {
            try await service.save(request)
        }
        #expect(recorder.calls.isEmpty)
    }

    @Test
    func transportAndMalformedResponseStayDistinct_whenCallableFails() async throws {
        // Given
        let recorder = MiniHomeCallableRecorder(responses: [
            "loadMiniHome": Data(#"{"kind":"unknown"}"#.utf8)
        ])
        let service = FirebaseMiniHomeAuthoritativeService(client: recorder)

        // When / Then
        await #expect(throws: MiniHomeAuthoritativeError.malformedResponse) {
            try await service.load(accountID: Self.accountID)
        }
        recorder.failure = .transport
        await #expect(throws: MiniHomeAuthoritativeError.transport) {
            try await service.load(accountID: Self.accountID)
        }
    }
}
