import Foundation
import PlanteriorData

extension LocalPlantCollectionStore {
    func setSnapshotStateFromQA() {
        #if DEBUG
            snapshotState = CollectionViewState(
                rawValue: ProcessInfo.processInfo.environment[
                    "QA_COLLECTION_STATE"
                ] ?? "content"
            ) ?? .content
        #endif
    }

    func loadQAFixtureIfNeeded() {
        #if DEBUG
            guard ProcessInfo.processInfo.environment["QA_COLLECTION_FIXTURE"] == "1"
            else {
                return
            }
            plants.removeAll()
            healthNotes.removeAll()
            plants = [
                qaDraft(name: "몬스테라"),
                qaDraft(name: "스킨답서스")
            ]
            if ProcessInfo.processInfo.environment[
                "QA_COLLECTION_PRIVATE_FIXTURE"
            ] == "1" {
                plants.append(qaDraft(name: "비공개 식물"))
            }
            persist()
        #endif
    }

    private func qaDraft(name: String) -> PlantRegistrationDraft {
        PlantRegistrationDraft(
            plantID: nil,
            displayName: name,
            representativePhoto: nil,
            lastWateredOn: nil,
            registrationMethod: .manual
        )
    }
}
