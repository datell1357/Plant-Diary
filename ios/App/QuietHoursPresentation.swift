import Foundation
import PlanteriorDomain

@MainActor
enum QuietHoursPresentation {
    static func date(from time: LocalTime) -> Date {
        let parts = time.rawValue.split(separator: ":").compactMap { Int($0) }
        return Calendar.current.date(
            from: DateComponents(
                year: 2001,
                month: 1,
                day: 1,
                hour: parts.first ?? 0,
                minute: parts.count > 1 ? parts[1] : 0
            )
        ) ?? Date(timeIntervalSinceReferenceDate: 0)
    }

    static func localTime(from date: Date) -> LocalTime? {
        let components = Calendar.current.dateComponents(
            [.hour, .minute],
            from: date
        )
        guard let hour = components.hour, let minute = components.minute else {
            return nil
        }
        return try? LocalTime.parse(
            String(format: "%02d:%02d", hour, minute)
        )
    }

    static func summary(_ preference: QuietHoursPreference) -> String {
        guard preference.enabled else {
            return "없음"
        }
        return "\(short(preference.start))–\(short(preference.end))"
    }

    private static func short(_ time: LocalTime) -> String {
        date(from: time).formatted(date: .omitted, time: .shortened)
    }
}
