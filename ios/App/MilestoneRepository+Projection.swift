import PlanteriorDomain

extension MilestoneRepository {
    var projection: ProgressionProjection? {
        snapshot.map {
            ProgressionProjection(
                authoritative: $0,
                pendingEvents: pendingEvents
            )
        }
    }
}
