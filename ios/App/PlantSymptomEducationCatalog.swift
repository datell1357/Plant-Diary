import Foundation

struct PlantSymptomEducation: Equatable {
    let scientificName: String
    let items: [PlantSymptomGuidance]
}

struct PlantSymptomGuidance: Equatable {
    let icon: String
    let title: String
    let possibleCause: String
    let initialResponse: String
}

enum PlantSymptomEducationCatalog {
    static let disclaimer =
        "이 안내는 진단이 아니며, 관찰 가능한 가능성과 확인 순서만 제공합니다."

    static func education(scientificName: String?) -> PlantSymptomEducation? {
        education(scientificName: scientificName, hasWateringBaseline: false)
    }

    static func education(
        scientificName: String?,
        hasWateringBaseline: Bool
    ) -> PlantSymptomEducation {
        guard let scientificName, monsteraAliases.contains(normalized(scientificName)) else {
            return genericEducation(hasWateringBaseline: hasWateringBaseline)
        }
        return monstera
    }

    private static let monsteraAliases = Set([
        normalized("Monstera deliciosa"),
        normalized("Monstera deliciosa Liebm.")
    ])

    private static let monstera = PlantSymptomEducation(
        scientificName: "Monstera deliciosa",
        items: [
            PlantSymptomGuidance(
                icon: "🍂",
                title: "몬스테라 잎이 노랗게 변해요",
                possibleCause: "화분 속 흙이 오래 축축하거나 빛 조건이 갑자기 바뀐 경우 등 여러 가능성이 있어요.",
                initialResponse: "겉흙뿐 아니라 화분 안쪽의 습기를 확인하고, 밝은 간접광인지 살펴보세요. 변화를 날짜와 함께 기록해 관찰하세요."
            ),
            PlantSymptomGuidance(
                icon: "🥀",
                title: "몬스테라 잎 끝이 갈색으로 말라요",
                possibleCause: "실내가 건조하거나 물 주기 간격 및 뿌리 상태가 변할 때 보일 수 있어요.",
                initialResponse: "흙의 수분과 실내 습도를 확인하고, 물을 주기 전 아래쪽 흙도 만져 보세요. 변화를 며칠간 기록해 관찰하세요."
            ),
            PlantSymptomGuidance(
                icon: "🟤",
                title: "몬스테라 잎의 반점이나 손상이 늘어요",
                possibleCause: "직사광선, 잎에 오래 남은 물, 통풍 변화 등 환경 요인을 함께 확인해 볼 수 있어요.",
                initialResponse: "강한 빛과 잎 표면의 물기를 확인하고, 통풍이 되는지 살펴보세요. 손상이 계속 번지면 식물 전문가에게 상담하세요."
            )
        ]
    )

    private static func genericEducation(
        hasWateringBaseline: Bool
    ) -> PlantSymptomEducation {
        PlantSymptomEducation(
            scientificName: "일반 관찰 안내",
            items: [
                PlantSymptomGuidance(
                    icon: "🔎",
                    title: "종 정보를 알 수 없는 식물의 관찰 안내",
                    possibleCause: "관찰만으로 원인을 알 수 없으므로, 물·빛·통풍처럼 확인할 수 있는 환경 변화를 차례로 살펴보세요.",
                    initialResponse: hasWateringBaseline
                        ? "최근 물 준 날짜가 기록되어 있으니, 그 날짜와 현재 흙 안쪽의 습기를 먼저 함께 확인하세요. 이어서 빛과 통풍 변화를 관찰해 기록하세요."
                        : "최근 물 준 날짜가 기록되어 있지 않으니, 먼저 현재 흙 안쪽의 습기와 배수 상태를 관찰해 기록하세요. 이어서 빛과 통풍 변화를 확인하세요."
                )
            ]
        )
    }

    private static func normalized(_ value: String) -> String {
        value
            .precomposedStringWithCanonicalMapping
            .split(whereSeparator: \.isWhitespace)
            .joined(separator: " ")
            .lowercased()
    }
}
