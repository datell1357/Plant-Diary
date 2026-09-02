import Foundation

struct DomesticPlantCareProvenance {
    let providerName: String
    let datasetName: String
    let datasetID: String
    let sourceURL: URL
    let retrievedDate: Date?
    let sourceModifiedDate: Date?
    let originalSourceFieldNames: [String]?
    let transformationNotice: String
}

struct DomesticPlantCareProfile {
    let scientificName: String
    let metrics: [PlantGuideMetric]
    let provenance: DomesticPlantCareProvenance

    var sourceName: String {
        "\(provenance.providerName) \(provenance.datasetName)"
    }

    var sourceURL: URL {
        provenance.sourceURL
    }

    var datasetID: String {
        provenance.datasetID
    }
}

enum DomesticPlantCareCatalog {
    static func profile(scientificName: String?) -> DomesticPlantCareProfile? {
        guard let scientificName else { return nil }
        return profiles[normalized(scientificName)]
    }

    static func manualOptions(
        matching search: String
    ) -> [DomesticPlantCareProfile] {
        let query = normalized(search)
        guard !query.isEmpty else { return [] }
        return manualProfiles.filter { profile in
            curatedAliases[profile.scientificName, default: []].contains {
                normalized($0).contains(query)
            }
        }
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
        provenance: DomesticPlantCareProvenance(
            providerName: "농촌진흥청",
            datasetName: "실내정원용 식물",
            datasetID: "15059042",
            sourceURL: URL(
                string: "https://www.data.go.kr/data/15059042/openapi.do"
            )!,
            retrievedDate: nil,
            sourceModifiedDate: nil,
            originalSourceFieldNames: nil,
            transformationNotice: "앱이 공개 데이터를 식물 관리 지침으로 요약해 표시했습니다."
        )
    )

    private static let manualProfiles = [monstera]

    private static let curatedAliases = [
        monstera.scientificName: [
            monstera.scientificName,
            "Monstera deliciosa Liebm.",
            "몬스테라",
            "몬스테라 델리시오사"
        ]
    ]

    private static let profiles = Dictionary(
        uniqueKeysWithValues: curatedAliases[
            monstera.scientificName,
            default: []
        ].map { (normalized($0), monstera) }
    )

    private static func normalized(_ value: String) -> String {
        value
            .precomposedStringWithCanonicalMapping
            .split(whereSeparator: \.isWhitespace)
            .joined(separator: " ")
            .lowercased()
    }
}
