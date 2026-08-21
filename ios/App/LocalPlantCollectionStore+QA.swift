import Foundation
import PlanteriorData
import PlanteriorDomain

extension LocalPlantCollectionStore {
    func resetPersistenceForQA() {
        #if DEBUG
            guard ProcessInfo.processInfo.environment[
                "QA_RESET_COLLECTION"
            ] == "1" else {
                return
            }
            defaults.removeObject(forKey: plantsKey)
            defaults.removeObject(forKey: notesKey)
            defaults.removeObject(forKey: weatherPlantIDsKey)
        #endif
    }

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
            resetQAFixtureData()
            if ProcessInfo.processInfo.environment[
                "QA_HOME_CARE_VARIANTS"
            ] == "1" {
                plants = [
                    qaDraft(
                        id: "local-0",
                        name: "지연 식물",
                        lastWateredOn: try? CalendarDate.parse("2026-07-30")
                    ),
                    qaDraft(
                        id: "local-1",
                        name: "오늘 식물",
                        lastWateredOn: try? CalendarDate.parse("2026-08-01")
                    ),
                    qaDraft(
                        id: "local-2",
                        name: "예정 식물",
                        lastWateredOn: try? CalendarDate.parse("2026-08-10"),
                        wateringIntervalDays: 5
                    ),
                    qaDraft(id: "local-3", name: "미설정 식물", lastWateredOn: nil)
                ]
                reconcilePlantIdentities()
                persist()
                return
            }
            plants = [
                qaDraft(
                    id: "local-0",
                    name: "몬스테라",
                    lastWateredOn: try? CalendarDate.parse("2026-08-01")
                ),
                qaDraft(id: "local-1", name: "스킨답서스", lastWateredOn: nil)
            ]
            if ProcessInfo.processInfo.environment[
                "QA_COLLECTION_PRIVATE_FIXTURE"
            ] == "1" {
                plants.append(qaDraft(
                    id: "local-2", name: "비공개 식물", lastWateredOn: nil
                ))
            }
            reconcilePlantIdentities()
            persist()
        #endif
    }

    private func resetQAFixtureData() {
        plants.removeAll()
        resetPlantIdentities()
        healthNotesByPlantID.removeAll()
    }

    private func qaDraft(
        id: String,
        name: String,
        lastWateredOn: CalendarDate?,
        wateringIntervalDays: Int = 10
    ) -> PlantRegistrationDraft {
        PlantRegistrationDraft(
            plantID: try? PlantContentID.parse(id),
            displayName: name,
            representativePhoto: nil,
            lastWateredOn: lastWateredOn,
            wateringIntervalDays: wateringIntervalDays,
            registrationMethod: .manual
        )
    }
}
