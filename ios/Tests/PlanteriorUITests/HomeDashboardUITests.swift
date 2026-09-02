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
