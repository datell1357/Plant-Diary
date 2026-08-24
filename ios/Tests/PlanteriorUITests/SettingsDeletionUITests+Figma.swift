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

        XCTAssertTrue(app.otherElements["settings.profile-card"].exists)
        XCTAssertTrue(app.staticTexts["알림 관리"].exists)
        XCTAssertTrue(app.staticTexts["지역 및 환경"].exists)
        XCTAssertTrue(app.staticTexts["계정"].exists)
        XCTAssertTrue(app.buttons["settings.privacy"].isHittable)
        XCTAssertTrue(app.staticTexts["앱 버전"].isHittable)
        XCTAssertTrue(app.staticTexts["v1.0.0"].exists)
        XCTAssertTrue(app.buttons["auth.logout"].isHittable)

        let rootBack = app.buttons["settings.back"]
        let rootTopBar = app.otherElements["settings.top-bar"]
        let rootBody = app.scrollViews["settings.screen"]
        XCTAssertTrue(rootBack.isHittable)
        XCTAssertEqual(rootBack.frame.minX, 16, accuracy: 1)
        XCTAssertEqual(rootBack.frame.minY, 50, accuracy: 2)
        XCTAssertEqual(rootBack.frame.width, 44, accuracy: 1)
        XCTAssertEqual(rootBack.frame.height, 44, accuracy: 1)
        XCTAssertEqual(rootTopBar.frame.minY, 44, accuracy: 1)
        XCTAssertEqual(rootTopBar.frame.height, 56, accuracy: 1)
        XCTAssertEqual(rootBody.frame.minY, 100, accuracy: 1)
        XCTAssertEqual(rootBody.frame.maxY, 874, accuracy: 1)
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
        let quietTopBar = app.otherElements["quiet-hours.top-bar"]
        let quietBody = app.scrollViews["quiet-hours.screen"]
        let warning = app.otherElements["quiet-hours.warning"]
        let save = app.buttons["quiet-hours.save"]
        XCTAssertEqual(toggle.value as? String, "0")
        XCTAssertFalse(start.isEnabled)
        XCTAssertFalse(end.isEnabled)
        XCTAssertEqual(start.value as? String, "22:00:00")
        XCTAssertEqual(end.value as? String, "07:00:00")
        XCTAssertEqual(quietTopBar.frame.minY, 44, accuracy: 1)
        XCTAssertEqual(quietTopBar.frame.height, 56, accuracy: 1)
        XCTAssertEqual(quietBody.frame.minY, 100, accuracy: 1)
        XCTAssertEqual(quietBody.frame.maxY, 769, accuracy: 1)
        XCTAssertEqual(warning.frame.minX, 20, accuracy: 1)
        XCTAssertEqual(warning.frame.minY, 397, accuracy: 2)
        XCTAssertEqual(warning.frame.width, 362, accuracy: 1)
        XCTAssertEqual(warning.frame.height, 80, accuracy: 1)
        XCTAssertEqual(save.frame.minX, 20, accuracy: 1)
        XCTAssertEqual(save.frame.minY, 780, accuracy: 1)
        XCTAssertEqual(save.frame.width, 362, accuracy: 1)
        XCTAssertEqual(save.frame.height, 48, accuracy: 1)
        XCTAssertEqual(save.frame.maxY, 828, accuracy: 1)
        assertReferenceQuietHoursAnatomy(in: app)
        attachScreenshot(named: "quiet-hours-402x874-light")

        toggle.tap()
        XCTAssertTrue(start.isEnabled)
        XCTAssertTrue(end.isEnabled)
        start.tap()
        let dismissStart = app.buttons["PopoverDismissRegion"]
        XCTAssertTrue(dismissStart.waitForExistence(timeout: 2))
        dismissStart.tap()
        end.tap()
        let dismissEnd = app.buttons["PopoverDismissRegion"]
        XCTAssertTrue(dismissEnd.waitForExistence(timeout: 2))
        dismissEnd.tap()
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
        XCTAssertTrue(app.datePickers["quiet-hours.start"].isEnabled)
        XCTAssertTrue(app.datePickers["quiet-hours.end"].isEnabled)
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

    func testFigmaSettingsAndQuietHoursAtKoreanAX5ReduceMotion() {
        let app = figmaSettingsApp(accessibilitySize: true)
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
        assertIconWellSize(
            "settings.location.icon-well",
            expectedSide: 44,
            in: app
        )
        attachScreenshot(named: "settings-korean-ax5-reduce-motion")

        let quietHours = app.buttons["settings.quiet-hours.open"]
        let settingsScroll = app.scrollViews["settings.screen"]
        let tabBarControl = app.buttons["tab.settings"]
        var scrollCount = 0
        while (
            !quietHours.isHittable
                || quietHours.frame.intersects(tabBarControl.frame)
        ), scrollCount < 6 {
            settingsScroll.swipeUp()
            scrollCount += 1
        }
        XCTAssertTrue(quietHours.isHittable)
        XCTAssertFalse(quietHours.frame.intersects(tabBarControl.frame))
        quietHours.tap()
        let scroll = app.scrollViews["quiet-hours.screen"]
        XCTAssertTrue(scroll.waitForExistence(timeout: 5))
        let topBar = app.otherElements["quiet-hours.top-bar"]
        let title = app.staticTexts
            .matching(NSPredicate(format: "label == %@", "알림 금지 시간 설정"))
            .element(boundBy: 0)
        XCTAssertTrue(title.exists)
        XCTAssertGreaterThan(
            topBar.frame.height,
            56,
            "AX5 top chrome must expand beyond its default Large height"
        )
        XCTAssertTrue(
            topBar.frame.contains(title.frame),
            "the complete AX5 title must remain inside the expanded top bar"
        )
        XCTAssertGreaterThan(
            title.frame.height,
            56,
            "the AX5 Quiet Hours title must use multiple lines"
        )
        XCTAssertEqual(title.frame.midX, topBar.frame.midX, accuracy: 1)
        XCTAssertEqual(title.label, "알림 금지 시간 설정")
        XCTAssertFalse(title.label.contains("\u{2026}"))
        assertIconWellSize(
            "quiet-hours.clock.icon-well",
            expectedSide: 44,
            in: app
        )
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
        scroll.swipeUp()
        attachScreenshot(named: "quiet-hours-korean-ax5-reduce-motion")
        let save = app.buttons["quiet-hours.save"]
        let window = app.windows.element(boundBy: 0)
        // Remove the two 11pt insets, 34pt bottom safe area, and 1pt divider.
        let saveVisualHeight = window.frame.maxY - scroll.frame.maxY - 57
        XCTAssertTrue(save.isHittable)
        XCTAssertGreaterThan(
            saveVisualHeight,
            48,
            "AX5 Save must expand beyond its default Large height"
        )
        XCTAssertTrue(
            window.frame.contains(save.frame),
            "the complete AX5 Save frame must remain onscreen"
        )
        XCTAssertGreaterThanOrEqual(save.frame.minY, scroll.frame.maxY + 11)
        XCTAssertLessThanOrEqual(save.frame.maxY, window.frame.maxY - 11)
        XCTAssertEqual(save.label, "저장하기")
        XCTAssertFalse(save.label.contains("\u{2026}"))
        attachJSON(
            [
                "saveFrameHeight": save.frame.height,
                "saveVisualHeight": saveVisualHeight,
                "titleFrameHeight": title.frame.height,
                "topBarHeight": topBar.frame.height
            ],
            named: "quiet-hours-ax5-geometry"
        )
        save.tap()
        XCTAssertTrue(app.buttons["settings.quiet-hours.open"].waitForExistence(timeout: 5))
    }

    func figmaSettingsApp(
        accountID: String? = nil,
        accessibilitySize: Bool = false
    ) -> XCUIApplication {
        let app = XCUIApplication()
        app.launchEnvironment["QA_SKIP_ONBOARDING"] = "1"
        app.launchEnvironment["QA_AUTHENTICATED"] = "1"
        app.launchEnvironment["QA_ACCOUNT_ID"] = accountID
            ?? "qa-settings-\(UUID().uuidString)"
        app.launchEnvironment["QA_AUTH_PROFILE_NAME"] = "민지"
        app.launchEnvironment["QA_AUTH_PROFILE_EMAIL"] = "minji@email.com"
        app.launchEnvironment["QA_WEATHER_AUTHORIZATION"] = "full"
        app.launchEnvironment["QA_WEATHER_MANUAL_REGION"] = "manual-seoul"
        app.launchEnvironment["QA_SETTINGS_LOCATION_TEXT"] =
            "서울특별시 강남구 역삼동"
        app.launchEnvironment["TZ"] = "Asia/Seoul"
        if accessibilitySize {
            app.launchEnvironment["QA_SETTINGS_SIZE_CATEGORY"] = "AX5"
            app.launchArguments += [
                "-UIPreferredContentSizeCategoryName",
                "UICTContentSizeCategoryAccessibilityXXXL"
            ]
        }
        return app
    }

    func openFigmaSettings(in app: XCUIApplication) {
        let settings = app.buttons["tab.settings"]
        XCTAssertTrue(settings.waitForExistence(timeout: 10))
        settings.tap()
        XCTAssertTrue(
            app.scrollViews["settings.screen"].waitForExistence(timeout: 5)
        )
    }
}
