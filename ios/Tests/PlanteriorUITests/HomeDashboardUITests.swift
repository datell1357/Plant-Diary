import Foundation
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
        app.launchEnvironment["QA_MINIHOME_RESET_TOKEN"] = UUID().uuidString
        app.launch()

        XCTAssertTrue(app.staticTexts["home.greeting"].waitForExistence(timeout: 5))
        XCTAssertTrue(app.staticTexts["home.care.row.0"].exists)
        // Migrated to the Figma room hero (§6.3), which replaces the old
        // mini-home summary row and still proves the committed room is shown.
        XCTAssertFalse(
            app.images["home.room.hero"].exists,
            "the decorative room base must remain hidden from VoiceOver"
        )
        XCTAssertTrue(app.buttons["home.room.title"].exists)
        assertRoomVisualGeometry(in: app, state: "authenticated-default")
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
        let receipt = applyLoggedOutFigmaLaunch(loggedOut)
        loggedOut.launch()
        waitForLoggedOutHomeFixture(in: loggedOut, receipt: receipt)

        // Migrated to the Figma signed-out header (§8.3): the guest greeting and
        // the login link prove the logged-out state without hiding the body.
        XCTAssertTrue(
            loggedOut.buttons["home.login.link"].waitForExistence(timeout: 5)
        )
        XCTAssertEqual(
            loggedOut.staticTexts["home.greeting"].label,
            "안녕하세요, 게스트님!"
        )
        XCTAssertFalse(loggedOut.buttons["home.identify"].exists)
        XCTAssertTrue(loggedOut.buttons["tab.camera"].exists)
        XCTAssertEqual(loggedOut.staticTexts.matching(
            NSPredicate(format: "identifier BEGINSWITH %@", "home.care.row.")
        ).count, 0)
        XCTAssertTrue(loggedOut.staticTexts["home.care.empty"].exists)
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
        XCTAssertFalse(signingIn.buttons["home.identify"].exists)
        XCTAssertTrue(signingIn.buttons["tab.camera"].exists)
        attachScreenshot(
            app: signingIn,
            named: "task-12-home-signing-in"
        )
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

    func testWateringCompletionCancelsPendingNotifications() throws {
        let app = XCUIApplication()
        app.launchEnvironment["QA_SKIP_ONBOARDING"] = "1"
        app.launchEnvironment["QA_AUTHENTICATED"] = "1"
        app.launchEnvironment["QA_COLLECTION_FIXTURE"] = "1"
        app.launchEnvironment["QA_ACCOUNT_ID"] = "qa-notification-" + UUID().uuidString
        app.launchEnvironment["QA_WATERING_TODAY"] = try qaFutureWateringDate()
        app.launch()

        XCTAssertTrue(app.scrollViews["home.screen"].waitForExistence(timeout: 5))
        app.buttons["tab.settings"].tap()
        XCTAssertTrue(app.scrollViews["settings.screen"].waitForExistence(timeout: 5))
        let watering = app.switches["settings.alerts.watering-enabled"]
        XCTAssertTrue(watering.waitForExistence(timeout: 5))
        if watering.value as? String == "1" {
            watering.tap()
        }
        let permissionMonitor = addUIInterruptionMonitor(
            withDescription: "Notification authorization"
        ) { alert in
            let allow = alert.buttons["허용"].exists
                ? alert.buttons["허용"]
                : alert.buttons["Allow"]
            guard allow.exists else { return false }
            allow.tap()
            return true
        }
        watering.tap()
        app.tap()
        removeUIInterruptionMonitor(permissionMonitor)
        let authorizedStatus = app.staticTexts
            .matching(identifier: "settings.permission.notifications")
            .matching(NSPredicate(format: "label == %@", "허용됨"))
            .firstMatch
        XCTAssertTrue(authorizedStatus.waitForExistence(timeout: 5))
        app.terminate()
        app.launch()
        let initialCount = app.staticTexts
            .matching(identifier: "home.notification.scheduled")
            .matching(NSPredicate(format: "label == %@", "예정 알림 2건"))
            .firstMatch
        XCTAssertTrue(initialCount.waitForExistence(timeout: 10))
        app.buttons["tab.collection"].tap()
        XCTAssertTrue(app.buttons["collection.row.0"].waitForExistence(timeout: 5))
        app.buttons["collection.row.0"].tap()
        let complete = app.buttons["watering.complete"]
        XCTAssertTrue(complete.waitForExistence(timeout: 5))
        complete.tap()
        app.buttons["tab.home"].tap()
        app.swipeUp()
        let count = app.staticTexts
            .matching(identifier: "home.notification.scheduled")
            .matching(NSPredicate(format: "label == %@", "예정 알림 0건"))
            .firstMatch
        XCTAssertTrue(count.waitForExistence(timeout: 10))
    }

    private func qaFutureWateringDate() throws -> String {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = .current
        let futureDate = try XCTUnwrap(
            calendar.date(byAdding: .day, value: 1, to: Date())
        )
        let formatter = DateFormatter()
        formatter.calendar = calendar
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.dateFormat = "yyyy-MM-dd"
        return formatter.string(from: futureDate)
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

        let greeting = app.staticTexts["home.greeting"]
        XCTAssertTrue(greeting.waitForExistence(timeout: 5))
        XCTAssertGreaterThanOrEqual(
            greeting.frame.width,
            250,
            "AX5 header must reflow so 안녕하세요 is not split mid-word"
        )
        let metadata = app.staticTexts["home.greeting.meta"]
        XCTAssertLessThanOrEqual(
            metadata.frame.height,
            60,
            "AX5 metadata must remain a single line rather than orphaning its final unit"
        )
        for _ in 0 ..< 4 {
            app.swipeUp()
        }
        XCTAssertTrue(
            app.staticTexts["home.notification.denied"].waitForExistence(timeout: 5)
        )
        attachScreenshot(named: "task-12-home-ax5")
    }
}
