import Foundation
import PlanteriorDomain

extension LocalPlantCollectionStore {
    func movePlant(from sourceIndex: Int, to destinationIndex: Int) {
        guard
            plants.indices.contains(sourceIndex),
            weatherPlantIDs.indices.contains(sourceIndex),
            destinationIndex >= 0,
            destinationIndex < plants.count
        else {
            return
        }
        let plant = plants.remove(at: sourceIndex)
        let plantID = weatherPlantIDs.remove(at: sourceIndex)
        plants.insert(plant, at: destinationIndex)
        weatherPlantIDs.insert(plantID, at: destinationIndex)
        persist()
    }

    func healthNotes(at index: Int) -> [String] {
        guard let identity = healthNoteIdentity(at: index) else { return [] }
        return healthNotesByPlantID[identity] ?? []
    }

    func addHealthNote(_ note: String, at index: Int) {
        guard let identity = healthNoteIdentity(at: index) else { return }
        healthNotesByPlantID[identity, default: []].append(note)
        persist()
    }

    func resetPlantIdentities() {
        weatherPlantIDs.removeAll()
    }

    func reconcilePlantIdentities() {
        while weatherPlantIDs.count < plants.count {
            let rawValue = "local_\(UUID().uuidString)"
            if let plantID = try? PersonalPlantID.parse(rawValue) {
                weatherPlantIDs.append(plantID)
            }
        }
        if weatherPlantIDs.count > plants.count {
            weatherPlantIDs = Array(weatherPlantIDs.prefix(plants.count))
        }
    }

    func restoreHealthNotes() {
        guard let data = defaults.data(forKey: notesKey) else { return }
        if let legacyNotes = try? JSONDecoder().decode([Int: [String]].self, from: data) {
            healthNotesByPlantID = legacyNotes.reduce(into: [:]) { migrated, entry in
                guard let identity = healthNoteIdentity(at: entry.key) else { return }
                migrated[identity] = entry.value
            }
            return
        }
        healthNotesByPlantID = (try? JSONDecoder().decode(
            [String: [String]].self,
            from: data
        )) ?? [:]
    }

    private func healthNoteIdentity(at index: Int) -> String? {
        weatherPlantID(at: index)?.rawValue
    }
}
