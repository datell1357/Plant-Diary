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

    func testWateringPreferenceDoesNotCrossAccounts() {
        let accountA = "qa-watering-a-\(UUID().uuidString)"
        let accountB = "qa-watering-b-\(UUID().uuidString)"
        let first = figmaSettingsApp()
        first.launchEnvironment["QA_ACCOUNT_ID"] = accountA
        first.launch()
        openFigmaSettings(in: first)
        let firstToggle = first.switches["settings.alerts.watering-enabled"]
        if firstToggle.value as? String != "0" {
            firstToggle.tap()
            let disabled = XCTNSPredicateExpectation(
                predicate: NSPredicate(format: "value == '0'"),
                object: firstToggle
            )
            XCTAssertEqual(XCTWaiter.wait(for: [disabled], timeout: 5), .completed)
        }
        first.terminate()

        let second = figmaSettingsApp()
        second.launchEnvironment["QA_ACCOUNT_ID"] = accountB
        second.launch()
        openFigmaSettings(in: second)
        XCTAssertEqual(
            second.switches["settings.alerts.watering-enabled"].value as? String,
            "1",
            "a new account must receive its own default watering preference"
        )
        second.terminate()

        let restored = figmaSettingsApp()
        restored.launchEnvironment["QA_ACCOUNT_ID"] = accountA
        restored.launch()
        openFigmaSettings(in: restored)
        XCTAssertEqual(
            restored.switches[
                "settings.alerts.watering-enabled"
            ].value as? String,
            "0",
            "returning to an account must restore only that account's preference"
        )
        attachScreenshot(named: "track-3-watering-account-a-restored")
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
}
