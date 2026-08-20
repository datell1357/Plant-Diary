import XCTest

extension AppLaunchUITests {
    func testAvailablePlantURLUsesRealCareDetail() {
        let app = XCUIApplication()
        app.launchEnvironment["QA_DEEP_LINK"] = "planterior://plant/local-0"
        app.launchEnvironment["QA_COLLECTION_FIXTURE"] = "1"
        app.launchEnvironment["QA_RESET_COLLECTION"] = "1"
        app.launchEnvironment["QA_AUTHENTICATED"] = "1"
        app.launchEnvironment["QA_SKIP_ONBOARDING"] = "1"
        app.launch()

        let nickname = app.textFields["plant.detail.nickname"]
        XCTAssertTrue(nickname.waitForExistence(timeout: 5))
        XCTAssertEqual(nickname.value as? String, "몬스테라")
        XCTAssertFalse(app.otherElements["plant.detail"].exists)
    }

    func testUnavailableURLsFallBackWithoutMetadata() {
        let hostileApp = XCUIApplication()
        hostileApp.launchEnvironment["QA_DEEP_LINK"] =
            "https://evil.test/plant/private-plant"
        hostileApp.launchEnvironment["QA_SKIP_ONBOARDING"] = "1"
        hostileApp.launch()
        XCTAssertTrue(
            hostileApp.otherElements["route.unavailable"]
                .waitForExistence(timeout: 5)
        )
        XCTAssertFalse(hostileApp.staticTexts["private-plant"].exists)
        hostileApp.terminate()

        let malformedApp = XCUIApplication()
        malformedApp.launchEnvironment["QA_DEEP_LINK"] =
            "planterior://plant/%2E%2E"
        malformedApp.launchEnvironment["QA_SKIP_ONBOARDING"] = "1"
        malformedApp.launch()
        XCTAssertTrue(
            malformedApp.otherElements["route.unavailable"]
                .waitForExistence(timeout: 5)
        )
        XCTAssertFalse(malformedApp.staticTexts[".."].exists)
        malformedApp.terminate()

        let deletedApp = XCUIApplication()
        deletedApp.launchEnvironment["QA_DEEP_LINK"] =
            "planterior://plant/local-0"
        deletedApp.launchEnvironment["QA_COLLECTION_FIXTURE"] = "1"
        deletedApp.launchEnvironment["QA_RESET_COLLECTION"] = "1"
        deletedApp.launchEnvironment["QA_TARGET_DELETED"] = "1"
        deletedApp.launchEnvironment["QA_AUTHENTICATED"] = "1"
        deletedApp.launchEnvironment["QA_SKIP_ONBOARDING"] = "1"
        deletedApp.launch()
        XCTAssertTrue(
            deletedApp.otherElements["route.unavailable"]
                .waitForExistence(timeout: 5)
        )
        XCTAssertFalse(deletedApp.staticTexts["local-0"].exists)
    }
}
