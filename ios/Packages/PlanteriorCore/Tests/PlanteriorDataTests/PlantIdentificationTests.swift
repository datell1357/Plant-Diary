import Foundation
@testable import PlanteriorData
import PlanteriorDomain
import Testing

struct PlantIdentificationTests {
    @Test
    func emitsOnlyTopThreeCandidatesAndRequiresConfirmation() async throws {
        var candidates: [IdentificationCandidate] = []
        for index in 1 ... 4 {
            let thumbnailURL = try #require(
                URL(string: "https://images.example.invalid/plant-\(index).jpg")
            )
            try candidates.append(
                IdentificationCandidate(
                    plantID: PlantContentID.parse("plant-\(index)"),
                    koreanName: "식물 \(index)",
                    commonName: "Plant \(index)",
                    scientificName: "Plantus species \(index)",
                    thumbnailURL: thumbnailURL,
                    confidence: Double(100 - index) / 100
                )
            )
        }
        let coordinator = PlantIdentificationCoordinator(
            service: IdentificationServiceFake(
                states: [
                    .pending,
                    .candidates(IdentificationCandidates(candidates))
                ]
            )
        )

        await coordinator.submit(Data("acknowledged-image".utf8))
        #expect(await coordinator.candidateCount == 3)
        #expect(await coordinator.hasPersonalPlant == false)
        try await coordinator.selectCandidate(
            plantID: "plant-1",
            confidence: 0.99
        )
        #expect(await coordinator.hasPersonalPlant == false)
        await coordinator.confirmSelection()
        #expect(await coordinator.hasDraft)
        #expect(await coordinator.hasPersonalPlant == false)
    }

    @Test
    func supportsNoCandidatesFailureRetryReplaceAndManualPaths() async {
        let service = IdentificationServiceFake(
            states: [.pending, .noCandidates]
        )
        let coordinator = PlantIdentificationCoordinator(service: service)
        await coordinator.submit(Data("image".utf8))
        let noCandidates = await coordinator.state
        #expect(noCandidates == .noCandidates)
        await coordinator.beginManualEntry(name: "몬스테라")
        let draftName = await coordinator.draft?.displayName
        #expect(draftName == "몬스테라")

        service.replaceStates([.failed(.providerUnavailable)])
        await coordinator.retry()
        let providerFailure = await coordinator.state
        #expect(providerFailure == .failed(.providerUnavailable))
        await coordinator.replacePhoto()
        let awaitingPhoto = await coordinator.state
        #expect(awaitingPhoto == .awaitingPhoto)
    }

    @Test
    func duplicateRegistrationRequiresExplicitDecision() async throws {
        let coordinator = try PlantRegistrationCoordinator(
            existingPlantIDs: ["plant-duplicate"]
        )

        #expect(
            try await coordinator.register(
                plantID: "plant-duplicate",
                displayName: "기존 식물"
            ) == .duplicate
        )
        #expect(await coordinator.personalPlantCount == 0)
        #expect(await coordinator.resolveDuplicate(.openExisting) == .openedExisting)
        #expect(await coordinator.personalPlantCount == 0)
        #expect(
            try await coordinator.register(
                plantID: "plant-duplicate",
                displayName: "기존 식물"
            ) == .duplicate
        )
        #expect(await coordinator.resolveDuplicate(.addAnother) == .registered)
        #expect(await coordinator.personalPlantCount == 1)
    }

    @Test
    func retryReusesRequestIdentityUntilPhotoReplacement() async {
        let service = IdentificationServiceFake(states: [.failed(.providerUnavailable)])
        let coordinator = PlantIdentificationCoordinator(service: service)
        await coordinator.submit(Data("image".utf8))
        await coordinator.retry()
        let firstTwo = service.identities()
        #expect(firstTwo.count == 2)
        #expect(firstTwo[0] == firstTwo[1])

        await coordinator.replacePhoto()
        await coordinator.submit(Data("replacement".utf8))
        let all = service.identities()
        #expect(all[2] != all[1])
    }

    @Test
    func validatesNameDateAndPreservesDraftAfterSaveFailure() async throws {
        let failing = RegistrationStoreFake(shouldFail: true)
        let coordinator = PlantRegistrationCoordinator(
            existingPlantIDs: [],
            store: failing
        )
        let today = try CalendarDate.parse("2026-08-14")
        let future = try CalendarDate.parse("2026-08-15")

        await #expect(throws: PlantRegistrationError.invalidName) {
            try await coordinator.save(
                draft(name: " ", date: nil),
                today: today
            )
        }
        await #expect(throws: PlantRegistrationError.futureWateringDate) {
            try await coordinator.save(
                draft(name: "몬스테라", date: future),
                today: today
            )
        }
        await #expect(throws: PlantRegistrationError.saveFailed) {
            try await coordinator.save(
                draft(name: "몬스테라", date: today),
                today: today
            )
        }
        #expect(await coordinator.personalPlantCount == 0)
        #expect(await coordinator.failedDraft?.displayName == "몬스테라")
    }

    private func draft(
        name: String,
        date: CalendarDate?
    ) -> PlantRegistrationDraft {
        PlantRegistrationDraft(
            plantID: nil,
            displayName: name,
            representativePhoto: nil,
            lastWateredOn: date,
            registrationMethod: .manual
        )
    }
}
