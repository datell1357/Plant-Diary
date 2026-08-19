import Foundation
@testable import Planterior
import Testing

@MainActor
struct OperationalPrivacyTests {
    @Test
    func analyticsExportsOnlyAllowlistedFields() {
        let recorder = AnalyticsRecorder()
        recorder.record(.screenViewed(.settings))
        recorder.record(.action(.imageShared, .cancelled))

        #expect(recorder.exportedEvents.count == 2)
        #expect(
            recorder.exportedEvents
                .flatMap(\.keys)
                .allSatisfy(AnalyticsRecorder.allowedKeys.contains)
        )
        let export = String(describing: recorder.exportedEvents)
        for forbidden in [
            "raw_image", "exact_coordinate", "note", "auth_data",
            "push_token", "share_token", "private_url", "payload"
        ] {
            #expect(!export.contains(forbidden))
        }
    }

    @Test
    func redactorRemovesPrivateValues() {
        let source = """
        user@example.com https://private.example/path \
        abcdefghijklmnopqrstuvwxyz123456 37.56650, 126.97800
        """
        let redacted = SensitiveDataRedactor.redact(source)

        #expect(!redacted.contains("user@example.com"))
        #expect(!redacted.contains("private.example"))
        #expect(!redacted.contains("37.56650"))
        #expect(redacted.contains("[REDACTED]"))
    }

    @Test
    func retentionBoundaryPreservesRepresentativeAndRetriesFailures() {
        let now = Date(timeIntervalSince1970: 100_000)
        let at2359 = RetainedPhoto(
            id: "at-2359",
            createdAt: now.addingTimeInterval(-(23 * 60 * 60 + 59 * 60)),
            isRepresentative: false
        )
        let at2400 = RetainedPhoto(
            id: "at-2400",
            createdAt: now.addingTimeInterval(-24 * 60 * 60),
            isRepresentative: false
        )
        let representative = RetainedPhoto(
            id: "representative",
            createdAt: now.addingTimeInterval(-48 * 60 * 60),
            isRepresentative: true
        )
        let retry = PhotoRetentionCoordinator.cleanup(
            [at2359, at2400, representative],
            now: now
        ) { _ in
            throw CocoaError(.fileWriteUnknown)
        }

        #expect(retry == ["at-2400"])
    }

    @Test
    func appCheckRejectsMissingAndShortTokens() {
        #expect(!AppCheckPolicy.accepts(token: nil))
        #expect(!AppCheckPolicy.accepts(token: "short"))
        #expect(
            AppCheckPolicy.accepts(
                token: String(repeating: "a", count: 32)
            )
        )
    }
}
