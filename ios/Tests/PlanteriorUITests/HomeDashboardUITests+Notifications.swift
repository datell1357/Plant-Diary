import XCTest

extension HomeDashboardUITests {
    func testDeniedNotificationsDoNotBlockCollection() {
        let app = XCUIApplication()
        app.launchEnvironment["QA_SKIP_ONBOARDING"] = "1"
        app.launchEnvironment["QA_AUTHENTICATED"] = "1"
        app.launchEnvironment["QA_COLLECTION_FIXTURE"] = "1"
        app.launchEnvironment["QA_NOTIFICATION_AUTHORIZATION"] = "denied"
        app.launch()

        XCTAssertTrue(
            app.staticTexts["home.notification.denied"].waitForExistence(timeout: 5)
        )
        app.swipeUp()
        attachScreenshot(named: "task-12-home-notification-denied")
        app.buttons["tab.collection"].tap()
        XCTAssertTrue(app.scrollViews["collection.screen"].waitForExistence(timeout: 5))
        let searchAction = app.buttons["collection.search.action"]
        XCTAssertTrue(searchAction.waitForExistence(timeout: 5))
        let searchField = app.textFields["collection.search"]
        if !searchField.exists {
            searchAction.tap()
        }
        XCTAssertTrue(searchField.waitForExistence(timeout: 5))
    }

    func attachScreenshot(named name: String) {
        attachScreenshot(app: XCUIApplication(), named: name)
    }

    func attachScreenshot(
        app _: XCUIApplication,
        named name: String
    ) {
        let attachment = XCTAttachment(
            screenshot: XCUIScreen.main.screenshot()
        )
        attachment.name = name
        attachment.lifetime = .keepAlways
        add(attachment)
    }
}
