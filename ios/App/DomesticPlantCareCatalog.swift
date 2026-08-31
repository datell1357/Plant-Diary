import Foundation

struct DomesticPlantCareProfile {
    let scientificName: String
    let metrics: [PlantGuideMetric]
    let sourceName: String
    let sourceURL: URL
    let datasetID: String
}

enum DomesticPlantCareCatalog {
    static func profile(scientificName: String?) -> DomesticPlantCareProfile? {
        guard let scientificName else { return nil }
        return profiles[normalized(scientificName)]
    }

    static let monstera = DomesticPlantCareProfile(
        scientificName: "Monstera deliciosa",
        metrics: [
            PlantGuideMetric(
                id: "water",
                icon: "💧",
                title: "물 주기",
                value: "봄~가을 촉촉하게",
                hint: "겨울에는 겉흙이 마르면 충분히"
            ),
            PlantGuideMetric(
                id: "light",
                icon: "☀️",
                title: "햇빛",
                value: "800~10,000 Lux",
                hint: "중간~높은 광도, 직사광선은 주의"
            ),
            PlantGuideMetric(
                id: "temperature",
                icon: "🌡️",
                title: "온도",
                value: "16~20°C",
                hint: "겨울에는 13°C 이상 유지"
            ),
            PlantGuideMetric(
                id: "humidity",
                icon: "💨",
                title: "습도",
                value: "70% 이상",
                hint: "더운 여름에는 잎을 닦고 분무"
            )
        ],
        sourceName: "농촌진흥청 실내정원용 식물",
        sourceURL: URL(string: "https://www.data.go.kr/data/15059042/openapi.do")!,
        datasetID: "15059042"
    )

    private static let profiles = [
        normalized(monstera.scientificName): monstera,
        normalized("Monstera deliciosa Liebm."): monstera
    ]

    private static func normalized(_ value: String) -> String {
        value.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
    }
}
