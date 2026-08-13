import Foundation
@testable import PlanteriorDomain
import Testing

struct DomainContractTests {
    @Test(arguments: ["a", "fixture-monstera_01", String(repeating: "x", count: 128)])
    func opaqueIDAcceptsPathSafeValues(_ value: String) throws {
        #expect(try AccountID.parse(value).rawValue == value)
    }

    @Test(arguments: ["", "a/b", "with space", String(repeating: "x", count: 129)])
    func opaqueIDRejectsInvalidValues(_ value: String) {
        #expect(throws: DomainValidationError.invalidOpaqueID) {
            try AccountID.parse(value)
        }
    }

    @Test
    func revisionAdvancesAndDetectsOverflow() throws {
        #expect(try Revision(rawValue: 0).next() == Revision(rawValue: 1))
        #expect(throws: DomainValidationError.revisionOverflow) {
            try Revision(rawValue: UInt64.max).next()
        }
    }

    @Test(arguments: ["2024-02-29", "2026-08-12"])
    func calendarDateAcceptsRealGregorianDates(_ value: String) throws {
        #expect(try CalendarDate.parse(value).rawValue == value)
    }

    @Test(arguments: ["2023-02-29", "2026-13-01", "2026-8-12"])
    func calendarDateRejectsMalformedDates(_ value: String) {
        #expect(throws: DomainValidationError.invalidCalendarDate) {
            try CalendarDate.parse(value)
        }
    }

    @Test(arguments: ["00:00:00", "23:59:59"])
    func localTimeAcceptsBoundaries(_ value: String) throws {
        #expect(try LocalTime.parse(value).rawValue == value)
    }

    @Test(arguments: ["24:00:00", "09:60:00", "09:00"])
    func localTimeRejectsOutOfRangeValues(_ value: String) {
        #expect(throws: DomainValidationError.invalidLocalTime) {
            try LocalTime.parse(value)
        }
    }

    @Test
    func instantAndTimeZoneAreStrict() throws {
        #expect(
            try Instant.parse("2026-08-12T00:00:00Z").rawValue ==
                "2026-08-12T00:00:00Z"
        )
        #expect(try TimeZoneID.parse("Asia/Seoul").rawValue == "Asia/Seoul")
        #expect(throws: DomainValidationError.invalidInstant) {
            try Instant.parse("2026-08-12")
        }
        #expect(throws: DomainValidationError.invalidTimeZone) {
            try TimeZoneID.parse("Mars/Olympus")
        }
    }

    @Test
    func wireEnumsRoundTripAndRejectUnknownValues() throws {
        let encoder = JSONEncoder()
        let decoder = JSONDecoder()
        for value in RegistrationMethod.allCases {
            let decoded = try decoder.decode(
                RegistrationMethod.self,
                from: encoder.encode(value)
            )
            #expect(decoded == value)
        }
        #expect(throws: DecodingError.self) {
            try decoder.decode(RegistrationMethod.self, from: Data(#""UNKNOWN""#.utf8))
        }
    }

    @Test
    func mutationTransitionsPreserveDrafts() throws {
        let editing = MutationState<String>.editing("draft")
        let submitting = try editing.submit()
        #expect(submitting == .submitting("draft"))
        #expect(try submitting.queue() == .queued("draft"))
        #expect(try submitting.fail(.transient) == .failed("draft", .transient))
        #expect(throws: MutationTransitionError.invalidTransition) {
            try MutationState<String>.succeeded.submit()
        }
    }

    @Test
    func fixedClockReturnsInjectedInstant() throws {
        let instant = try Instant.parse("2026-08-12T00:00:00Z")
        #expect(FixedClock(instant: instant).now() == instant)
    }
}
