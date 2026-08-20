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

    func testSettingsProfileUsesAuthenticatedSessionIdentity() {
        let app = figmaSettingsApp()
        app.launchEnvironment["QA_AUTH_PROFILE_NAME"] = "서연"
        app.launchEnvironment["QA_AUTH_PROFILE_EMAIL"] = "owner+garden@example.org"
        app.launch()
        openFigmaSettings(in: app)

        XCTAssertEqual(app.staticTexts["settings.profile.name"].label, "서연")
        XCTAssertEqual(
            app.staticTexts["settings.profile.email"].label,
            "owner+garden@example.org"
        )
        XCTAssertFalse(app.staticTexts["민지"].exists)
        XCTAssertFalse(app.staticTexts["minji@email.com"].exists)
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
        let email = app.staticTexts["settings.profile.email"]
        XCTAssertTrue(email.exists)
        XCTAssertEqual(email.label, "minji@email.com")
        attachScreenshot(named: "settings-korean-ax5-reduce-motion")

        let quietHours = app.buttons["settings.quiet-hours.open"]
        if !quietHours.isHittable {
            app.swipeUp()
        }
        quietHours.tap()
        let scroll = app.scrollViews["quiet-hours.screen"]
        XCTAssertTrue(scroll.waitForExistence(timeout: 5))
        let enabled = app.switches["quiet-hours.enabled"]
        if enabled.value as? String != "1" {
            enabled.tap()
        }
        scroll.swipeUp()
        let start = app.datePickers["quiet-hours.start"]
        XCTAssertTrue(start.isHittable)
        XCTAssertTrue(app.staticTexts["시작 시간"].exists)
        start.tap()
        let dismissStart = app.buttons["PopoverDismissRegion"]
        XCTAssertTrue(dismissStart.waitForExistence(timeout: 2))
        dismissStart.tap()
        scroll.swipeUp()
        let end = app.datePickers["quiet-hours.end"]
        XCTAssertTrue(end.isHittable)
        XCTAssertTrue(app.staticTexts["종료 시간"].exists)
        end.tap()
        let dismissEnd = app.buttons["PopoverDismissRegion"]
        XCTAssertTrue(dismissEnd.waitForExistence(timeout: 2))
        dismissEnd.tap()
        let save = app.buttons["quiet-hours.save"]
        XCTAssertTrue(save.isHittable)
        save.tap()
        XCTAssertTrue(
            app.buttons["settings.quiet-hours.open"].waitForExistence(timeout: 5)
        )
        attachScreenshot(named: "quiet-hours-korean-ax5-reduce-motion")
    }

    private func figmaSettingsApp() -> XCUIApplication {
        let app = XCUIApplication()
        app.launchEnvironment["QA_SKIP_ONBOARDING"] = "1"
        app.launchEnvironment["QA_AUTHENTICATED"] = "1"
        app.launchEnvironment["QA_AUTH_PROFILE_NAME"] = "민지"
        app.launchEnvironment["QA_AUTH_PROFILE_EMAIL"] = "minji@email.com"
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
