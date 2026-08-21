import Foundation
@testable import Planterior
import PlanteriorData
import PlanteriorDomain
import Testing

@MainActor
struct AccountDeletionRecoveryTests {
    @Test
    func relaunchWithoutAuthRecoversCompletionAndCleansOnlyPendingOwner() async throws {
        let defaults = try #require(UserDefaults(
            suiteName: "AccountDeletionRecoveryTests-\(UUID().uuidString)"
        ))
        let pendingStore = PendingAccountDeletionStore(defaults: defaults)
        let service = RecoveryDeletionService(now: 1000)
        let ownerA = try AccountID.parse("account-a")
        let ownerB = try AccountID.parse("account-b")
        let recorder = CleanupOwnerRecorder()
        let requester = coordinator(
            ownerID: ownerA,
            service: service,
            pendingStore: pendingStore,
            recorder: recorder
        )

        await requester.preview()
        requester.acceptReauthentication()
        await requester.request()

        let pending = try #require(pendingStore.load())
        #expect(pending.ownerID == ownerA)
        #expect(!AccountDeletionRecoveryRuntime.shouldRefresh(
            pending: pending,
            isSignedIn: true,
            accountID: ownerB
        ))
        let accountB = coordinator(
            ownerID: ownerB,
            service: service,
            pendingStore: pendingStore,
            recorder: recorder
        )
        await accountB.preview()
        #expect(accountB.cleanupCount == 0)
        #expect(pendingStore.load()?.ownerID == ownerA)

        await service.complete(ownerID: ownerA)
        let relaunched = coordinator(
            ownerID: nil,
            service: service,
            pendingStore: pendingStore,
            recorder: recorder
        )
        await relaunched.preview()

        #expect(relaunched.workflow?.status == .completed)
        #expect(relaunched.cleanupCount == 1)
        #expect(await recorder.ownerIDs == [ownerA])
        #expect(pendingStore.load() == nil)
    }

    @Test
    func twoPendingAccountsRecoverAndClearIndependently() async throws {
        // Given
        let defaults = try #require(UserDefaults(
            suiteName: "AccountDeletionRecoveryTests-\(UUID().uuidString)"
        ))
        let pendingStore = PendingAccountDeletionStore(defaults: defaults)
        let service = RecoveryDeletionService(now: 1000)
        let ownerA = try AccountID.parse("account-a")
        let ownerB = try AccountID.parse("account-b")
        let recorder = CleanupOwnerRecorder()
        let requesterA = coordinator(
            ownerID: ownerA,
            service: service,
            pendingStore: pendingStore,
            recorder: recorder
        )
        let requesterB = coordinator(
            ownerID: ownerB,
            service: service,
            pendingStore: pendingStore,
            recorder: recorder
        )

        // When
        await requesterA.preview()
        requesterA.acceptReauthentication()
        await requesterA.request()
        await requesterB.preview()
        requesterB.acceptReauthentication()
        await requesterB.request()
        await service.complete(ownerID: ownerA)
        let recoveryA = coordinator(
            ownerID: nil,
            service: service,
            pendingStore: pendingStore,
            recorder: recorder
        )
        await recoveryA.preview()
        let remainingAfterA = pendingStore.loadAll()
        await service.complete(ownerID: ownerB)
        let recoveryB = coordinator(
            ownerID: nil,
            service: service,
            pendingStore: pendingStore,
            recorder: recorder
        )
        await recoveryB.preview()

        // Then
        #expect(recoveryA.cleanupCount == 1)
        #expect(remainingAfterA.map(\.ownerID) == [ownerB])
        #expect(recoveryB.cleanupCount == 1)
        #expect(await recorder.ownerIDs == [ownerA, ownerB])
        #expect(pendingStore.loadAll().isEmpty)
    }

    @Test
    func legacySinglePendingRecordMigratesWithoutDataLoss() throws {
        // Given
        let defaults = try #require(UserDefaults(
            suiteName: "AccountDeletionRecoveryTests-\(UUID().uuidString)"
        ))
        defaults.set(
            ["ownerID": "account-legacy", "requestID": "request-legacy"],
            forKey: "account-deletion.pending-recovery"
        )
        let pendingStore = PendingAccountDeletionStore(defaults: defaults)

        // When
        let pending = pendingStore.loadAll()

        // Then
        #expect(pending.map(\.ownerID.rawValue) == ["account-legacy"])
        #expect(pending.map(\.requestID.rawValue) == ["request-legacy"])
        #expect(defaults.dictionary(forKey: "account-deletion.pending-recovery") == nil)
    }

    private func coordinator(
        ownerID: AccountID?,
        service: RecoveryDeletionService,
        pendingStore: PendingAccountDeletionStore,
        recorder: CleanupOwnerRecorder
    ) -> AccountDeletionCoordinator {
        AccountDeletionCoordinator(
            allowsTrustedFake: false,
            ownerID: ownerID,
            now: 1000,
            service: service,
            pendingStore: pendingStore,
            onCompleted: { ownerID in
                await recorder.record(ownerID)
                return Array(AccountDeletionCoordinator.requiredCleanupReceipts)
            }
        )
    }
}
