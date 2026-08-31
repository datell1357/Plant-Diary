import Foundation
import PlanteriorData
import PlanteriorDesignSystem
import PlanteriorDomain
import SwiftUI

extension PlantCareDetailView {
    var calendarDate: CalendarDate? {
        lastWateredOn.flatMap(calendarDate)
    }

    var todayCalendarDate: CalendarDate? {
        #if DEBUG
            let date = ProcessInfo.processInfo.environment["QA_WATERING_TODAY"]
                .flatMap { try? CalendarDate.parse($0) }
            if let date {
                return date
            }
        #endif
        return try? plantCalendar.calendarDate(from: Date())
    }

    var todayDate: Date {
        guard let todayCalendarDate else {
            return Date()
        }
        return plantCalendar.date(from: todayCalendarDate) ?? Date()
    }

    func calendarDate(_ date: Date) -> CalendarDate? {
        try? plantCalendar.calendarDate(from: date)
    }

    func date(_ calendarDate: CalendarDate) -> Date? {
        plantCalendar.date(from: calendarDate)
    }

    func loadPlant() {
        guard collection.plants.indices.contains(index) else { return }
        let plant = collection.plants[index]
        nickname = plant.displayName
        location = plant.location ?? ""
        privateMemo = plant.privateMemo ?? ""
        notes = collection.healthNotes(at: index)
        wateringIntervalDays = collection.wateringIntervalDays(at: index)
        if let plantID = collection.weatherPlantID(at: index) {
            weatherAlertsEnabled = LocalWeatherAlertStore.shared.plantEnabled(for: plantID)
        }
        lastWateredOn = plant.lastWateredOn.flatMap(date)
        #if DEBUG
            let value = ProcessInfo.processInfo.environment["QA_WATERING_DRAFT_DATE"]
            if let value, let draftDate = try? CalendarDate.parse(value) {
                lastWateredOn = date(draftDate)
            }
        #endif
    }
}

struct PlantGuideMetric: Identifiable {
    let id: String
    let icon: String
    let title: String
    let value: String
    let hint: String
}

enum PlantCarePresentation {
    private static let plantAssets: [FigmaAsset] = [
        .collectionPlant01,
        .collectionPlant02,
        .collectionPlant03,
        .collectionPlant04,
        .collectionPlant05
    ]
    private static let defaultPlantAssets: [String: FigmaAsset] = [
        "local-0": .collectionPlant01,
        "local-1": .collectionPlant02,
        "local-2": .collectionPlant03,
        "local-3": .collectionPlant04,
        "local-4": .collectionPlant05
    ]
    private static let figmaPlantAssets: [String: FigmaAsset] = [
        "local-0": .collectionPlant05,
        "local-1": .collectionPlant03,
        "local-2": .collectionPlant01,
        "local-3": .collectionPlant04,
        "local-4": .collectionPlant02
    ]

    static func asset(for identity: String) -> FigmaAsset {
        #if DEBUG
            if ProcessInfo.processInfo.environment[
                "QA_COLLECTION_FIGMA_FIXTURE"
            ] == "1", let asset = figmaPlantAssets[identity] {
                return asset
            }
        #endif
        return defaultPlantAssets[identity]
            ?? plantAssets[stableIndex(for: identity)]
    }

    static func collectionName(for identity: String, fallback: String) -> String {
        #if DEBUG
            guard ProcessInfo.processInfo.environment[
                "QA_COLLECTION_FIGMA_FIXTURE"
            ] == "1" else {
                return fallback
            }
            switch identity {
            case "local-0": return "몬몬이 (몬스테라)"
            case "local-1": return "뾰족이 (스투키)"
            case "local-2": return "초록이 (미니 선인장)"
            case "local-3": return "야자 (아레카야자)"
            case "local-4": return "스킨이 (스킨답서스)"
            default: return fallback
            }
        #else
            return fallback
        #endif
    }

    static func species(for displayName: String) -> String {
        if displayName.localizedCaseInsensitiveContains("몬스테라") {
            return "Monstera deliciosa"
        }
        if displayName.localizedCaseInsensitiveContains("스킨답서스") {
            return "Epipremnum aureum"
        }
        return "등록한 반려식물"
    }

    static func species(for plant: PlantRegistrationDraft) -> String {
        plant.scientificName ?? species(for: plant.displayName)
    }

    static func careProfile(
        for plant: PlantRegistrationDraft
    ) -> DomesticPlantCareProfile? {
        DomesticPlantCareCatalog.profile(scientificName: plant.scientificName)
    }

    static let guideMetrics = DomesticPlantCareCatalog.monstera.metrics

    private static func stableIndex(for value: String) -> Int {
        let hash = value.utf8.reduce(UInt64(1_469_598_103_934_665_603)) {
            ($0 ^ UInt64($1)) &* 1_099_511_628_211
        }
        return Int(hash % UInt64(plantAssets.count))
    }
}
