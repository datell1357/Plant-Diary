import XCTest

@MainActor
final class OperationalAccessibilityUITests: XCTestCase {
    func testSettingsPassesStrictAccessibilityAudit() throws {
        let app = XCUIApplication()
        app.launchEnvironment["QA_SKIP_ONBOARDING"] = "1"
        app.launchEnvironment["QA_AUTHENTICATED"] = "1"
        app.launchEnvironment["QA_SETTINGS_SIZE_CATEGORY"] = "AX5"
        app.launchEnvironment["QA_REDUCE_MOTION"] = "1"
        app.launch()
        let settings = app.buttons["tab.settings"]
        XCTAssertTrue(settings.waitForExistence(timeout: 10))
        settings.tap()
        XCTAssertTrue(
            app.scrollViews["settings.screen"].waitForExistence(timeout: 5)
        )
        let privacy = app.buttons["settings.privacy"]
        XCTAssertTrue(privacy.waitForExistence(timeout: 5))
        privacy.tap()
        XCTAssertTrue(
            app.scrollViews["privacy.screen"].waitForExistence(timeout: 5)
        )
        try app.performAccessibilityAudit(
            for: [
                .contrast,
                .elementDetection,
                .hitRegion,
                .sufficientElementDescription,
                .textClipped,
                .trait
            ]
        )
    }
}
