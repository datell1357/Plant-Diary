import Foundation
@testable import Planterior
import PlanteriorData
import Testing

struct DomesticPlantCareCatalogTests {
    @Test
    func matchesAuthorSuffixedScientificName() throws {
        let profile = try #require(
            DomesticPlantCareCatalog.profile(
                scientificName: "Monstera deliciosa Liebm."
            )
        )

        #expect(profile.datasetID == "15059042")
    }

    @Test
    func matchesScientificNameWithoutDependingOnProviderIdentifier() throws {
        let profile = try #require(
            DomesticPlantCareCatalog.profile(
                scientificName: "  MONSTERA DELICIOSA "
            )
        )

        #expect(profile.datasetID == "15059042")
        #expect(profile.sourceURL.host == "www.data.go.kr")
        #expect(profile.metrics.map(\.id) == ["water", "light", "temperature", "humidity"])
    }

    @Test
    func unsupportedSpeciesDoesNotReceiveInventedCareGuidance() {
        #expect(
            DomesticPlantCareCatalog.profile(
                scientificName: "Plantus unsupported"
            ) == nil
        )
    }

    @Test
    func existingRegistrationsWithoutScientificNameStillDecode() throws {
        let existing = PlantRegistrationDraft(
            plantID: nil,
            displayName: "기존 식물",
            representativePhoto: nil,
            lastWateredOn: nil,
            registrationMethod: .manual
        )

        let restored = try JSONDecoder().decode(
            PlantRegistrationDraft.self,
            from: JSONEncoder().encode(existing)
        )

        #expect(restored.scientificName == nil)
        #expect(restored.displayName == "기존 식물")
    }
}
