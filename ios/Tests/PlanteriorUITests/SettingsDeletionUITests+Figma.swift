import XCTest

@MainActor
extension SettingsDeletionUITests {
    func testFigmaSettingsRootAndQuietHoursPersist() {
        let accountID = "qa-settings-figma-\(UUID().uuidString)"
        let app = figmaSettingsApp(accountID: accountID)
        app.launchArguments += [
            "-AppleLanguages", "(ko)",
            "-AppleLocale", "ko_KR"
        ]
        app.launch()
        openFigmaSettings(in: app)

        XCTAssertTrue(app.staticTexts["settings.profile.name"].exists)
        XCTAssertTrue(app.staticTexts["알림 관리"].exists)
        XCTAssertTrue(app.staticTexts["지역 및 환경"].exists)
        XCTAssertTrue(app.staticTexts["계정"].exists)
        XCTAssertTrue(app.buttons["settings.privacy"].isHittable)
        XCTAssertTrue(app.staticTexts["앱 버전"].isHittable)
        XCTAssertTrue(app.staticTexts["v1.0.0"].exists)
        XCTAssertTrue(app.buttons["auth.logout"].isHittable)
        XCTAssertFalse(app.buttons["settings.milestones"].exists)
        XCTAssertFalse(app.staticTexts["꾸미기 마일스톤"].exists)

        assertReferenceRootAnatomy(in: app)
        attachScreenshot(named: "settings-402x874-light")

        app.buttons["settings.quiet-hours.open"].tap()
        XCTAssertTrue(
            app.scrollViews["quiet-hours.screen"]
                .waitForExistence(timeout: 5)
        )
        let toggle = app.switches["quiet-hours.enabled"]
        let start = app.datePickers["quiet-hours.start"]
        let end = app.datePickers["quiet-hours.end"]
        XCTAssertEqual(toggle.value as? String, "0")
        XCTAssertFalse(start.isEnabled)
        XCTAssertFalse(end.isEnabled)
        XCTAssertEqual(start.value as? String, "22:00:00")
        XCTAssertEqual(end.value as? String, "07:00:00")
        assertReferenceQuietHoursAnatomy(in: app)
        attachScreenshot(named: "quiet-hours-402x874-light")

        assertQuietHoursPersistence(in: app)
    }

    func testEnablingWateringAlertsRequestsNotificationAuthorization() {
        let app = figmaSettingsApp()
        app.launchEnvironment["QA_NOTIFICATION_AUTHORIZATION"] = "notDetermined"
        app.launchEnvironment["QA_NOTIFICATION_REQUEST_RESULT"] = "authorized"
        app.launch()
        openFigmaSettings(in: app)

        let watering = app.switches["settings.alerts.watering-enabled"]
        assertSwitch(watering, reachesValue: "1")
        watering.tap()
        assertSwitch(watering, reachesValue: "0")
        watering.tap()
        assertSwitch(watering, reachesValue: "1")

        let status = app.staticTexts
            .matching(identifier: "settings.permission.notifications")
            .matching(NSPredicate(format: "label == '허용됨'"))
            .firstMatch
        XCTAssertTrue(status.waitForExistence(timeout: 5))
    }

    func testOpeningSettingsDoesNotRequestOrClearAlertPreferences() {
        let app = figmaSettingsApp()
        app.launchEnvironment["QA_NOTIFICATION_AUTHORIZATION"] = "notDetermined"
        app.launchEnvironment["QA_NOTIFICATION_REQUEST_RESULT"] = "authorized"
        app.launch()
        openFigmaSettings(in: app)

        XCTAssertTrue(
            notificationStatus("확인 필요", in: app).waitForExistence(timeout: 5)
        )
        assertSwitch(
            app.switches["settings.alerts.watering-enabled"],
            reachesValue: "1"
        )
        assertSwitch(
            app.switches["settings.alerts.weather-enabled"],
            reachesValue: "1"
        )
    }

