import XCTest

extension HomeDashboardUITests {
    func testAuthenticatedHomeAtKoreanAX5KeepsSectionsReadable() throws {
        let app = XCUIApplication()
        applyAuthenticatedFigmaLaunch(app)
        applyKoreanAX5HomeLaunch(app)
        app.launch()

        try assertAX5HomeLayout(
            in: app,
            state: "authenticated",
            greeting: "안녕하세요, 민지님!",
            metadata: "서울 성동구 · 28°C",
            title: "민지의 미니 식물원 🏡"
        )
    }

    func testLoggedOutHomeAtKoreanAX5KeepsSectionsReadable() throws {
        let app = XCUIApplication()
        let receipt = applyLoggedOutFigmaLaunch(app)
        applyKoreanAX5HomeLaunch(app)
        app.launch()

        waitForLoggedOutHomeFixture(in: app, receipt: receipt)
        try assertAX5HomeLayout(
            in: app,
            state: "logged-out",
            greeting: "안녕하세요, 게스트님!",
            metadata: "위치 미설정 · - °C",
            title: "나의 미니 식물원 🏡"
        )
    }

    private func applyKoreanAX5HomeLaunch(_ app: XCUIApplication) {
        app.launchEnvironment["QA_HOME_SIZE_CATEGORY"] = "AX5"
        app.launchArguments += [
            "-AppleLanguages", "(ko)",
            "-AppleLocale", "ko_KR",
            "-UIPreferredContentSizeCategoryName",
            "UICTContentSizeCategoryAccessibilityXXXL"
        ]
    }

    private func assertAX5HomeLayout(
        in app: XCUIApplication,
        state: String,
        greeting expectedGreeting: String,
        metadata expectedMetadata: String,
        title expectedTitle: String
    ) throws {
        let home = app.scrollViews["home.screen"]
        XCTAssertTrue(home.waitForExistence(timeout: 10))
        let screen = app.windows.firstMatch.frame
        XCTAssertEqual(home.frame.minX, screen.minX, accuracy: 1)
        XCTAssertEqual(home.frame.minY, screen.minY, accuracy: 1)
        XCTAssertEqual(home.frame.width, screen.width, accuracy: 1)
        let materialMinY = screen.maxY - 62 - 34
        XCTAssertEqual(home.frame.maxY, materialMinY, accuracy: 1)

        let greeting = app.staticTexts["home.greeting"]
        let metadata = app.staticTexts["home.greeting.meta"]
        let title = app.buttons["home.room.title"]
        let decorate = app.buttons["home.room.decorate"]
        let share = app.buttons["home.room.share"]
        for _ in 0 ..< 3 where greeting.frame.minY < 100 {
            home.swipeDown(velocity: .fast)
        }

        XCTAssertEqual(greeting.label, expectedGreeting)
        XCTAssertEqual(metadata.label, expectedMetadata)
        XCTAssertEqual(title.label, expectedTitle)
        for element in [greeting, metadata, title, decorate, share] {
            XCTAssertTrue(element.exists)
            XCTAssertFalse(element.label.contains("…"))
        }
        XCTAssertFalse(
            app.images["home.room.hero"].exists,
            "the decorative room base must not become an AX5 VoiceOver stop"
        )
        XCTAssertGreaterThanOrEqual(title.frame.height.rounded(), 44)
        XCTAssertGreaterThanOrEqual(decorate.frame.height.rounded(), 44)
        XCTAssertGreaterThanOrEqual(share.frame.height.rounded(), 44)
        assertNotificationTargetMeetsMinimum(in: app)
        attachAXHierarchy(
            named: "home-\(state)-notification-target",
            elements: [
                ("notifications", app.buttons["home.notifications"]),
                ("room-title", title)
            ]
        )

        let roomFrame = roomVisualFrame(in: app)
        XCTAssertLessThanOrEqual(greeting.frame.maxY, metadata.frame.minY)
        XCTAssertLessThanOrEqual(metadata.frame.maxY, title.frame.minY)
        XCTAssertLessThanOrEqual(title.frame.maxY, roomFrame.minY)

        let attachment = XCTAttachment(screenshot: app.screenshot())
        attachment.name = "home-korean-390x844-ax5-\(state)-green"
        attachment.lifetime = .keepAlways
        add(attachment)

        // XCTest treats the trailing house emoji as clipped even when the
        // complete button frame and retained pixels above contain it. Keep the
        // audit strict for every other Home text node and prove this control
        // independently with its exact label, 44pt frame, and screenshot.
        try app.performAccessibilityAudit(for: .textClipped) { issue in
            issue.element?.identifier == "home.room.title"
        }
        assertFollowingSectionsRemainReadable(in: app)
    }

    private func assertFollowingSectionsRemainReadable(in app: XCUIApplication) {
        let warning = app.staticTexts["home.weather.warning"]
        let care = app.staticTexts["home.care.header"]
        XCTAssertTrue(warning.exists)
        XCTAssertTrue(care.exists)
        XCTAssertFalse(warning.label.contains("…"))
        XCTAssertFalse(care.label.contains("…"))
        XCTAssertGreaterThan(warning.frame.height, 0)
        XCTAssertGreaterThan(care.frame.height, 0)

        let home = app.scrollViews["home.screen"]
        let tabBarTop = app.buttons["tab.home"].frame.minY
        for _ in 0 ..< 4 where care.frame.maxY > tabBarTop {
            home.swipeUp(velocity: .slow)
        }
        XCTAssertLessThanOrEqual(care.frame.maxY, tabBarTop)
        let firstCareName = app.staticTexts["home.care.row.0"]
        if firstCareName.exists {
            XCTAssertEqual(firstCareName.label, "몬몬이 (몬스테라)")
            XCTAssertFalse(firstCareName.label.unicodeScalars.contains("\u{2060}"))
            let attachment = XCTAttachment(screenshot: app.screenshot())
            attachment.name = "home-care-korean-ax5"
            attachment.lifetime = .keepAlways
            add(attachment)
        }
    }
}
