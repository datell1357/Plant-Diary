import Foundation

/// Account-scoped allowance for the Figma `home-screen-rename-*` dialog
/// (figma-analysis §6.9). The first rename on an account is free; every later
/// rename quotes a fixed five-credit cost. This is deliberately local and
/// isolated — it is not wired to milestones or any other progression.
struct HomeRenameAllowance: Equatable, Sendable {
    static let paidCost = 5

    var hasUsedFreeRename: Bool
    var balance: Int

    enum Quote: Equatable, Sendable {
        case free
        case paid(cost: Int, balance: Int)
        case insufficient(cost: Int, balance: Int)

        var isAffordable: Bool {
            if case .insufficient = self {
                return false
            }
            return true
        }
    }

    var quote: Quote {
        guard hasUsedFreeRename else {
            return .free
        }
        guard balance >= Self.paidCost else {
            return .insufficient(cost: Self.paidCost, balance: balance)
        }
        return .paid(cost: Self.paidCost, balance: balance)
    }

    /// Applies the quote. Charging only ever happens here, so dismissing the
    /// dialog can never move the balance.
    mutating func commit() -> Bool {
        switch quote {
        case .free:
            hasUsedFreeRename = true
            return true
        case .paid:
            balance -= Self.paidCost
            return true
        case .insufficient:
            return false
        }
    }
}

/// Persists the allowance per account scope.
struct HomeRenameAllowanceStore {
    private let defaults: UserDefaults
    private let usedKey: String
    private let balanceKey: String
    private let seededKey: String
    private let qaAppliedKey: String

    init(accountID: String?, defaults: UserDefaults = .standard) {
        self.defaults = defaults
        let scope = accountID ?? "signed-out"
        usedKey = "home.\(scope).rename.used-free"
        balanceKey = "home.\(scope).rename.balance"
        seededKey = "home.\(scope).rename.seeded"
        qaAppliedKey = "home.\(scope).rename.qa-applied"
    }

    func load() -> HomeRenameAllowance {
        seedIfNeeded()
        return HomeRenameAllowance(
            hasUsedFreeRename: defaults.bool(forKey: usedKey),
            balance: defaults.integer(forKey: balanceKey)
        )
    }

    func save(_ allowance: HomeRenameAllowance) {
        defaults.set(allowance.hasUsedFreeRename, forKey: usedKey)
        defaults.set(allowance.balance, forKey: balanceKey)
    }

    /// New accounts start with one free rename and a small starter balance so
    /// the paid quote is reachable without any external currency domain.
    private func seedIfNeeded() {
        #if DEBUG
            if let mode = ProcessInfo.processInfo.environment[
                "QA_HOME_RENAME_MODE"
            ] {
                applyQAMode(mode)
                return
            }
        #endif
        guard !defaults.bool(forKey: seededKey) else {
            return
        }
        defaults.set(true, forKey: seededKey)
        defaults.set(false, forKey: usedKey)
        defaults.set(12, forKey: balanceKey)
    }

    #if DEBUG
        /// Clears the account-scoped rename state so QA runs stay isolated.
        func resetForQA() {
            for key in [usedKey, balanceKey, seededKey, qaAppliedKey] {
                defaults.removeObject(forKey: key)
            }
        }
    #endif

    #if DEBUG
        /// Deterministic QA launch state for the three dialog modes. The mode is
        /// authoritative on every launch so runs cannot leak into each other.
        private func applyQAMode(_ mode: String) {
            // Re-seed whenever the mode or the reset token changes, so each QA
            // run starts from a known allowance.
            let token = ProcessInfo.processInfo.environment[
                "QA_MINIHOME_RESET_TOKEN"
            ] ?? ""
            let stamp = "\(mode)|\(token)"
            guard defaults.string(forKey: qaAppliedKey) != stamp else {
                return
            }
            defaults.set(stamp, forKey: qaAppliedKey)
            defaults.set(true, forKey: seededKey)
            switch mode {
            case "free":
                defaults.set(false, forKey: usedKey)
                defaults.set(12, forKey: balanceKey)
            case "paid":
                defaults.set(true, forKey: usedKey)
                defaults.set(12, forKey: balanceKey)
            case "insufficient":
                defaults.set(true, forKey: usedKey)
                defaults.set(1, forKey: balanceKey)
            default:
                defaults.set(false, forKey: usedKey)
                defaults.set(12, forKey: balanceKey)
            }
        }
    #endif
}
