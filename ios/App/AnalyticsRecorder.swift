import Foundation

@MainActor
protocol AnalyticsRecording {
    func record(_ event: AnalyticsEvent)
}

@MainActor
final class AnalyticsRecorder: AnalyticsRecording {
    static let shared = AnalyticsRecorder()
    private(set) var exportedEvents: [[String: String]] = []

    func record(_ event: AnalyticsEvent) {
        let exported = event.export
        guard exported.keys.allSatisfy(Self.allowedKeys.contains) else {
            return
        }
        exportedEvents.append(
            exported.mapValues(SensitiveDataRedactor.redact)
        )
    }

    static let allowedKeys: Set<String> = [
        "event", "screen", "action", "outcome"
    ]
}
