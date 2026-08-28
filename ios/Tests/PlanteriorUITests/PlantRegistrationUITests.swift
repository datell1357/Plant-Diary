import XCTest

@MainActor
final class PlantRegistrationUITests: XCTestCase {
    func testRegistrationPersistsLocalGregorianWateringDate() {
        let app = XCUIApplication()
        app.launchEnvironment["QA_SKIP_ONBOARDING"] = "1"
        app.launchEnvironment["QA_RESET_COLLECTION"] = "1"
        app.launchEnvironment["QA_INITIAL_TAB"] = "collection"
        app.launchEnvironment["QA_MANUAL_REGISTRATION"] = "1"
        app.launchEnvironment["QA_REGISTRATION_LAST_WATERED_INSTANT"] =
            "2026-08-10T15:30:00Z"
        app.launchEnvironment["QA_WATERING_TODAY"] = "2026-08-11"
        app.launchEnvironment["TZ"] = "Asia/Seoul"
        app.launch()

        XCTAssertTrue(app.navigationBars["식물 등록"].waitForExistence(timeout: 5))
        assertSinglePersistentTabBar(in: app, selected: "tab.collection")
        let name = app.textFields["registration.name"]
        name.tap()
        name.typeText("몬스테라")
        app.keyboards.buttons["Return"].tap()
        XCTAssertTrue(app.keyboards.firstMatch.waitForNonExistence(timeout: 5))
        let submit = app.buttons["registration.submit"]
        XCTAssertTrue(submit.isEnabled)
        XCTAssertTrue(submit.isHittable)
        submit.tap()
        XCTAssertTrue(app.staticTexts["registration.saved"].waitForExistence(timeout: 5))
        let savedDate = app.staticTexts["registration.saved.last-watered"]
        XCTAssertTrue(savedDate.waitForExistence(timeout: 5))
        XCTAssertEqual(savedDate.label, "2026-08-11")
    }
}
