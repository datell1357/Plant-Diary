import PlanteriorData
import PlanteriorDomain

extension LocalPlantCollectionStore {
    func wateringIntervalDays(at index: Int) -> Int {
        guard plants.indices.contains(index) else {
            return 10
        }
        return plants[index].wateringIntervalDays ?? 10
    }

    func wateringStatus(
        at index: Int,
        lastWateredOn: CalendarDate?,
        today: CalendarDate,
        intervalDays: Int
    ) -> WateringScheduleStatus {
        guard
            plants.indices.contains(index),
            let lastWateredOn
        else {
            return .unavailable
        }
        var coordinator = WateringScheduleCoordinator(today: today)
        do {
            let plantID = try personalPlantID(at: index)
            try coordinator.setSchedule(
                plantID: plantID,
                lastWateredDate: lastWateredOn,
                intervalDays: intervalDays
            )
            return coordinator.status(for: plantID)
        } catch {
            return .unavailable
        }
    }

    func recordWateredToday(
        at index: Int,
        today: CalendarDate,
        intervalDays: Int
    ) throws -> WateringCompletionResult {
        guard
            plants.indices.contains(index),
            let lastWateredOn = plants[index].lastWateredOn
        else {
            throw WateringScheduleError.scheduleUnavailable
        }
        let plantID = try personalPlantID(at: index)
        var coordinator = WateringScheduleCoordinator(today: today)
        try coordinator.setSchedule(
            plantID: plantID,
            lastWateredDate: lastWateredOn,
            intervalDays: intervalDays
        )
        let result = try coordinator.recordWateredToday(for: plantID)
        markWateringCompleted(for: plantID)
        let current = plants[index]
        plants[index] = PlantRegistrationDraft(
            plantID: current.plantID,
            displayName: current.displayName,
            representativePhoto: current.representativePhoto,
            lastWateredOn: today,
            wateringIntervalDays: intervalDays,
            registrationMethod: current.registrationMethod,
            location: current.location,
            privateMemo: current.privateMemo
        )
        notificationSchedules.cancel(for: plantID)
        persist()
        return result
    }

    func setWateringBaseline(
        at index: Int,
        today: CalendarDate,
        intervalDays: Int
    ) throws {
        guard plants.indices.contains(index) else {
            throw WateringScheduleError.scheduleUnavailable
        }
        var coordinator = WateringScheduleCoordinator(today: today)
        try coordinator.setSchedule(
            plantID: personalPlantID(at: index),
            lastWateredDate: today,
            intervalDays: intervalDays
        )
        let current = plants[index]
        plants[index] = PlantRegistrationDraft(
            plantID: current.plantID,
            displayName: current.displayName,
            representativePhoto: current.representativePhoto,
            lastWateredOn: today,
            wateringIntervalDays: intervalDays,
            registrationMethod: current.registrationMethod,
            location: current.location,
            privateMemo: current.privateMemo
        )
        persist()
    }

    func personalPlantID(at index: Int) throws -> PersonalPlantID {
        try PersonalPlantID.parse("local-\(index)")
    }
}
