import Foundation
import PlanteriorData
import PlanteriorDomain
import UserNotifications

enum AccountDeletionLocalCleanup {
    @MainActor
    static func perform(ownerID: AccountID, auth: AuthRuntime) async -> [String] {
        var receipts: [String] = []
        if await auth.sync.destroyLocalStore(for: ownerID) {
            receipts.append(contentsOf: ["swiftdata", "sync"])
        }
        do {
            try await IdentificationDraftStore.shared.clear(accountID: ownerID.rawValue)
            receipts.append("media")
        } catch {
            // The missing receipt keeps cleanup failed and retryable.
        }

        let notificationCenter = UNUserNotificationCenter.current()
        notificationCenter.removeAllPendingNotificationRequests()
        notificationCenter.removeAllDeliveredNotifications()
        receipts.append("notifications")

        if SettingsView.clearAccountDefaults(ownerID: ownerID) {
            receipts.append("userdefaults")
        }

        let session = await auth.completeDeletionSignOut()
        if session.firebaseSignedOut {
            receipts.append("auth")
        }
        if session.metadataCleared {
            receipts.append("keychain")
        }
        if !auth.isSignedIn, auth.accountID == nil {
            receipts.append("routes")
        }
        return receipts
    }
}

@MainActor
enum AccountDeletionRecoveryRuntime {
    static func refresh(auth: AuthRuntime) async {
        guard !auth.isRestoring,
              let pending = PendingAccountDeletionStore.shared.load(),
              shouldRefresh(
                  pending: pending,
                  isSignedIn: auth.isSignedIn,
                  accountID: auth.accountID
              )
        else {
            return
        }
        let coordinator = AccountDeletionCoordinator(
            allowsTrustedFake: false,
            ownerID: auth.accountID,
            now: Int64(Date().timeIntervalSince1970),
            onCompleted: { ownerID in
                await AccountDeletionLocalCleanup.perform(ownerID: ownerID, auth: auth)
            }
        )
        await coordinator.preview()
    }

    static func shouldRefresh(
        pending: PendingAccountDeletion,
        isSignedIn: Bool,
        accountID: AccountID?
    ) -> Bool {
        !isSignedIn || accountID == pending.ownerID
    }
}
