public enum ProgressionEventKind: String, Codable, CaseIterable, Sendable {
    case registration = "REGISTRATION"
    case watering = "WATERING"
    case miniHomeSave = "MINI_HOME_SAVE"
    case sharing = "SHARING"
}
