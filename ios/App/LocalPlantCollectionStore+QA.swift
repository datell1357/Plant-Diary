import Foundation
import PlanteriorData
import PlanteriorDomain

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
            if ProcessInfo.processInfo.environment[
                "QA_HOME_CARE_VARIANTS"
            ] == "1" {
                plants = [
                    qaDraft(
                        name: "지연 식물",
                        lastWateredOn: try? CalendarDate.parse("2026-07-30")
                    ),
                    qaDraft(
                        name: "오늘 식물",
                        lastWateredOn: try? CalendarDate.parse("2026-08-01")
                    ),
                    qaDraft(
                        name: "예정 식물",
                        lastWateredOn: try? CalendarDate.parse("2026-08-10"),
                        wateringIntervalDays: 5
                    ),
                    qaDraft(name: "미설정 식물", lastWateredOn: nil)
                ]
                persist()
                return
            }
            plants = [
                qaDraft(
                    name: "몬스테라",
                    lastWateredOn: try? CalendarDate.parse("2026-08-01")
                ),
                qaDraft(name: "스킨답서스", lastWateredOn: nil)
            ]
            if ProcessInfo.processInfo.environment[
                "QA_COLLECTION_PRIVATE_FIXTURE"
            ] == "1" {
                plants.append(qaDraft(name: "비공개 식물", lastWateredOn: nil))
            }
            persist()
        #endif
    }

    private func qaDraft(
        name: String,
        lastWateredOn: CalendarDate?,
        wateringIntervalDays: Int = 10
    ) -> PlantRegistrationDraft {
        PlantRegistrationDraft(
            plantID: nil,
            displayName: name,
            representativePhoto: nil,
            lastWateredOn: lastWateredOn,
            wateringIntervalDays: wateringIntervalDays,
            registrationMethod: .manual
        )
    }
}
