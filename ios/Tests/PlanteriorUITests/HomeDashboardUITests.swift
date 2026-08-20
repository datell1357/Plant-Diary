import XCTest

@MainActor
final class HomeDashboardUITests: XCTestCase {
    func testAuthenticatedHomeShowsCareMiniHomeAndPartialWeather() {
        let app = XCUIApplication()
        app.launchEnvironment["QA_SKIP_ONBOARDING"] = "1"
        app.launchEnvironment["QA_AUTHENTICATED"] = "1"
        app.launchEnvironment["QA_COLLECTION_FIXTURE"] = "1"
        app.launchEnvironment["QA_HOME_FIXTURE"] = "1"
        app.launchEnvironment["QA_HOME_WEATHER_STATE"] = "failed"
        app.launchEnvironment["QA_NOTIFICATION_AUTHORIZATION"] = "authorized"
        app.launchEnvironment["QA_NOTIFICATION_ENDPOINT"] = "registered"
        app.launchEnvironment["QA_WATERING_TODAY"] = "2026-08-11"
        app.launch()

        XCTAssertTrue(app.staticTexts["home.greeting"].waitForExistence(timeout: 5))
        XCTAssertTrue(app.staticTexts["home.care.row.0"].exists)
        // Migrated to the Figma room hero (§6.3), which replaces the old
        // mini-home summary row and still proves the committed room is shown.
        let miniHome = app.images["home.room.hero"]
        XCTAssertTrue(miniHome.exists)
        XCTAssertTrue(app.buttons["home.room.title"].exists)
        XCTAssertTrue(app.staticTexts["home.weather.failed"].exists)
        XCTAssertTrue(app.staticTexts["home.notification.status"].exists)
        attachScreenshot(named: "task-12-home-dashboard")
        // The Figma room hero makes Home taller, so scroll until the trailing
        // status rows are reachable instead of assuming one swipe.
        let scheduled = app.staticTexts["home.notification.scheduled"]
        for _ in 0 ..< 6 where !scheduled.exists {
            app.swipeUp()
        }
        XCTAssertTrue(scheduled.waitForExistence(timeout: 5))
        let sync = app.staticTexts["home.sync.status"]
        for _ in 0 ..< 4 where !sync.exists {
            app.swipeUp()
        }
        XCTAssertTrue(sync.exists)
        attachScreenshot(named: "task-12-home-notification")
    }

