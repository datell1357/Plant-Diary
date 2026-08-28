import XCTest

extension HomeDashboardUITests {
    func applyAuthenticatedFigmaLaunch(_ app: XCUIApplication) {
        app.launchEnvironment["QA_SKIP_ONBOARDING"] = "1"
        app.launchEnvironment["QA_AUTHENTICATED"] = "1"
        app.launchEnvironment["QA_COLLECTION_FIXTURE"] = "1"
        app.launchEnvironment["QA_HOME_FIXTURE"] = "1"
        app.launchEnvironment["QA_AUTH_PROFILE_NAME"] = "민지"
        app.launchEnvironment["QA_HOME_WEATHER_STATE"] = "high-dry"
        app.launchEnvironment["QA_WEATHER_MANUAL_REGION"] = "manual-seoul"
        app.launchEnvironment["QA_WEATHER_NOW"] = "2026-08-11T03:00:00Z"
        app.launchEnvironment["QA_RESET_WEATHER"] = "1"
        app.launchEnvironment["QA_WATERING_TODAY"] = "2026-08-11"
        app.launchEnvironment["QA_MINIHOME_NOW"] = "2026-08-11T00:00:00Z"
        app.launchEnvironment["QA_MINIHOME_RESET_TOKEN"] = UUID().uuidString
    }

    func applyLoggedOutFigmaLaunch(
        _ app: XCUIApplication,
        testID: String = #function
    ) -> String {
        let token = testID
            .replacingOccurrences(of: "[^A-Za-z0-9]+", with: "-", options: .regularExpression)
            .trimmingCharacters(in: CharacterSet(charactersIn: "-"))
        app.launchEnvironment["QA_SKIP_ONBOARDING"] = "1"
        app.launchEnvironment["QA_AUTHENTICATED"] = "0"
        app.launchEnvironment["QA_RESET_COLLECTION"] = "1"
        app.launchEnvironment["QA_HOME_COLLECTION_STATE"] = "empty"
        app.launchEnvironment["QA_HOME_FIXTURE_TOKEN"] = token
        app.launchEnvironment["QA_MINIHOME_RESET_TOKEN"] = token
        return "token=\(token);collection=empty;care=0"
    }

    func waitForLoggedOutHomeFixture(
        in app: XCUIApplication,
        receipt: String
    ) {
        let home = app.scrollViews["home.screen"]
        let mounted = XCTNSPredicateExpectation(
            predicate: NSPredicate(format: "value == %@", receipt),
            object: home
        )
        XCTAssertEqual(XCTWaiter.wait(for: [mounted], timeout: 10), .completed)
        XCTAssertEqual(home.value as? String, receipt)
        XCTAssertEqual(app.staticTexts.matching(
            NSPredicate(format: "identifier BEGINSWITH %@", "home.care.row.")
        ).count, 0)
        XCTAssertTrue(app.staticTexts["home.care.empty"].exists)
    }

    /// The rename modal carries `.isModal`, so it surfaces as an alert-class
    /// element rather than a plain container.
    func renameDialog(_ app: XCUIApplication) -> XCUIElement {
        app.descendants(matching: .any)["home.rename.dialog"]
    }
}
