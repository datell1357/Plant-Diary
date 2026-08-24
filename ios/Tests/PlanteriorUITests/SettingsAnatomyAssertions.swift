import XCTest

@MainActor
extension SettingsDeletionUITests {
    func assertReferenceRootAnatomy(in app: XCUIApplication) {
        let wateringToggle = app.switches["settings.alerts.watering-enabled"]
        let weatherToggle = app.switches["settings.alerts.weather-enabled"]
        let quietHours = app.buttons["settings.quiet-hours.open"]
        let region = app.buttons["settings.region.open"]

        for toggle in [wateringToggle, weatherToggle] {
            XCTAssertTrue(toggle.exists)
            XCTAssertGreaterThanOrEqual(toggle.frame.width, 43.5)
            XCTAssertGreaterThanOrEqual(toggle.frame.height, 43.5)
        }
        XCTAssertEqual(quietHours.frame.height, 52, accuracy: 1)
        XCTAssertEqual(region.frame.height, 52, accuracy: 1)
        XCTAssertLessThan(quietHours.frame.maxY, region.frame.minY)
    }

    func assertReferenceRegionAnatomy(in app: XCUIApplication) {
        let firstRow = app.buttons["weather.region-result.manual-seoul"]
        let secondRow = app.buttons["weather.region-result.manual-busan"]

        XCTAssertEqual(
            firstRow.frame,
            CGRect(x: 20, y: 299, width: 362, height: 52),
            accuracy: 1
        )
        XCTAssertEqual(
            secondRow.frame,
            CGRect(x: 20, y: 351, width: 362, height: 52),
            accuracy: 1
        )
    }

    func assertReferenceQuietHoursAnatomy(in app: XCUIApplication) {
        let toggle = app.switches["quiet-hours.enabled"]
        let start = app.datePickers["quiet-hours.start"]
        let end = app.datePickers["quiet-hours.end"]
        let warning = app.otherElements["quiet-hours.warning"]

        XCTAssertTrue(toggle.exists)
        XCTAssertGreaterThanOrEqual(toggle.frame.width, 44)
        XCTAssertGreaterThanOrEqual(toggle.frame.height, 44)
        for picker in [start, end] {
            XCTAssertTrue(picker.exists)
            XCTAssertTrue(picker.isHittable)
        }
        XCTAssertLessThanOrEqual(start.frame.maxY, end.frame.minY)
        XCTAssertEqual(
            warning.frame,
            CGRect(x: 20, y: 397, width: 362, height: 80),
            accuracy: 1
        )
    }
}

private func XCTAssertEqual(
    _ actual: CGRect,
    _ expected: CGRect,
    accuracy: CGFloat,
    file: StaticString = #filePath,
    line: UInt = #line
) {
    XCTAssertEqual(actual.minX, expected.minX, accuracy: accuracy, file: file, line: line)
    XCTAssertEqual(actual.minY, expected.minY, accuracy: accuracy, file: file, line: line)
    XCTAssertEqual(actual.width, expected.width, accuracy: accuracy, file: file, line: line)
    XCTAssertEqual(actual.height, expected.height, accuracy: accuracy, file: file, line: line)
}