    func testDisablingWateringAlertsDoesNotRequestNotificationAuthorization() {
        let app = figmaSettingsApp()
        app.launchEnvironment["QA_NOTIFICATION_AUTHORIZATION"] = "notDetermined"
        app.launchEnvironment["QA_NOTIFICATION_REQUEST_RESULT"] = "authorized"
        app.launch()
        openFigmaSettings(in: app)

        let watering = app.switches["settings.alerts.watering-enabled"]
        XCTAssertTrue(
            notificationStatus("확인 필요", in: app).waitForExistence(timeout: 5)
        )
        assertSwitch(watering, reachesValue: "1")
        watering.tap()
        assertSwitch(watering, reachesValue: "0")
        XCTAssertTrue(
            notificationStatus("확인 필요", in: app).waitForExistence(timeout: 5)
        )
    }

    func testAlreadyAuthorizedWateringAlertEnablesImmediately() {
        let app = figmaSettingsApp()
        app.launchEnvironment["QA_NOTIFICATION_AUTHORIZATION"] = "authorized"
        app.launch()
        openFigmaSettings(in: app)

        let watering = app.switches["settings.alerts.watering-enabled"]
        assertSwitch(watering, reachesValue: "1")
        watering.tap()
        assertSwitch(watering, reachesValue: "0")
        watering.tap()
        assertSwitch(watering, reachesValue: "1")
        XCTAssertTrue(
            notificationStatus("허용됨", in: app).waitForExistence(timeout: 5)
        )
    }

    func testDeniedNotificationAuthorizationKeepsWeatherAlertsOff() {
        let app = figmaSettingsApp()
        app.launchEnvironment["QA_NOTIFICATION_AUTHORIZATION"] = "notDetermined"
        app.launchEnvironment["QA_NOTIFICATION_REQUEST_RESULT"] = "denied"
        app.launch()
        openFigmaSettings(in: app)

        let weather = app.switches["settings.alerts.weather-enabled"]
        assertSwitch(weather, reachesValue: "1")
        weather.tap()
        assertSwitch(weather, reachesValue: "0")
        weather.tap()
        assertSwitch(weather, reachesValue: "0")

        let status = app.staticTexts
            .matching(identifier: "settings.permission.notifications")
            .matching(NSPredicate(format: "label == '설정에서 허용 필요'"))
            .firstMatch
        XCTAssertTrue(status.waitForExistence(timeout: 5))
    }

    func testDeniedNotificationAuthorizationKeepsWateringIntentOnAndShowsSettingsStatus() {
        // Given
        let app = figmaSettingsApp()
        app.launchEnvironment["QA_NOTIFICATION_AUTHORIZATION"] = "notDetermined"
        app.launchEnvironment["QA_NOTIFICATION_REQUEST_RESULT"] = "denied"
        app.launch()
        openFigmaSettings(in: app)
        let watering = app.switches["settings.alerts.watering-enabled"]
        assertSwitch(watering, reachesValue: "1")
        watering.tap()
        assertSwitch(watering, reachesValue: "0")

        // When
        watering.tap()

        // Then
        assertSwitch(watering, reachesValue: "1")
        XCTAssertTrue(
            notificationStatus("설정에서 허용 필요", in: app)
                .waitForExistence(timeout: 5)
        )
    }

    func testFailedNotificationAuthorizationKeepsWateringIntentOnAndShowsSettingsStatus() {
        let app = figmaSettingsApp()
        app.launchEnvironment["QA_NOTIFICATION_AUTHORIZATION"] = "notDetermined"
        app.launchEnvironment["QA_NOTIFICATION_REQUEST_RESULT"] = "failed"
        app.launch()
        openFigmaSettings(in: app)

        let watering = app.switches["settings.alerts.watering-enabled"]
        assertSwitch(watering, reachesValue: "1")
        watering.tap()
        assertSwitch(watering, reachesValue: "0")
        watering.tap()
        assertSwitch(watering, reachesValue: "1")
        XCTAssertTrue(
            notificationStatus("설정에서 허용 필요", in: app)
                .waitForExistence(timeout: 5)
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
}
