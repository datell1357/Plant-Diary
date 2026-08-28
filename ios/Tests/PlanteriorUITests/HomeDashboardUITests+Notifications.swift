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

    /// `home.notifications` renders a 40pt avatar-sized bell, but the control
    /// itself must still expose the 44pt HIG/WCAG target its siblings do.
    /// Assert the live frame, not the declared modifier: the previously
    /// shipped nested-frame chain declared 44 and rendered 40.
    func assertNotificationTargetMeetsMinimum(in app: XCUIApplication) {
        let minimumTarget: CGFloat = 44
        let subpixelEpsilon: CGFloat = 0.001
        let notifications = app.buttons["home.notifications"]
        XCTAssertTrue(notifications.waitForExistence(timeout: 10))
        XCTAssertFalse(
            meetsMinimumTarget(
                43.9,
                minimum: minimumTarget,
                epsilon: subpixelEpsilon
            ),
            "the epsilon must not admit a genuinely undersized target"
        )
        XCTAssertTrue(
            meetsMinimumTarget(
                notifications.frame.width,
                minimum: minimumTarget,
                epsilon: subpixelEpsilon
            ),
            "home.notifications width was \(notifications.frame)"
        )
        XCTAssertTrue(
            meetsMinimumTarget(
                notifications.frame.height,
                minimum: minimumTarget,
                epsilon: subpixelEpsilon
            ),
            "home.notifications height was \(notifications.frame)"
        )
        XCTAssertTrue(notifications.isHittable)
    }

    private func meetsMinimumTarget(
        _ dimension: CGFloat,
        minimum: CGFloat,
        epsilon: CGFloat
    ) -> Bool {
        dimension + epsilon >= minimum
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
