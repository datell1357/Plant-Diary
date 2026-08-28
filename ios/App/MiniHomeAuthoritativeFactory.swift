import Foundation
import PlanteriorDomain

@MainActor
struct MiniHomeAuthoritativeBoundary {
    let service: any MiniHomeAuthoritativeService
    let cache: MiniHomeVerifiedCache
}

@MainActor
enum MiniHomeAuthoritativeFactory {
    static func current() -> MiniHomeAuthoritativeBoundary {
        #if DEBUG
            return debugBoundary(
                defaults: .standard,
                environment: ProcessInfo.processInfo.environment
            )
        #else
            return makeRelease(
                firebaseConfigured: FirebaseConfiguration.isAvailable,
                defaults: .standard
            )
        #endif
    }

    static func makeRelease(
        firebaseConfigured: Bool,
        defaults: UserDefaults,
        client: (any MiniHomeCallableClient)? = nil
    ) -> MiniHomeAuthoritativeBoundary {
        let service: any MiniHomeAuthoritativeService = if firebaseConfigured {
            if let client {
                FirebaseMiniHomeAuthoritativeService(client: client)
            } else {
                FirebaseMiniHomeAuthoritativeService()
            }
        } else {
            UnavailableMiniHomeAuthoritativeService()
        }
        return MiniHomeAuthoritativeBoundary(
            service: service,
            cache: MiniHomeVerifiedCache(defaults: defaults)
        )
    }

    #if DEBUG
        static func makeDebug(
            service: any MiniHomeAuthoritativeService,
            defaults: UserDefaults
        ) -> MiniHomeAuthoritativeBoundary {
            MiniHomeAuthoritativeBoundary(
                service: service,
                cache: MiniHomeVerifiedCache(defaults: defaults)
            )
        }

        private static func debugBoundary(
            defaults: UserDefaults,
            environment: [String: String]
        ) -> MiniHomeAuthoritativeBoundary {
            let cache = MiniHomeVerifiedCache(defaults: defaults)
            let accountID = environment["QA_ACCOUNT_ID"]
                ?? environment["QA_INVENTORY_ACCOUNT_ID"]
                ?? "qa-account"
            resetDebugStateIfNeeded(
                accountID: accountID,
                defaults: defaults,
                cache: cache,
                environment: environment
            )
            let cached = cache.load(accountID: accountID)
            let seeded: MiniHomeVerifiedSnapshot?
            do {
                seeded = try cached ?? debugSeed(
                    accountID: accountID,
                    environment: environment
                )
            } catch {
                return MiniHomeAuthoritativeBoundary(
                    service: UnavailableMiniHomeAuthoritativeService(),
                    cache: cache
                )
            }
            let snapshots = seeded.map { [accountID: $0] } ?? [:]
            return MiniHomeAuthoritativeBoundary(
                service: InMemoryMiniHomeAuthoritativeService(
                    snapshots: snapshots,
                    failsSave: environment["QA_MINIHOME_SAVE_FAILURE"] == "1",
                    forcesConflict: environment["QA_MINIHOME_CONFLICT_ONCE"] == "1"
                ),
                cache: cache
            )
        }

        private static func resetDebugStateIfNeeded(
            accountID: String,
            defaults: UserDefaults,
            cache: MiniHomeVerifiedCache,
            environment: [String: String]
        ) {
            guard let token = environment["QA_MINIHOME_RESET_TOKEN"] else { return }
            let tokenKey = "qa.minihome.authoritative.reset-token.\(accountID)"
            guard defaults.string(forKey: tokenKey) != token else { return }
            cache.removeSnapshot(accountID: accountID)
            defaults.removeObject(
                forKey: "home.\(accountID).committed-mini-home"
            )
            defaults.set(token, forKey: tokenKey)
        }

        private static func debugSeed(
            accountID: String,
            environment: [String: String]
        ) throws -> MiniHomeVerifiedSnapshot? {
            guard environment["QA_HOME_FIXTURE"] == "1",
                  let draft = try MiniHomeView.defaultDraft(
                      updatedAt: MiniHomeView.runtimeInstant()
                  ),
                  let revision = try? Revision.parse(1),
                  let updatedAt = try? MiniHomeResponseDecoder.instant(
                      1_786_413_600_000
                  )
            else { return nil }
            let home = MiniHome(
                id: draft.id,
                name: "민지의 미니 식물원",
                placements: draft.placements,
                revision: revision,
                updatedAt: updatedAt
            )
            return .verified(
                accountID: accountID,
                home: home,
                updatedAtEpochMillis: 1_786_413_600_000
            )
        }
    #endif
}
