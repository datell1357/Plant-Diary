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
        #expect(try Revision.parse(0).next() == Revision.parse(1))
        #expect(throws: DomainValidationError.revisionOverflow) {
            try Revision.parse(UInt64.max)
        }
    }

    @Test
    func validatedWrappersEncodeAsScalarsAndValidateDecode() throws {
        let encoder = JSONEncoder()
        let decoder = JSONDecoder()
        let accountID = try AccountID.parse("fixture-account")
        let encodedID = try #require(String(data: encoder.encode(accountID), encoding: .utf8))
        #expect(encodedID == #""fixture-account""#)
        let decodedID = try decoder.decode(
            AccountID.self,
            from: Data(#""fixture-account""#.utf8)
        )
        #expect(decodedID == accountID)
        #expect(throws: DomainValidationError.invalidOpaqueID) {
            try decoder.decode(AccountID.self, from: Data(#""../bad""#.utf8))
        }
        let encodedRevision = try #require(
            String(data: encoder.encode(Revision.parse(7)), encoding: .utf8)
        )
        #expect(encodedRevision == "7")
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

    @Test
    func miniHomePlacementRejectsMalformedDecodedTarget() throws {
        // Given
        let malformed = try JSONSerialization.data(withJSONObject: [
            "id": "fixture-placement",
            "plantID": NSNull(),
            "itemID": NSNull(),
            "normalizedX": 0.5,
            "normalizedY": 0.5,
            "zIndex": 0
        ])

        // When / Then
        #expect(throws: MiniHomePlacementError.invalidTarget) {
            try JSONDecoder().decode(MiniHomePlacement.self, from: malformed)
        }
    }

    @Test(arguments: ["00:00", "09:00", "00:00:00", "23:59:59"])
    func localTimeAcceptsBoundaries(_ value: String) throws {
        let expected = value.count == 5 ? "\(value):00" : value
        #expect(try LocalTime.parse(value).rawValue == expected)
    }

    @Test(arguments: ["24:00:00", "09:60:00"])
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
        try assertWireEnum(RegistrationMethod.self, encoder: encoder, decoder: decoder)
        try assertWireEnum(PublicationState.self, encoder: encoder, decoder: decoder)
        try assertWireEnum(RiskType.self, encoder: encoder, decoder: decoder)
        try assertWireEnum(ItemCategory.self, encoder: encoder, decoder: decoder)
        try assertWireEnum(DeletionStatus.self, encoder: encoder, decoder: decoder)
        try assertWireEnum(DeliveryStatus.self, encoder: encoder, decoder: decoder)
        try assertWireEnum(ConsentType.self, encoder: encoder, decoder: decoder)
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

    @Test
    func entityFixtureRoundTripsAndMalformedFieldConstructsNothing() throws {
        let json = Data(
            """
            {
              "id":"fixture-plant",
              "displayName":"몬스테라",
              "contentID":"fixture-content",
              "registrationMethod":"IDENTIFIED",
              "representativePhotoPath":null,
              "location":"거실",
              "note":null,
              "lastWateredDate":"2026-08-12",
              "revision":1,
              "updatedAt":"2026-08-12T00:00:00Z"
            }
            """.utf8
        )
        let decoder = JSONDecoder()
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.sortedKeys]
        let plant = try decoder.decode(PersonalPlant.self, from: json)
        #expect(try decoder.decode(PersonalPlant.self, from: encoder.encode(plant)) == plant)
        let jsonString = try #require(String(data: json, encoding: .utf8))
        let malformed = Data(
            jsonString.replacingOccurrences(of: "2026-08-12", with: "2026-02-30")
                .utf8
        )
        #expect(throws: DomainValidationError.invalidCalendarDate) {
            try decoder.decode(PersonalPlant.self, from: malformed)
        }
    }
}

private func assertWireEnum<Value: WireEnum & Equatable>(
    _ type: Value.Type,
    encoder: JSONEncoder,
    decoder: JSONDecoder
) throws {
    for value in Value.allCases {
        #expect(try decoder.decode(Value.self, from: encoder.encode(value)) == value)
    }
    #expect(throws: DomainValidationError.unknownEnum(
        type: String(describing: Value.self),
        value: "UNKNOWN"
    )) {
        try decoder.decode(Value.self, from: Data(#""UNKNOWN""#.utf8))
    }
}
