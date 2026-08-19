public enum LocationAuthorizationState: Equatable, Sendable {
    case notDetermined
    case denied
    case reduced
    case full
}

public struct WeatherRegionSelection: Equatable, Sendable {
    public let authorization: LocationAuthorizationState
    public let manualRegionCode: String?
    public let locationRegionCode: String?

    public init(
        authorization: LocationAuthorizationState,
        manualRegionCode: String?,
        locationRegionCode: String?
    ) {
        self.authorization = authorization
        self.manualRegionCode = manualRegionCode
        self.locationRegionCode = locationRegionCode
    }

    public var effectiveRegionCode: String? {
        if let manualRegionCode, !manualRegionCode.isEmpty {
            return manualRegionCode
        }
        guard shouldRequestLocation else {
            return nil
        }
        return locationRegionCode
    }

    public var shouldRequestLocation: Bool {
        guard manualRegionCode?.isEmpty != false else {
            return false
        }
        return authorization == .reduced || authorization == .full
    }
}
