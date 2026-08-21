import Foundation
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

    static func asset(for identity: String) -> FigmaAsset {
        switch identity {
        case "local-0": .collectionPlant01
        case "local-1": .collectionPlant02
        case "local-2": .collectionPlant03
        case "local-3": .collectionPlant04
        case "local-4": .collectionPlant05
        default: plantAssets[stableIndex(for: identity)]
        }
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

    static let guideMetrics = [
        PlantGuideMetric(
            id: "water",
            icon: "drop.fill",
            title: "물 주기",
            value: "7~10일 간격",
            hint: "흙이 마른 뒤 충분히"
        ),
        PlantGuideMetric(
            id: "light",
            icon: "sun.max.fill",
            title: "빛",
            value: "밝은 간접광",
            hint: "강한 직사광선은 피하기"
        ),
        PlantGuideMetric(
            id: "temperature",
            icon: "thermometer.medium",
            title: "온도",
            value: "18~27°C",
            hint: "급격한 온도 변화 주의"
        ),
        PlantGuideMetric(
            id: "humidity",
            icon: "humidity.fill",
            title: "습도",
            value: "60% 이상",
            hint: "통풍도 함께 확인"
        )
    ]

    private static func stableIndex(for value: String) -> Int {
        let hash = value.utf8.reduce(UInt64(1_469_598_103_934_665_603)) {
            ($0 ^ UInt64($1)) &* 1_099_511_628_211
        }
        return Int(hash % UInt64(plantAssets.count))
    }
}
