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
    static let disclaimer = "이 내용은 가능한 원인과 초기 확인 방법을 안내하는 교육 자료이며, 확정 진단이 아닙니다. 증상이 악화되거나 긴급한 상태라면 식물 전문가에게 상담하세요."

    static func education(scientificName: String?) -> PlantSymptomEducation? {
        guard let scientificName, monsteraAliases.contains(normalized(scientificName)) else {
            return nil
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

    private static func normalized(_ value: String) -> String {
        value
            .precomposedStringWithCanonicalMapping
            .split(whereSeparator: \.isWhitespace)
            .joined(separator: " ")
            .lowercased()
    }
}
