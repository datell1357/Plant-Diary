import Foundation
@testable import PlanteriorData
import PlanteriorDomain
import Testing

struct PlantCollectionTests {
    @Test
    func sortsByNicknameAndFiltersSearch() throws {
        let plants = try [
            plant(id: "plant-b", nickname: "스킨답서스"),
            plant(id: "plant-a", nickname: "몬스테라")
        ]
        let collection = PlantCollection(plants: plants)

        #expect(collection.filtered(search: "").map(\.displayName) == ["몬스테라", "스킨답서스"])
        #expect(collection.filtered(search: "몬").map(\.displayName) == ["몬스테라"])
    }

    @Test
    func detailsPreserveTimelineDraftAndDeletionConfirmation() throws {
        let coordinator = try PlantCareDetailCoordinator(
            plant: plant(id: "plant-a", nickname: "몬스테라")
        )
        coordinator.updateNickname(" 몬스테라 새잎 ")
        coordinator.addHealthNote("잎 끝이 조금 말랐어요")
        coordinator.requestDeletion()

        #expect(coordinator.draftNickname == "몬스테라 새잎")
        #expect(coordinator.healthNotes.count == 1)
        #expect(coordinator.deletionState == .confirmationRequired)
        coordinator.cancelDeletion()
        #expect(coordinator.deletionState == .idle)
        try coordinator.validateEdits(
            location: String(repeating: "가", count: 50),
            privateMemo: String(repeating: "나", count: 1000)
        )
        #expect(throws: PlantCareValidationError.invalidLocation) {
            try coordinator.validateEdits(
                location: String(repeating: "가", count: 51),
                privateMemo: ""
            )
        }
    }

    @Test
    func filtersUnpublishedDeletedAndForbiddenContent() throws {
        let publishedID = try PlantContentID.parse("published")
        let deletedID = try PlantContentID.parse("deleted")
        let freeText = try plant(id: "free-text", nickname: "직접 입력")
        let published = try plant(
            id: "published-plant",
            nickname: "공개 식물",
            contentID: publishedID
        )
        let deleted = try plant(
            id: "deleted-plant",
            nickname: "비공개 식물",
            contentID: deletedID
        )
        let snapshot = PlantCollectionRepository().snapshot(
            plants: [published, deleted, freeText],
            access: [publishedID: .published, deletedID: .deleted],
            cachedPublishedIDs: []
        )
        #expect(snapshot == .partial([published, freeText]))
    }

    private func plant(
        id: String,
        nickname: String,
        contentID: PlantContentID? = nil
    ) throws -> PersonalPlant {
        let plantID = try PersonalPlantID.parse(id)
        let revision = try Revision.parse(0)
        let updatedAt = try Instant.parse("2026-08-14T00:00:00Z")
        return PersonalPlant(
            id: plantID,
            displayName: nickname,
            contentID: contentID,
            registrationMethod: .manual,
            representativePhotoPath: String?.none,
            location: String?.none,
            note: String?.none,
            lastWateredDate: CalendarDate?.none,
            revision: revision,
            updatedAt: updatedAt
        )
    }
}
