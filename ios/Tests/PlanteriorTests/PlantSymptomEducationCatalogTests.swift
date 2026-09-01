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
    func doesNotInventSpeciesEducationForMissingOrUnsupportedIdentity(
        scientificName: String?
    ) {
        #expect(PlantSymptomEducationCatalog.education(scientificName: scientificName) == nil)
    }

    @Test
    func disclaimerStatesNonDiagnosticPurposeAndProfessionalEscalation() {
        let disclaimer = PlantSymptomEducationCatalog.disclaimer

        #expect(disclaimer.contains("확정 진단이 아닙니다."))
        #expect(disclaimer.contains("식물 전문가에게 상담하세요."))
    }
}
