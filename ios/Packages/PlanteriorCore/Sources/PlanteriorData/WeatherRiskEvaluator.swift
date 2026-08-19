import Foundation
import PlanteriorDomain

public struct WeatherRiskThresholds: Equatable, Sendable {
    public let lowTemperatureCelsius: Double
    public let highTemperatureCelsius: Double
    public let dryHumidityPercent: Int
    public let overwateredPrecipitationMillimeters: Double

    public static let plantDefault = WeatherRiskThresholds(
        lowTemperatureCelsius: 10,
        highTemperatureCelsius: 30,
        dryHumidityPercent: 40,
        overwateredPrecipitationMillimeters: 20
    )

    public init(
        lowTemperatureCelsius: Double,
        highTemperatureCelsius: Double,
        dryHumidityPercent: Int,
        overwateredPrecipitationMillimeters: Double
    ) {
        self.lowTemperatureCelsius = lowTemperatureCelsius
        self.highTemperatureCelsius = highTemperatureCelsius
        self.dryHumidityPercent = dryHumidityPercent
        self.overwateredPrecipitationMillimeters =
            overwateredPrecipitationMillimeters
    }
}

public struct WeatherRiskEvaluation: Equatable, Sendable {
    public let risks: [RiskType]
    public let isStale: Bool
    public let alertsAllowed: Bool
}

public enum WeatherRiskEvaluationError: Error, Equatable, Sendable {
    case invalidInstant
    case futureObservation
}

public struct WeatherRiskEvaluator: Sendable {
    private let now: Instant

    public init(now: Instant) {
        self.now = now
    }

    public func evaluate(
        snapshot: WeatherSnapshot,
        thresholds: WeatherRiskThresholds,
        globalAlertsEnabled: Bool,
        perPlantAlertsEnabled: Bool
    ) throws -> WeatherRiskEvaluation {
        var risks: [RiskType] = []
        let isHighTemperature =
            snapshot.temperatureCelsius >
            thresholds.highTemperatureCelsius
        if isHighTemperature {
            risks.append(.highTemperature)
        }
        let isLowTemperature =
            snapshot.temperatureCelsius <
            thresholds.lowTemperatureCelsius
        if isLowTemperature {
            risks.append(.lowTemperature)
        }
        if snapshot.humidityPercent < thresholds.dryHumidityPercent {
            risks.append(.dry)
        }
        let isOverwatered =
            snapshot.precipitationMillimeters >
            thresholds.overwateredPrecipitationMillimeters
        if isOverwatered {
            risks.append(.overwatered)
        }
        let age = try elapsedSeconds(
            from: snapshot.observedAt,
            to: now
        )
        guard age >= 0 else {
            throw WeatherRiskEvaluationError.futureObservation
        }
        let isStale = age > 3 * 60 * 60
        return WeatherRiskEvaluation(
            risks: risks,
            isStale: isStale,
            alertsAllowed:
            !isStale &&
                globalAlertsEnabled &&
                perPlantAlertsEnabled
        )
    }

    private func elapsedSeconds(
        from start: Instant,
        to end: Instant
    ) throws -> TimeInterval {
        guard
            let startDate = Self.date(from: start),
            let endDate = Self.date(from: end)
        else {
            throw WeatherRiskEvaluationError.invalidInstant
        }
        return endDate.timeIntervalSince(startDate)
    }

    private static func date(from instant: Instant) -> Date? {
        let fractional = ISO8601DateFormatter()
        fractional.formatOptions = [
            .withInternetDateTime,
            .withFractionalSeconds
        ]
        if let date = fractional.date(from: instant.rawValue) {
            return date
        }
        let wholeSeconds = ISO8601DateFormatter()
        wholeSeconds.formatOptions = [.withInternetDateTime]
        return wholeSeconds.date(from: instant.rawValue)
    }
}

public struct WeatherRiskEpisodeCoordinator: Sendable {
    private var activeByPlant: [PersonalPlantID: Set<RiskType>] = [:]

    public init() {}

    public mutating func alertsForTransition(
        plantID: PersonalPlantID,
        activeRisks: Set<RiskType>,
        globalEnabled: Bool,
        perPlantEnabled: Bool
    ) -> [RiskType] {
        let previous = activeByPlant[plantID] ?? []
        activeByPlant[plantID] = activeRisks
        guard globalEnabled, perPlantEnabled else {
            return []
        }
        return activeRisks
            .subtracting(previous)
            .sorted { riskOrder($0) < riskOrder($1) }
    }

    private func riskOrder(_ risk: RiskType) -> Int {
        switch risk {
        case .highTemperature: 0
        case .lowTemperature: 1
        case .dry: 2
        case .overwatered: 3
        }
    }
}
