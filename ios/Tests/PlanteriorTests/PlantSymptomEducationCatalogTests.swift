@testable import Planterior
import Testing

struct PlantSymptomEducationCatalogTests {
    @Test
    func supportsCuratedMonsteraScientificNameAndAuthorAlias() throws {
        let canonical = try #require(
            PlantSymptomEducationCatalog.education(scientificName: "Monstera deliciosa")
        )
        let alias = try #require(
            PlantSymptomEducationCatalog.education(
                scientificName: "Monstera deliciosa Liebm."
            )
        )

        #expect(canonical.scientificName == "Monstera deliciosa")
        #expect(alias == canonical)
        #expect(canonical.items.count == 3)
    }

    @Test(arguments: [nil, "Plantus unsupported"])
    func providesGenericEducationForMissingOrUnsupportedIdentity(
        scientificName: String?
    ) {
        let education = PlantSymptomEducationCatalog.education(
            scientificName: scientificName,
            hasWateringBaseline: false
        )

        #expect(education.items.count == 1)
        #expect(education.items[0].title.contains("종 정보"))
        #expect(education.items[0].initialResponse.hasPrefix("최근 물 준 날짜가 기록되어 있지 않으니"))
    }

    @Test
    func genericEducationChecksRecordedWateringBeforeOtherObservations() {
        let withBaseline = PlantSymptomEducationCatalog.education(
            scientificName: "Plantus unsupported",
            hasWateringBaseline: true
        )
        let withoutBaseline = PlantSymptomEducationCatalog.education(
            scientificName: nil,
            hasWateringBaseline: false
        )

        #expect(withBaseline.items.count == 1)
        #expect(withoutBaseline.items.count == 1)
        #expect(withBaseline.items[0].initialResponse.hasPrefix("최근 물 준 날짜가 기록되어 있으니"))
        #expect(withoutBaseline.items[0].initialResponse.hasPrefix("최근 물 준 날짜가 기록되어 있지 않으니"))
        #expect(withBaseline.items[0].initialResponse != withoutBaseline.items[0].initialResponse)
    }

    @Test
    func disclaimerUsesTheBoundedNonDiagnosticCopy() {
        #expect(
            PlantSymptomEducationCatalog.disclaimer
                == "이 안내는 진단이 아니며, 관찰 가능한 가능성과 확인 순서만 제공합니다."
        )
    }
}
