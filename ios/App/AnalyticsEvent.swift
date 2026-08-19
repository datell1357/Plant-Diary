enum AnalyticsScreen: String, CaseIterable {
    case home
    case collection
    case inventory
    case settings
    case miniHome = "mini_home"
}

enum AnalyticsAction: String, CaseIterable {
    case plantRegistered = "plant_registered"
    case wateringRecorded = "watering_recorded"
    case miniHomeSaved = "mini_home_saved"
    case imageShared = "image_shared"
    case deletionRequested = "deletion_requested"
}

enum AnalyticsOutcome: String {
    case succeeded
    case failed
    case cancelled
    case unavailable
}

enum AnalyticsEvent {
    case screenViewed(AnalyticsScreen)
    case action(AnalyticsAction, AnalyticsOutcome)

    var export: [String: String] {
        switch self {
        case let .screenViewed(screen):
            ["event": "screen_viewed", "screen": screen.rawValue]
        case let .action(action, outcome):
            [
                "event": "action",
                "action": action.rawValue,
                "outcome": outcome.rawValue
            ]
        }
    }
}
