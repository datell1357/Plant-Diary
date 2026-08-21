import Foundation
import PlanteriorData
import PlanteriorDomain

struct PlantCareEdits {
    let displayName: String
    let location: String?
    let note: String?
    let lastWateredOn: CalendarDate?
    let wateringIntervalDays: Int
}

@MainActor
final class LocalPlantCollectionStore: ObservableObject {
    static let shared = LocalPlantCollectionStore()
    @Published var plants: [PlantRegistrationDraft] = []
    @Published var weatherPlantIDs: [PersonalPlantID] = []
    @Published private(set) var completedPlantIDs: Set<PersonalPlantID> = []
    @Published var healthNotesByPlantID: [String: [String]] = [:]
    @Published private(set) var scrollAnchor: Int?
    @Published var snapshotState = CollectionViewState.content
    @Published private(set) var saveError: String?
    private var accountID = "signed-out"
    let defaults: UserDefaults
    let notificationSchedules: LocalNotificationScheduleStore
    var plantsKey: String {
        "collection.\(accountID).plants"
    }

    var notesKey: String {
        "collection.\(accountID).health-notes"
    }

    var weatherPlantIDsKey: String {
        "collection.\(accountID).weather-plant-ids"
    }

    init(
        defaults: UserDefaults = .standard,
        notificationSchedules: LocalNotificationScheduleStore = .shared
    ) {
        self.defaults = defaults
        self.notificationSchedules = notificationSchedules
        resetPersistenceForQA()
        restore()
    }

    func mount(accountID: String?) {
        let mountedAccountID = accountID ?? "signed-out"
        guard self.accountID != mountedAccountID else {
            return
        }
        self.accountID = mountedAccountID
        completedPlantIDs = []
        resetPersistenceForQA()
        restore()
    }

    func markWateringCompleted(for plantID: PersonalPlantID) {
        completedPlantIDs.insert(plantID)
    }

    private func restore() {
        plants = []
        weatherPlantIDs = []
        healthNotesByPlantID = [:]
        scrollAnchor = nil
        if let data = defaults.data(forKey: plantsKey) {
            plants = (try? JSONDecoder().decode([PlantRegistrationDraft].self, from: data)) ?? []
        }
        let rawIDs = defaults.stringArray(forKey: weatherPlantIDsKey) ?? []
        weatherPlantIDs = rawIDs.compactMap { try? PersonalPlantID.parse($0) }
        reconcilePlantIdentities()
        restoreHealthNotes()
        persist()
        restoreScrollAnchor()
    }

    func save(_ draft: PlantRegistrationDraft) {
        plants.append(draft)
        let rawValue = "local_\(UUID().uuidString)"
        if let plantID = try? PersonalPlantID.parse(rawValue) {
            weatherPlantIDs.append(plantID)
        }
        persist()
    }

    func contains(_ plantID: String) -> Bool {
        plants.contains { $0.plantID?.rawValue == plantID }
    }

    func existingName(for plantID: String) -> String? {
        plants.first { $0.plantID?.rawValue == plantID }?.displayName
    }

    func remove(at index: Int) {
        guard plants.indices.contains(index), weatherPlantIDs.indices.contains(index) else {
            return
        }
        let removedID = weatherPlantIDs[index]
        plants.remove(at: index)
        weatherPlantIDs.remove(at: index)
        completedPlantIDs.remove(removedID)
        notificationSchedules.cancel(for: removedID)
        healthNotesByPlantID[removedID.rawValue] = nil
        defaults.set(
            true,
            forKey: "collection.\(accountID).tombstone.\(removedID.rawValue)"
        )
        persist()
    }

    func update(
        at index: Int,
        edits: PlantCareEdits,
        today: CalendarDate
    ) throws {
        guard plants.indices.contains(index) else {
            return
        }
        let coordinator = try PlantCareDetailCoordinator(
            plant: personalPlant(at: index)
        )
        try coordinator.validateEdits(
            location: edits.location ?? "",
            privateMemo: edits.note ?? ""
        )
        if let lastWateredOn = edits.lastWateredOn {
            var watering = WateringScheduleCoordinator(today: today)
            let plantID = try personalPlantID(at: index)
            try watering.setSchedule(
                plantID: plantID,
                lastWateredDate: lastWateredOn,
                intervalDays: edits.wateringIntervalDays
            )
        }
        #if DEBUG
            if ProcessInfo.processInfo.environment["QA_COLLECTION_SAVE_FAILURE"] == "1" {
                saveError = "변경사항을 저장하지 못했어요."
                throw CollectionSaveError.failed
            }
        #endif
        let current = plants[index]
        plants[index] = PlantRegistrationDraft(
            plantID: current.plantID,
            displayName: edits.displayName,
            representativePhoto: current.representativePhoto,
            lastWateredOn: edits.lastWateredOn,
            wateringIntervalDays: edits.wateringIntervalDays,
            registrationMethod: current.registrationMethod,
            location: edits.location,
            privateMemo: edits.note
        )
        persist()
        saveError = nil
    }

    func presentationIdentity(at index: Int) -> String? {
        guard plants.indices.contains(index) else { return nil }
        return plants[index].plantID?.rawValue ?? weatherPlantID(at: index)?.rawValue
    }

    func rememberScrollAnchor(_ index: Int) {
        scrollAnchor = index
        defaults.set(index, forKey: "collection.\(accountID).scroll-anchor")
    }

    func restoreScrollAnchor() {
        guard defaults.object(
            forKey: "collection.\(accountID).scroll-anchor"
        ) != nil else {
            return
        }
        scrollAnchor = defaults.integer(forKey: "collection.\(accountID).scroll-anchor")
    }

    func persist() {
        defaults.set(
            try? JSONEncoder().encode(plants),
            forKey: plantsKey
        )
        defaults.set(
            try? JSONEncoder().encode(healthNotesByPlantID),
            forKey: notesKey
        )
        defaults.set(weatherPlantIDs.map(\.rawValue), forKey: weatherPlantIDsKey)
    }
}
