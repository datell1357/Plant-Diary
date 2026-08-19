import Foundation

enum SensitiveDataRedactor {
    static func redact(_ value: String) -> String {
        var redacted = value
        let patterns = [
            #"[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}"#,
            #"https?://\S+"#,
            #"[A-Za-z0-9_-]{32,}"#,
            #"-?\d{1,3}\.\d{4,},\s*-?\d{1,3}\.\d{4,}"#
        ]
        for pattern in patterns {
            redacted = redacted.replacingOccurrences(
                of: pattern,
                with: "[REDACTED]",
                options: .regularExpression
            )
        }
        return redacted
    }
}
