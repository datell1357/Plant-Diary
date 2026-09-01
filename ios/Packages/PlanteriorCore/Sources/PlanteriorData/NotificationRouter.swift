import PlanteriorDomain

public enum NotificationRouteResolution: Equatable, Sendable {
    case plant(PersonalPlantID)
    case requiresAuthentication
    case unavailable
}

public struct NotificationRouter: Sendable {
    public init() {}

    public func resolve(
        plantID: PersonalPlantID,
        authenticated: Bool,
        targetAvailable: Bool
    ) -> NotificationRouteResolution {
        guard authenticated else {
            return .requiresAuthentication
        }
        guard targetAvailable else {
            return .unavailable
        }
        return .plant(plantID)
    }
}
