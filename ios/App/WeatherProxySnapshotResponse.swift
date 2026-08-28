import Foundation
import PlanteriorDomain

struct WeatherProxySnapshotResponse: Decodable {
    let id: String
    let regionCode: String
    let temperatureCelsius: Double
    let humidityPercent: Int
    let precipitationMillimeters: Double
    let observedAt: String
    let expiresAt: String

    private enum CodingKeys: String, CodingKey, CaseIterable {
        case id
        case regionCode
        case temperatureCelsius
        case humidityPercent
        case precipitationMillimeters
        case observedAt
        case expiresAt
    }

    private struct AnyCodingKey: CodingKey {
        let stringValue: String
        let intValue: Int?

        init?(stringValue: String) {
            self.stringValue = stringValue
            intValue = nil
        }

        init?(intValue: Int) {
            stringValue = String(intValue)
            self.intValue = intValue
        }
    }

    init(from decoder: Decoder) throws {
        let payload = try decoder.container(keyedBy: AnyCodingKey.self)
        let actualKeys = Set(payload.allKeys.map(\.stringValue))
        let allowedKeys = Set(CodingKeys.allCases.map(\.stringValue))
        guard actualKeys == allowedKeys else {
            throw DecodingError.dataCorrupted(
                DecodingError.Context(
                    codingPath: decoder.codingPath,
                    debugDescription: "Weather proxy payload keys do not match schema"
                )
            )
        }
        let values = try decoder.container(keyedBy: CodingKeys.self)
        id = try values.decode(String.self, forKey: .id)
        regionCode = try values.decode(String.self, forKey: .regionCode)
        temperatureCelsius = try values.decode(
            Double.self,
            forKey: .temperatureCelsius
        )
        humidityPercent = try values.decode(Int.self, forKey: .humidityPercent)
        precipitationMillimeters = try values.decode(
            Double.self,
            forKey: .precipitationMillimeters
        )
        observedAt = try values.decode(String.self, forKey: .observedAt)
        expiresAt = try values.decode(String.self, forKey: .expiresAt)
    }

    static func decode(
        data: Data,
        requestedRegionCode: String
    ) throws -> WeatherSnapshot {
        let response = try JSONDecoder().decode(Self.self, from: data)
        guard response.regionCode == requestedRegionCode,
              response.temperatureCelsius.isFinite,
              (0 ... 100).contains(response.humidityPercent),
              response.precipitationMillimeters.isFinite,
              response.precipitationMillimeters >= 0
        else {
            throw WeatherRepositoryError.invalidResponse
        }
        do {
            return try WeatherSnapshot(
                id: WeatherSnapshotID.parse(response.id),
                regionCode: response.regionCode,
                temperatureCelsius: response.temperatureCelsius,
                humidityPercent: response.humidityPercent,
                precipitationMillimeters: response.precipitationMillimeters,
                observedAt: Instant.parse(response.observedAt),
                expiresAt: Instant.parse(response.expiresAt)
            )
        } catch {
            throw WeatherRepositoryError.invalidResponse
        }
    }
}
