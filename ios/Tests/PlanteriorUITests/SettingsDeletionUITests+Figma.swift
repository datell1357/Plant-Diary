import XCTest

@MainActor
extension SettingsDeletionUITests {
    func testFigmaSettingsRootAndQuietHoursPersist() {
        let app = figmaSettingsApp()
        app.launch()
        openFigmaSettings(in: app)

        XCTAssertTrue(app.otherElements["settings.profile-card"].exists)
        XCTAssertTrue(app.staticTexts["알림 관리"].exists)
        XCTAssertTrue(app.staticTexts["지역 및 환경"].exists)
        XCTAssertTrue(app.staticTexts["계정"].exists)
        XCTAssertTrue(app.staticTexts["앱 버전"].exists)
        let weatherAlerts = app.switches["settings.alerts.weather-enabled"]
        let initialWeatherValue = weatherAlerts.value as? String
        weatherAlerts.tap()
        let weatherChanged = XCTNSPredicateExpectation(
            predicate: NSPredicate(format: "value != %@", initialWeatherValue ?? ""),
            object: weatherAlerts
        )
        XCTAssertEqual(
            XCTWaiter.wait(for: [weatherChanged], timeout: 5),
            .completed
        )
        weatherAlerts.tap()
        attachScreenshot(named: "settings-402x874-light")

        app.buttons["settings.quiet-hours.open"].tap()
        XCTAssertTrue(
            app.scrollViews["quiet-hours.screen"]
                .waitForExistence(timeout: 5)
        )
        let toggle = app.switches["quiet-hours.enabled"]
        if toggle.value as? String != "1" {
            toggle.tap()
        }
        XCTAssertTrue(app.datePickers["quiet-hours.start"].exists)
        XCTAssertTrue(app.datePickers["quiet-hours.end"].exists)
        attachScreenshot(named: "quiet-hours-402x874-light")
        app.buttons["quiet-hours.save"].tap()

        XCTAssertTrue(
            app.buttons["settings.quiet-hours.open"]
                .waitForExistence(timeout: 5)
        )
        app.buttons["settings.quiet-hours.open"].tap()
        XCTAssertEqual(
            app.switches["quiet-hours.enabled"].value as? String,
            "1"
        )
    }

    func testHomeNotificationAffordanceOpensQuietHours() {
        let app = figmaSettingsApp()
        app.launch()
        let notification = app.buttons["home.notifications"]
        XCTAssertTrue(notification.waitForExistence(timeout: 10))
        notification.tap()

        XCTAssertTrue(
            app.scrollViews["quiet-hours.screen"]
                .waitForExistence(timeout: 5)
        )
        XCTAssertFalse(app.textFields["weather.manual-region"].exists)
    }

    func testFigmaSettingsAndQuietHoursAtKoreanAX5ReduceMotion() {
        let app = figmaSettingsApp()
        app.launchEnvironment["QA_SETTINGS_SIZE_CATEGORY"] = "AX5"
        app.launchEnvironment["QA_REDUCE_MOTION"] = "1"
        app.launchArguments += [
            "-AppleLanguages", "(ko)",
            "-AppleLocale", "ko_KR"
        ]
        app.launch()
        openFigmaSettings(in: app)
        attachScreenshot(named: "settings-korean-ax5-reduce-motion")

        let quietHours = app.buttons["settings.quiet-hours.open"]
        if !quietHours.isHittable {
            app.swipeUp()
        }
        quietHours.tap()
        XCTAssertTrue(
            app.scrollViews["quiet-hours.screen"]
                .waitForExistence(timeout: 5)
        )
        attachScreenshot(named: "quiet-hours-korean-ax5-reduce-motion")
    }

    private func figmaSettingsApp() -> XCUIApplication {
        let app = XCUIApplication()
        app.launchEnvironment["QA_SKIP_ONBOARDING"] = "1"
        app.launchEnvironment["QA_AUTHENTICATED"] = "1"
        app.launchEnvironment["QA_WEATHER_AUTHORIZATION"] = "denied"
        return app
    }

    private func openFigmaSettings(in app: XCUIApplication) {
        let settings = app.buttons["tab.settings"]
        XCTAssertTrue(settings.waitForExistence(timeout: 10))
        settings.tap()
        XCTAssertTrue(
            app.scrollViews["settings.screen"].waitForExistence(timeout: 5)
        )
    }
}
