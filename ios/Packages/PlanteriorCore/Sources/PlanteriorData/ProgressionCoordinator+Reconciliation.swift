import PlanteriorDomain

public extension ProgressionCoordinator {
    static func reconcile(
        authoritative: ProgressionSnapshot,
        pendingEvents: [PendingProgressionEvent]
    ) -> ProgressionProjection {
        let processed = Set(authoritative.receipts.map(\.eventID))
        return ProgressionProjection(
            authoritative: authoritative,
            pendingEvents: pendingEvents.filter {
                !processed.contains($0.id)
            }
        )
    }
}
