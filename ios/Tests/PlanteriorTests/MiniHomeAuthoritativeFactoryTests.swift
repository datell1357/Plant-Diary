import Foundation
@testable import Planterior
import PlanteriorDomain
import Testing

@MainActor
struct MiniHomeAuthoritativeFactoryTests {
    @Test
    func releaseBoundaryUsesFirebaseOrFailsClosed_whenConfigurationChanges() throws {
        // Given
        let defaults = try makeDefaults()
        let recorder = MiniHomeCallableRecorder()

        // When
        let configured = MiniHomeAuthoritativeFactory.makeRelease(
            firebaseConfigured: true,
            defaults: defaults,
            client: recorder
        )
        let unavailable = MiniHomeAuthoritativeFactory.makeRelease(
            firebaseConfigured: false,
            defaults: defaults,
            client: recorder
        )

        // Then
        #expect(configured.service is FirebaseMiniHomeAuthoritativeService)
        #expect(unavailable.service is UnavailableMiniHomeAuthoritativeService)
    }

    #if DEBUG
        @Test
        func debugBoundaryAcceptsTypedInMemoryService_whenInjected() async throws {
            // Given
            let defaults = try makeDefaults()
            let snapshot = try MiniHomeAuthoritativeServiceTests.snapshot()
            let fake = InMemoryMiniHomeAuthoritativeService(
                snapshots: [snapshot.accountID: snapshot]
            )
            let boundary = MiniHomeAuthoritativeFactory.makeDebug(
                service: fake,
                defaults: defaults
            )

            // When
            let loaded = try await boundary.service.load(accountID: snapshot.accountID)
            let saved = try await boundary.service.save(MiniHomeAuthoritativeSaveRequest(
                accountID: snapshot.accountID,
                draft: snapshot.home,
                expectedRevision: snapshot.home.revision,
                operationID: OperationID.parse("operation-fixture-0001")
            ))

            // Then
            #expect(loaded == .snapshot(snapshot))
            guard case let .committed(committed) = saved else {
                Issue.record("Expected committed fake save")
                return
            }
            #expect(committed.home.revision.rawValue == snapshot.home.revision.rawValue + 1)
            #expect(boundary.service is InMemoryMiniHomeAuthoritativeService)
        }

        @Test
        func debugFakeReplaysSameOperationAndRejectsChangedPayload() async throws {
            // Given
            let snapshot = try MiniHomeAuthoritativeServiceTests.snapshot()
            let fake = InMemoryMiniHomeAuthoritativeService(
                snapshots: [snapshot.accountID: snapshot]
            )
            let operationID = try OperationID.parse("operation-replay")
            let request = MiniHomeAuthoritativeSaveRequest(
                accountID: snapshot.accountID,
                draft: snapshot.home,
                expectedRevision: snapshot.home.revision,
                operationID: operationID
            )

            // When
            let committed = try await fake.save(request)
            let duplicate = try await fake.save(request)

            // Then
            guard case let .committed(first) = committed,
                  case let .duplicate(replayed) = duplicate
            else {
                Issue.record("Expected committed then duplicate")
                return
            }
            #expect(replayed == first)
            let changed = MiniHome(
                id: snapshot.home.id,
                name: "변경된 요청",
                placements: snapshot.home.placements,
                revision: snapshot.home.revision,
                updatedAt: snapshot.home.updatedAt
            )
            await #expect(throws: MiniHomeAuthoritativeError.failedPrecondition) {
                try await fake.save(MiniHomeAuthoritativeSaveRequest(
                    accountID: snapshot.accountID,
                    draft: changed,
                    expectedRevision: snapshot.home.revision,
                    operationID: operationID
                ))
            }
        }

        @Test
        func currentDebugBoundaryNeverUsesLocalRepositoryAuthority() {
            // Given / When
            let boundary = MiniHomeAuthoritativeFactory.current()

            // Then
            #expect(boundary.service is InMemoryMiniHomeAuthoritativeService)
            #expect(!(boundary.service is UnavailableMiniHomeAuthoritativeService))
        }
    #endif

    private func makeDefaults() throws -> UserDefaults {
        let suite = "MiniHomeAuthoritativeFactoryTests.\(UUID().uuidString)"
        return try #require(UserDefaults(suiteName: suite))
    }
}
