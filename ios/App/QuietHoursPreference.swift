import PlanteriorDomain

struct QuietHoursPreference: Equatable, Sendable {
    let enabled: Bool
    let start: LocalTime
    let end: LocalTime

    func contains(_ time: LocalTime) -> Bool {
        guard enabled, start != end else {
            return false
        }
        if start.rawValue < end.rawValue {
            return time.rawValue >= start.rawValue && time.rawValue < end.rawValue
        }
        return time.rawValue >= start.rawValue || time.rawValue < end.rawValue
    }
}
