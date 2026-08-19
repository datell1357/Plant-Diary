import Foundation
import PlanteriorDomain

struct HomeCommittedMiniHomeRepository {
    private let defaults: UserDefaults
    private let key: String

    init(
        accountID: String?,
        defaults: UserDefaults = .standard
    ) {
        self.defaults = defaults
        key = "home.\(accountID ?? "signed-out").committed-mini-home"
    }

    func load() -> MiniHome? {
        guard let data = defaults.data(forKey: key) else {
            return nil
        }
        return try? JSONDecoder().decode(MiniHome.self, from: data)
    }

    func seedQAIfNeeded(processInfo: ProcessInfo = .processInfo) {
        #if DEBUG
            guard processInfo.environment["QA_HOME_FIXTURE"] == "1" else {
                return
            }
            guard
                let id = try? MiniHomeID.parse("qa-committed-room"),
                let revision = try? Revision.parse(1),
                let updatedAt = try? Instant.parse("2026-08-11T00:00:00Z"),
                let data = try? JSONEncoder().encode(
                    MiniHome(
                        id: id,
                        name: "초록 방",
                        placements: [],
                        revision: revision,
                        updatedAt: updatedAt
                    )
                )
            else {
                return
            }
            defaults.set(data, forKey: key)
        #endif
    }
}
