import Foundation
import PlanteriorDomain
import SwiftUI

extension HomeDashboardView {
    var renameQuote: HomeRenameAllowance.Quote {
        renameAllowance.quote
    }

    var allowanceStore: HomeRenameAllowanceStore {
        HomeRenameAllowanceStore(accountID: accountScopeID)
    }

    var effectiveReduceMotion: Bool {
        reduceMotion
            || ProcessInfo.processInfo.environment["QA_REDUCE_MOTION"] == "1"
    }

    /// Clears rename state when the QA reset token rotates, so one UI test can
    /// never inherit another's renamed room or spent allowance.
    func resetRenameStateForQAIfNeeded() {
        #if DEBUG
            guard let token = ProcessInfo.processInfo.environment[
                "QA_MINIHOME_RESET_TOKEN"
            ] else {
                return
            }
            let key = "qa.home.rename.reset-token"
            guard UserDefaults.standard.string(forKey: key) != token else {
                return
            }
            UserDefaults.standard.set(token, forKey: key)
            allowanceStore.resetForQA()
            renamedRoomTitle = nil
        #endif
    }

    /// §6.2 title tap. Signed-out users are routed to login instead of renaming.
    func requestRename() {
        guard authenticationState == .authenticated else {
            openCamera()
            return
        }
        renameDraft = ""
        renameAllowance = allowanceStore.load()
        renamedRoomTitle = allowanceStore.renamedTitle
        isRenamePresented = true
        isRenameFieldFocused = true
    }

    /// Focus restores to the room title so VoiceOver never lands on nothing.
    func dismissRename() {
        isRenameFieldFocused = false
        isRenamePresented = false
        renameDraft = ""
    }

    /// The only path that charges. An unaffordable or empty rename is a no-op.
    func commitRename() {
        let name = renameDraft.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !name.isEmpty, renameQuote.isAffordable else {
            return
        }
        var allowance = renameAllowance
        guard allowance.commit() else {
            return
        }
        guard persistRoomName(name) else {
            return
        }
        allowanceStore.save(allowance)
        allowanceStore.saveRenamedTitle(name)
        renameAllowance = allowance
        renamedRoomTitle = name
        reload()
        dismissRename()
    }

    /// Commits the new room title through `LocalMiniHomeRepository` so it
    /// survives relaunch under the account scope.
    private func persistRoomName(_ name: String) -> Bool {
        guard let now = renameInstant else {
            return false
        }
        let repository = LocalMiniHomeRepository(
            accountID: accountScopeID,
            now: now
        )
        guard let current = repository.load() else {
            return false
        }
        let draft = MiniHome(
            id: current.id,
            name: name,
            placements: current.placements,
            revision: current.revision,
            updatedAt: current.updatedAt
        )
        guard let outcome = try? repository.save(
            draft: draft,
            expectedRevision: current.revision
        ) else {
            return false
        }
        if case .committed = outcome {
            return true
        }
        return false
    }

    private var renameInstant: Instant? {
        #if DEBUG
            if let value = ProcessInfo.processInfo.environment[
                "QA_MINIHOME_NOW"
            ] {
                return try? Instant.parse(value)
            }
        #endif
        return try? Instant.parse(
            ISO8601DateFormatter().string(from: Date())
        )
    }

    /// §6.2 notification button target.
    func openCareSettings() {
        showsRegionSettings = true
    }
}