    func testLoggedOutAndSigningInStatesKeepIdentificationAvailable() {
        let loggedOut = XCUIApplication()
        loggedOut.launchEnvironment["QA_SKIP_ONBOARDING"] = "1"
        loggedOut.launch()

        // Migrated to the Figma signed-out header (§8.3): the guest greeting and
        // the login link prove the logged-out state without hiding the body.
        XCTAssertTrue(
            loggedOut.buttons["home.login.link"].waitForExistence(timeout: 5)
        )
        XCTAssertEqual(
            loggedOut.staticTexts["home.greeting"].label,
            "안녕하세요, 게스트님!"
        )
        XCTAssertTrue(loggedOut.buttons["home.identify"].exists)
        attachScreenshot(
            app: loggedOut,
            named: "task-12-home-logged-out"
        )
        loggedOut.terminate()

        let signingIn = XCUIApplication()
        signingIn.launchEnvironment["QA_SKIP_ONBOARDING"] = "1"
        signingIn.launchEnvironment["QA_HOME_AUTH_STATE"] = "signing-in"
        signingIn.launch()
        XCTAssertTrue(
            signingIn.descendants(matching: .any)["home.auth.signing-in"]
                .waitForExistence(timeout: 5)
        )
        XCTAssertTrue(signingIn.buttons["home.identify"].exists)
        attachScreenshot(
            app: signingIn,
            named: "task-12-home-signing-in"
        )
    }

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
        XCTAssertTrue(app.textFields["collection.search"].waitForExistence(timeout: 5))
    }

    func testDeletedNotificationTargetShowsUnavailableWithoutPlantMetadata() {
        let app = XCUIApplication()
        app.launchEnvironment["QA_SKIP_ONBOARDING"] = "1"
        app.launchEnvironment["QA_AUTHENTICATED"] = "1"
        app.launchEnvironment["QA_NOTIFICATION_PLANT_ID"] = "private-plant"
        app.launchEnvironment["QA_TARGET_DELETED"] = "1"
        app.launch()

        XCTAssertTrue(
            app.otherElements["route.unavailable"].waitForExistence(timeout: 5)
        )
        XCTAssertFalse(app.staticTexts["private-plant"].exists)
    }

    func testWateringCompletionCancelsPendingNotifications() {
        let app = XCUIApplication()
        app.launchEnvironment["QA_SKIP_ONBOARDING"] = "1"
        app.launchEnvironment["QA_AUTHENTICATED"] = "1"
        app.launchEnvironment["QA_COLLECTION_FIXTURE"] = "1"
        app.launchEnvironment["QA_NOTIFICATION_AUTHORIZATION"] = "authorized"
        app.launchEnvironment["QA_NOTIFICATION_ENDPOINT"] = "registered"
        app.launchEnvironment["QA_WATERING_TODAY"] = "2026-08-11"
        app.launch()

        let initialCount = app.staticTexts["home.notification.scheduled"]
        XCTAssertTrue(initialCount.waitForExistence(timeout: 5))
        XCTAssertEqual(initialCount.label, "예정 알림 2건")
        app.buttons["tab.collection"].tap()
        XCTAssertTrue(app.buttons["collection.row.0"].waitForExistence(timeout: 5))
        app.buttons["collection.row.0"].tap()
        let complete = app.buttons["watering.complete"]
        XCTAssertTrue(complete.waitForExistence(timeout: 5))
        complete.tap()
        app.buttons["tab.home"].tap()
        app.swipeUp()
        let count = app.staticTexts["home.notification.scheduled"]
        XCTAssertTrue(count.waitForExistence(timeout: 5))
        XCTAssertEqual(count.label, "예정 알림 0건")
    }

    func testAllCareVariantsUseStableVisualOrdering() {
        let app = XCUIApplication()
        app.launchEnvironment["QA_SKIP_ONBOARDING"] = "1"
        app.launchEnvironment["QA_AUTHENTICATED"] = "1"
        app.launchEnvironment["QA_COLLECTION_FIXTURE"] = "1"
        app.launchEnvironment["QA_HOME_CARE_VARIANTS"] = "1"
        app.launchEnvironment["QA_WATERING_TODAY"] = "2026-08-11"
        app.launch()

        XCTAssertTrue(app.staticTexts["home.care.row.0"].waitForExistence(timeout: 5))
        app.swipeUp()
        XCTAssertTrue(app.staticTexts["home.care.row.3"].waitForExistence(timeout: 5))
        attachScreenshot(named: "task-12-home-care-variants")
    }

    func testAX5HomeKeepsContentScrollableAboveTabBar() {
        let app = XCUIApplication()
        app.launchEnvironment["QA_SKIP_ONBOARDING"] = "1"
        app.launchEnvironment["QA_AUTHENTICATED"] = "1"
        app.launchEnvironment["QA_COLLECTION_FIXTURE"] = "1"
        app.launchEnvironment["QA_HOME_FIXTURE"] = "1"
        app.launchEnvironment["QA_HOME_SIZE_CATEGORY"] = "AX5"
        app.launchEnvironment["QA_NOTIFICATION_AUTHORIZATION"] = "denied"
        app.launchEnvironment["QA_WATERING_TODAY"] = "2026-08-11"
        app.launch()

        XCTAssertTrue(app.staticTexts["home.greeting"].waitForExistence(timeout: 5))
        for _ in 0 ..< 4 {
            app.swipeUp()
        }
        XCTAssertTrue(
            app.staticTexts["home.notification.denied"].waitForExistence(timeout: 5)
        )
        attachScreenshot(named: "task-12-home-ax5")
    }

    private func attachScreenshot(named name: String) {
        attachScreenshot(app: XCUIApplication(), named: name)
    }

    private func attachScreenshot(
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
