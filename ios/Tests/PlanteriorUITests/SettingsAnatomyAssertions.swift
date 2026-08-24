import XCTest

@MainActor
extension SettingsDeletionUITests {
    func assertReferenceRootAnatomy(in app: XCUIApplication) {
        let wateringRow = app.descendants(matching: .any)[
            "settings.alerts.watering-enabled.row"
        ]
        let wateringToggle = app.descendants(matching: .any)[
            "settings.alerts.watering-enabled.visual"
        ]
        let weatherToggle = app.descendants(matching: .any)[
            "settings.alerts.weather-enabled.visual"
        ]
        let locationGlyph = app.descendants(matching: .any)[
            "settings.location-glyph"
        ]

        XCTAssertTrue(wateringRow.exists)
        assertFrame(
            wateringRow.frame,
            CGRect(x: 16, y: 244, width: 370, height: 52)
        )
        XCTAssertTrue(wateringToggle.exists)
        assertFrame(
            wateringToggle.frame,
            CGRect(x: 326, y: 258, width: 44, height: 24)
        )
        XCTAssertTrue(weatherToggle.exists)
        assertFrame(
            weatherToggle.frame,
            CGRect(x: 326, y: 310, width: 44, height: 24)
        )
        XCTAssertEqual(
            app.buttons["settings.quiet-hours.open"].frame.height,
            52,
            accuracy: 1
        )
        XCTAssertEqual(
            app.buttons["settings.region.open"].frame.height,
            52,
            accuracy: 1
        )
        XCTAssertTrue(locationGlyph.exists)
        assertFrame(
            locationGlyph.frame,
            CGRect(x: 41, y: 461, width: 14, height: 18)
        )
    }

    func assertReferenceRegionAnatomy(in app: XCUIApplication) {
        let locationGlyph = app.descendants(matching: .any)[
            "region-settings.location-glyph"
        ]
        let firstRow = app.buttons["weather.region-result.manual-seoul"]
        let secondRow = app.buttons["weather.region-result.manual-busan"]

        XCTAssertTrue(locationGlyph.exists)
        assertFrame(
            locationGlyph.frame,
            CGRect(x: 38, y: 199, width: 12, height: 15)
        )
        assertFrame(
            firstRow.frame,
            CGRect(x: 20, y: 299, width: 362, height: 52)
        )
        assertFrame(
            secondRow.frame,
            CGRect(x: 20, y: 351, width: 362, height: 52)
        )
    }

    func assertReferenceQuietHoursAnatomy(in app: XCUIApplication) {
        let toggleRow = app.descendants(matching: .any)["quiet-hours.enabled.row"]
        let toggleVisual = app.descendants(matching: .any)[
            "quiet-hours.enabled.visual"
        ]
        let startRow = app.descendants(matching: .any)["quiet-hours.start.row"]
        let endRow = app.descendants(matching: .any)["quiet-hours.end.row"]
        let warning = app.otherElements["quiet-hours.warning"]
        let warningIcon = app.descendants(matching: .any)["quiet-hours.warning-icon"]
        let warningCopy = app.staticTexts["quiet-hours.warning-copy"]
        let intactWeather = app.descendants(matching: .any)[
            "quiet-hours.warning-copy.intact-weather"
        ]
        let expectedCopy =
            "태풍, 한파, 폭염 등 식물 생존에 직접적인 영향을 미치는\n"
                + "기상 특보 및 재난 알림은 시간 설정과 관계없이\n"
                + "즉시 발송됩니다."

        XCTAssertTrue(toggleRow.exists)
        assertFrame(
            toggleRow.frame,
            CGRect(x: 20, y: 116, width: 362, height: 56)
        )
        XCTAssertTrue(toggleVisual.exists)
        assertFrame(
            toggleVisual.frame,
            CGRect(x: 322, y: 132, width: 44, height: 24)
        )
        assertFrame(
            startRow.frame,
            CGRect(x: 20, y: 272, width: 362, height: 52)
        )
        assertFrame(
            endRow.frame,
            CGRect(x: 20, y: 324, width: 362, height: 52)
        )
        assertFrame(
            warning.frame,
            CGRect(x: 20, y: 397, width: 362, height: 80)
        )
        XCTAssertTrue(warningIcon.exists)
        assertFrame(
            warningIcon.frame,
            CGRect(x: 39, y: 414, width: 11, height: 19)
        )
        XCTAssertTrue(warningCopy.exists)
        XCTAssertEqual(warningCopy.label, expectedCopy)
        XCTAssertEqual(warningCopy.frame.minX, 62, accuracy: 1)
        XCTAssertTrue(intactWeather.exists)
        XCTAssertTrue(warningCopy.label.contains("기상"))
        XCTAssertFalse(warningCopy.label.contains("기 상"))
    }
}

private func assertFrame(
    _ actual: CGRect,
    _ expected: CGRect,
    accuracy: CGFloat = 1
) {
    XCTAssertEqual(actual.minX, expected.minX, accuracy: accuracy)
    XCTAssertEqual(actual.minY, expected.minY, accuracy: accuracy)
    XCTAssertEqual(actual.width, expected.width, accuracy: accuracy)
    XCTAssertEqual(actual.height, expected.height, accuracy: accuracy)
}
