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
        isRenameFieldFocused = false
        isRenamePresented = true
    }

    /// Focus restores to the room title so VoiceOver never lands on nothing.
    func dismissRename() {
        isRenameFieldFocused = false
        isRenamePresented = false
        renameDraft = ""
    }

    /// The only path that charges. The allowance commits only after the
    /// authoritative room save succeeds.
    func commitRename() {
        let name = renameDraft.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !name.isEmpty, renameQuote.isAffordable else { return }
        Task {
            var allowance = renameAllowance
            guard allowance.commit() else { return }
            miniHomeStore.renameDraft(name)
            await miniHomeStore.save()
            guard miniHomeStore.state == .saved else { return }
            allowanceStore.save(allowance)
            renameAllowance = allowance
            reload()
            dismissRename()
        }
    }

    /// §6.2 notification button target.
    func openCareSettings() {
        guard authorizeAccountAction() else {
            return
        }
        showsQuietHoursSettings = true
    }
}
