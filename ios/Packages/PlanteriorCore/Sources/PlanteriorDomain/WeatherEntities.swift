public struct WeatherSnapshot: Codable, Equatable, Sendable {
    public let id: WeatherSnapshotID
    public let regionCode: String
    public let temperatureCelsius: Double
    public let humidityPercent: Int
    public let precipitationMillimeters: Double
    public let observedAt: Instant
    public let expiresAt: Instant

    public init(
        id: WeatherSnapshotID,
        regionCode: String,
        temperatureCelsius: Double,
        humidityPercent: Int,
        precipitationMillimeters: Double,
        observedAt: Instant,
        expiresAt: Instant
    ) {
        self.id = id
        self.regionCode = regionCode
        self.temperatureCelsius = temperatureCelsius
        self.humidityPercent = humidityPercent
        self.precipitationMillimeters = precipitationMillimeters
        self.observedAt = observedAt
        self.expiresAt = expiresAt
    }
}

public struct WeatherRisk: Codable, Equatable, Sendable {
    public let id: WeatherRiskID
    public let plantID: PersonalPlantID
    public let snapshotID: WeatherSnapshotID
    public let type: RiskType
    public let action: String?
    public let detectedAt: Instant
    public let active: Bool
    public let revision: Revision
}
