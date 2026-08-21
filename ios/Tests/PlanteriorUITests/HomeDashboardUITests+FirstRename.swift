import XCTest

extension HomeDashboardUITests {
    /// A newly authenticated account has no committed MiniHome yet. Rename Save
    /// must create that room instead of silently consuming neither action nor title.
    func testFirstRenameCreatesRoomBeforeAnyMiniHomeSaveAndSurvivesRelaunch() {
        let app = XCUIApplication()
        applyAuthenticatedFigmaLaunch(app)
        app.launchEnvironment.removeValue(forKey: "QA_HOME_FIXTURE")
        app.launchEnvironment["QA_HOME_RENAME_MODE"] = "free"
        app.launch()

        let title = app.buttons["home.room.title"]
        XCTAssertTrue(title.waitForExistence(timeout: 10))
        title.tap()
        let input = app.textFields["home.rename.input"]
        XCTAssertTrue(input.waitForExistence(timeout: 5))
        input.tap()
        input.typeText("처음 만든 홈")
        app.buttons["home.rename.save"].tap()

        XCTAssertTrue(renameDialog(app).waitForNonExistence(timeout: 5))
        XCTAssertEqual(title.label, "처음 만든 홈 🏡")
        app.terminate()

        let relaunched = XCUIApplication()
        applyAuthenticatedFigmaLaunch(relaunched)
        relaunched.launchEnvironment.removeValue(forKey: "QA_HOME_FIXTURE")
        relaunched.launchEnvironment.removeValue(forKey: "QA_MINIHOME_RESET_TOKEN")
        relaunched.launchEnvironment.removeValue(forKey: "QA_HOME_RENAME_MODE")
        relaunched.launch()
        let restoredTitle = relaunched.buttons["home.room.title"]
        XCTAssertTrue(restoredTitle.waitForExistence(timeout: 10))
        XCTAssertEqual(restoredTitle.label, "처음 만든 홈 🏡")
    }
}
