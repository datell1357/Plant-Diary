import Foundation
@testable import Planterior
import PlanteriorDomain
import Testing

@MainActor
struct OperationalPrivacyTests {
    @Test
    func analyticsExportsOnlyAllowlistedFields() {
        let recorder = AnalyticsRecorder()
        recorder.record(.screenViewed(.settings))
        recorder.record(.action(.imageShared, .cancelled))

        #expect(recorder.exportedEvents.count == 2)
        #expect(
            recorder.exportedEvents
                .flatMap(\.keys)
                .allSatisfy(AnalyticsRecorder.allowedKeys.contains)
        )
        let export = String(describing: recorder.exportedEvents)
        for forbidden in [
            "raw_image", "exact_coordinate", "note", "auth_data",
            "push_token", "share_token", "private_url", "payload"
        ] {
            #expect(!export.contains(forbidden))
        }
    }

    @Test
    func redactorRemovesPrivateValues() {
        let source = """
        user@example.com https://private.example/path \
        abcdefghijklmnopqrstuvwxyz123456 37.56650, 126.97800
        """
        let redacted = SensitiveDataRedactor.redact(source)

        #expect(!redacted.contains("user@example.com"))
        #expect(!redacted.contains("private.example"))
        #expect(!redacted.contains("37.56650"))
        #expect(redacted.contains("[REDACTED]"))
    }

    @Test
    func retentionBoundaryPreservesRepresentativeAndRetriesFailures() {
        let now = Date(timeIntervalSince1970: 100_000)
        let at2359 = RetainedPhoto(
            id: "at-2359",
            createdAt: now.addingTimeInterval(-(23 * 60 * 60 + 59 * 60)),
            isRepresentative: false
        )
        let at2400 = RetainedPhoto(
            id: "at-2400",
            createdAt: now.addingTimeInterval(-24 * 60 * 60),
            isRepresentative: false
        )
        let representative = RetainedPhoto(
            id: "representative",
            createdAt: now.addingTimeInterval(-48 * 60 * 60),
            isRepresentative: true
        )
        let retry = PhotoRetentionCoordinator.cleanup(
            [at2359, at2400, representative],
            now: now
        ) { _ in
            throw CocoaError(.fileWriteUnknown)
        }

        #expect(retry == ["at-2400"])
    }

    @Test
    func productionDeletionUsesAuthenticatedOwnerAndCreatesRequest() async throws {
        let ownerID = try AccountID.parse("production-delete-owner")
        let defaults = try #require(UserDefaults(
            suiteName: "OperationalPrivacyTests.deletion.\(UUID().uuidString)"
        ))
        let coordinator = AccountDeletionCoordinator(
            allowsTrustedFake: false,
            ownerID: ownerID,
            now: 1000,
            service: QAAccountDeletionService(now: 1000),
            pendingStore: PendingAccountDeletionStore(defaults: defaults)
        )

        await coordinator.preview()
        await coordinator.request()
        #expect(coordinator.requestCount == 0)

        coordinator.acceptReauthentication()
        await coordinator.request()

        #expect(coordinator.scope?.ownerID == ownerID)
        #expect(coordinator.reauthenticated)
        #expect(coordinator.requestCount == 1)
    }

    @Test
    func completedDeletionRejectsPartialCleanupReceipt() async throws {
        let ownerID = try AccountID.parse("partial-cleanup-owner")
        let partialReceipts = AccountDeletionCoordinator
            .requiredCleanupReceipts
            .subtracting(["keychain"])
        let coordinator = AccountDeletionCoordinator(
            allowsTrustedFake: true,
            ownerID: ownerID,
            now: 1000,
            service: QAAccountDeletionService(now: 1000),
            onCompleted: { _ in Array(partialReceipts) }
        )

        await coordinator.preview()
        coordinator.reauthenticate()
        await coordinator.request()
        await coordinator.simulateCompletion()

        #expect(coordinator.workflow?.status == .completed)
        #expect(coordinator.cleanupCount == 0)
        #expect(coordinator.message == "삭제 완료 · 로컬 정리 실패")
    }

    @Test
    func deletionClearsOnlyTheCompletedAccountsDefaults() throws {
        let suiteName = "OperationalPrivacyTests.defaults"
        let defaults = try #require(UserDefaults(suiteName: suiteName))
        defaults.removePersistentDomain(forName: suiteName)
        defer { defaults.removePersistentDomain(forName: suiteName) }
        let ownerID = try AccountID.parse("account-a")
        defaults.set(true, forKey: "weather.account-a.alerts")
        defaults.set(true, forKey: "weather.account-b.alerts")
        defaults.set(true, forKey: "global.appearance")

        #expect(
            SettingsView.clearAccountDefaults(
                ownerID: ownerID,
                defaults: defaults
            )
        )
        #expect(defaults.object(forKey: "weather.account-a.alerts") == nil)
        #expect(defaults.bool(forKey: "weather.account-b.alerts"))
        #expect(defaults.bool(forKey: "global.appearance"))
    }

    @Test
    func appCheckRejectsMissingAndShortTokens() {
        #expect(!AppCheckPolicy.accepts(token: nil))
        #expect(!AppCheckPolicy.accepts(token: "short"))
        #expect(
            AppCheckPolicy.accepts(
                token: String(repeating: "a", count: 32)
            )
        )
    }
}
