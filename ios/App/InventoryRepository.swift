import Combine
import Foundation
import PlanteriorData
import PlanteriorDomain

@MainActor
final class InventoryRepository: ObservableObject {
    @Published var catalog: [ShopItem] = []
    @Published var ownedItems: [OwnedItem] = []

    let defaults: UserDefaults
    let now: Instant?
    let allowsLocalAcquisition: Bool
    let failFirstAcquisition: Bool
    let authoritativeService: any AuthoritativeInventoryService
    var didFailAcquisition = false
    var isAuthoritativeForCurrentMount = false
    var provenance: InventorySnapshotProvenance?
    var accountID: String?
    var inventoryRequestGeneration = 0
    private var lastAuthoritativeRefreshAt: Date?
    private var authoritativeRefreshTask: Task<Bool, Never>?
    private var authoritativeRefreshTaskGeneration: Int?
    private var authoritativeRefreshTaskToken: UInt64 = 0
    private let automaticRefreshInterval: TimeInterval = 30

    init(
        defaults: UserDefaults = .standard,
        now: Instant?,
        allowsLocalAcquisition: Bool,
        failFirstAcquisition: Bool = false,
        authoritativeService: (any AuthoritativeInventoryService)? = nil
    ) {
        self.defaults = defaults
        self.now = now
        self.allowsLocalAcquisition = allowsLocalAcquisition
        self.failFirstAcquisition = failFirstAcquisition
        self.authoritativeService = authoritativeService
            ?? UnavailableAuthoritativeInventoryService()
    }

    func mount(accountID: String?) {
        authoritativeRefreshTask?.cancel()
        authoritativeRefreshTask = nil
        authoritativeRefreshTaskGeneration = nil
        authoritativeRefreshTaskToken &+= 1
        lastAuthoritativeRefreshAt = nil
        inventoryRequestGeneration &+= 1
        self.accountID = accountID
        catalog = InventoryCatalog.items()
        ownedItems = []
        provenance = nil
        isAuthoritativeForCurrentMount = allowsLocalAcquisition
            && accountID != nil
        guard let accountID,
              let snapshot = persistedSnapshot()
        else {
            return
        }
        let isLocalSnapshot = snapshot.source == .local
            || snapshot.source == .qaFixture
        if allowsLocalAcquisition, isLocalSnapshot {
            catalog = snapshot.catalog
            ownedItems = snapshot.ownedItems
            isAuthoritativeForCurrentMount = true
            return
        }
        guard snapshot.source == .serverAuthorized,
              let persistedProvenance = snapshot.provenance,
              persistedProvenance.ownerID == accountID,
              persistedProvenance.inventoryGeneration > 0,
              persistedProvenance.snapshotHash.range(
                  of: "^[a-f0-9]{64}$",
                  options: .regularExpression
              ) != nil,
              Set(snapshot.ownedItems.map(\.itemID)).isSubset(
                  of: Set(snapshot.catalog.map(\.id))
              )
        else {
            return
        }
        // A cache can render, but cannot authorize until this mount reloads it.
        catalog = snapshot.catalog
        ownedItems = snapshot.ownedItems
        provenance = persistedProvenance
    }

    @discardableResult
    func refreshAuthoritative(force: Bool = false) async -> Bool {
        guard !allowsLocalAcquisition,
              let mountedAccountID = accountID
        else {
            return allowsLocalAcquisition && accountID != nil
        }
        if !force,
           let lastAuthoritativeRefreshAt,
           Date().timeIntervalSince(lastAuthoritativeRefreshAt)
           < automaticRefreshInterval
        {
            return isAuthoritativeForCurrentMount
        }
        if let authoritativeRefreshTask {
            let joinedGeneration = inventoryRequestGeneration
            let existingResult = await authoritativeRefreshTask.value
            // A forced refresh is used after a mutation. It must not reuse the
            // pre-mutation snapshot that happened to be in flight.
            guard accountID == mountedAccountID,
                  inventoryRequestGeneration == joinedGeneration
            else { return false }
            guard force else { return existingResult }
        }
        let requestGeneration = inventoryRequestGeneration
        authoritativeRefreshTaskToken &+= 1
        let taskToken = authoritativeRefreshTaskToken
        let task = Task { [weak self] in
            guard let self else { return false }
            return await performAuthoritativeRefresh(
                accountID: mountedAccountID,
                requestGeneration: requestGeneration
            )
        }
        authoritativeRefreshTask = task
        authoritativeRefreshTaskGeneration = requestGeneration
        let result = await task.value
        if authoritativeRefreshTaskGeneration == requestGeneration,
           authoritativeRefreshTaskToken == taskToken
        {
            authoritativeRefreshTask = nil
            authoritativeRefreshTaskGeneration = nil
        }
        return result
    }

    private func performAuthoritativeRefresh(
        accountID mountedAccountID: String,
        requestGeneration: Int
    ) async -> Bool {
        guard accountID == mountedAccountID,
              inventoryRequestGeneration == requestGeneration
        else { return false }
        do {
            let snapshot = try await authoritativeService.load(
                accountID: mountedAccountID
            )
            guard accountID == mountedAccountID,
                  inventoryRequestGeneration == requestGeneration
            else { return false }
            let applied = applyAuthoritative(
                snapshot,
                accountID: mountedAccountID
            )
            if applied {
                lastAuthoritativeRefreshAt = Date()
            }
            return applied
        } catch {
            guard accountID == mountedAccountID,
                  inventoryRequestGeneration == requestGeneration
            else { return false }
            isAuthoritativeForCurrentMount = false
            lastAuthoritativeRefreshAt = nil
            return false
        }
    }

    @discardableResult
    func replaceFixture(
        catalog: [ShopItem],
        ownedItems: [OwnedItem],
        source: InventorySnapshotSource = .local
    ) -> Bool {
        guard allowsLocalAcquisition,
              source == .local || source == .qaFixture
        else {
            return false
        }
        self.catalog = catalog
        self.ownedItems = ownedItems
        provenance = nil
        isAuthoritativeForCurrentMount = accountID != nil
        return persist(source: source, provenance: nil)
    }

    func synchronizeAppliedItems(with room: MiniHome?) {
        let appliedItemIDs = Set(room?.placements.compactMap(\.itemID) ?? [])
        ownedItems = ownedItems.map { item in
            OwnedItem(
                itemID: item.itemID,
                acquiredAt: item.acquiredAt,
                applied: appliedItemIDs.contains(item.itemID),
                revision: item.revision
            )
        }
    }

    func entries(
        category: ItemCategory?,
        metConditions: Set<String>
    ) -> [InventoryCatalogEntry] {
        InventoryCatalogPolicy.entries(
            items: catalog,
            ownedItemIDs: Set(ownedItems.map(\.itemID)),
            metConditions: metConditions,
            category: category
        )
    }
}
