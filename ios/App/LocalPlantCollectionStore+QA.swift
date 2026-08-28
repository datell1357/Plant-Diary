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
            let accountPrefix = "collection.\(accountID)."
            let accountKeys = defaults.dictionaryRepresentation().keys.filter {
                $0.hasPrefix(accountPrefix)
            }
            for key in accountKeys {
                defaults.removeObject(forKey: key)
            }
            qaFixtureMountReceipt = "pending"
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
            if ProcessInfo.processInfo.environment["QA_COLLECTION_FIGMA_FIXTURE"] == "1" {
                installFigmaQAFixture()
                return
            }
            if ProcessInfo.processInfo.environment["QA_HOME_CARE_VARIANTS"] == "1" {
                installCareVariantsQAFixture()
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
            finishInstallingQAFixture()
        #endif
    }

    private func installFigmaQAFixture() {
        plants = [
            qaDraft(
                id: "local-0",
                name: "몬스테라",
                lastWateredOn: try? CalendarDate.parse("2026-05-15"),
                wateringIntervalDays: 4,
                privateMemo: "최근에 새 잎이 돋아나기 시작했어요! 잎 끝이 마르지 않게 "
                    + "저녁마다 습도 관리를 위한 스프레이를 분무해주고 있습니다. 🌿"
            ),
            qaDraft(
                id: "local-1",
                name: "스투키",
                lastWateredOn: try? CalendarDate.parse("2026-05-12"),
                wateringIntervalDays: 10
            ),
            qaDraft(
                id: "local-2",
                name: "미니 선인장",
                lastWateredOn: try? CalendarDate.parse("2026-05-03"),
                wateringIntervalDays: 30
            ),
            qaDraft(
                id: "local-3",
                name: "아레카야자",
                lastWateredOn: try? CalendarDate.parse("2026-05-11"),
                wateringIntervalDays: 10
            ),
            qaDraft(
                id: "local-4",
                name: "스킨답서스",
                lastWateredOn: try? CalendarDate.parse("2026-05-14"),
                wateringIntervalDays: 10
            )
        ]
        finishInstallingQAFixture()
    }

    private func installCareVariantsQAFixture() {
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
        finishInstallingQAFixture()
    }

    private func finishInstallingQAFixture() {
        installQAFixtureIdentities()
        persist()
        guard let token = ProcessInfo.processInfo.environment[
            "QA_COLLECTION_FIXTURE_TOKEN"
        ] else {
            return
        }
        let fixture = ProcessInfo.processInfo.environment[
            "QA_COLLECTION_FIGMA_FIXTURE"
        ] == "1" ? "figma" : "standard"
        let presentation = ProcessInfo.processInfo.environment[
            "QA_COLLECTION_EMPTY"
        ] == "1" ? "empty" : "content"
        qaFixtureMountReceipt = [
            "account=\(accountID)",
            "token=\(token)",
            "fixture=\(fixture)",
            "presentation=\(presentation)",
            "plants=\(plants.count)"
        ].joined(separator: ";")
    }

    private func installQAFixtureIdentities() {
        weatherPlantIDs = plants.compactMap { plant in
            guard let contentID = plant.plantID else {
                return nil
            }
            return try? PersonalPlantID.parse("qa-\(contentID.rawValue)")
        }
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
        wateringIntervalDays: Int = 10,
        privateMemo: String? = nil
    ) -> PlantRegistrationDraft {
        PlantRegistrationDraft(
            plantID: try? PlantContentID.parse(id),
            displayName: name,
            representativePhoto: nil,
            lastWateredOn: lastWateredOn,
            wateringIntervalDays: wateringIntervalDays,
            registrationMethod: .manual,
            privateMemo: privateMemo
        )
    }
}
