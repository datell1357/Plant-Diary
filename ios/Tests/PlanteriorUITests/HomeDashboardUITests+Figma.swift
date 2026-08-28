import XCTest

/// Figma `home-screen`, `home-screen-logged-out`, `home-screen-sign-in`,
/// `home-screen-rename-free`, and `home-screen-rename-paid` (figma-analysis
/// §3.1/3.2/6.2/6.3/6.4/6.5/6.8/6.9). Each test pins one app-owned live state.
extension HomeDashboardUITests {
    // MARK: - home.authenticated

    /// §6.2/6.3/6.4/6.5: avatar greeting, editable room title, room hero,
    /// weather warning, care header/badge/rows — and no generic large title.
    func testAuthenticatedHomeRendersFigmaSurfaceWithoutGenericNavigationTitle() {
        let app = XCUIApplication()
        applyAuthenticatedFigmaLaunch(app)
        app.launch()

        XCTAssertTrue(app.scrollViews["home.screen"].waitForExistence(timeout: 10))
        XCTAssertFalse(
            app.navigationBars["홈"].exists,
            "Figma home-screen has no generic large navigation title"
        )

        XCTAssertTrue(app.images["home.avatar"].exists)
        XCTAssertTrue(app.staticTexts["home.greeting"].exists)
        XCTAssertEqual(app.staticTexts["home.greeting"].label, "안녕하세요, 민지님!")
        let metadata = app.staticTexts["home.greeting.meta"]
        XCTAssertEqual(metadata.label, "서울 성동구 · 28°C")
        let weatherGlyph = app.images["home.greeting.weather-glyph"]
        XCTAssertTrue(weatherGlyph.exists)
        XCTAssertEqual(weatherGlyph.frame.width, 37.0 / 3.0, accuracy: 0.1)
        XCTAssertEqual(weatherGlyph.frame.height, 37.0 / 3.0, accuracy: 0.1)
        XCTAssertEqual(weatherGlyph.frame.minX - metadata.frame.maxX, 4, accuracy: 1)
        XCTAssertTrue(app.buttons["home.notifications"].exists)

        let title = app.buttons["home.room.title"]
        XCTAssertTrue(title.exists, "room title must be tappable to rename")
        XCTAssertEqual(title.label, "민지의 미니 식물원 🏡")

        assertRoomVisualGeometry(in: app, state: "authenticated")
        XCTAssertTrue(app.staticTexts["home.weather.warning"].exists)

        XCTAssertTrue(app.staticTexts["home.care.header"].exists)
        XCTAssertEqual(app.staticTexts["home.care.header"].label, "오늘의 식물 관리")
        XCTAssertTrue(app.staticTexts["home.care.badge"].exists)
        XCTAssertEqual(app.staticTexts["home.care.badge"].label, "오늘 1개")
        let firstCareName = app.staticTexts["home.care.row.0"]
        XCTAssertEqual(firstCareName.label, "몬몬이 (몬스테라)")
        XCTAssertFalse(firstCareName.label.unicodeScalars.contains("\u{2060}"))
        XCTAssertEqual(
            app.staticTexts["home.care.status.0"].label,
            "오늘 물 주는 날"
        )
        XCTAssertTrue(app.images["home.care.media.0"].exists)
        XCTAssertEqual(
            app.staticTexts["home.care.row.1"].label,
            "뾰족이 (스투키)"
        )
        XCTAssertEqual(app.staticTexts["home.care.status.1"].label, "3일 후 물주기")
        XCTAssertEqual(app.staticTexts["home.care.trailing.1"].label, "D-3")
        XCTAssertTrue(app.images["home.care.media.1"].exists)
        XCTAssertTrue(app.buttons["home.care.more"].exists)
        XCTAssertFalse(app.buttons["home.identify"].exists)

        assertSingleVerticalScrollOwner(app)
        assertMinimumTargets(app, identifiers: ["home.room.title"])
    }

    func testAuthenticatedHomeFallsBackToHousekeeperWhenProfileNameIsAbsent() {
        // Given
        let app = XCUIApplication()
        app.launchEnvironment["QA_SKIP_ONBOARDING"] = "1"
        app.launchEnvironment["QA_AUTHENTICATED"] = "1"

        // When
        app.launch()

        // Then
        let greeting = app.staticTexts["home.greeting"]
        XCTAssertTrue(greeting.waitForExistence(timeout: 10))
        XCTAssertEqual(greeting.label, "안녕하세요, 집사님!")
    }

    // MARK: - home.loggedOut

    /// §8.3: the signed-out body still renders; it is never hidden behind a
    /// login wall. Guest header, room hero, weather login warning, empty care
    /// state, and the green start link all stay live.
    func testLoggedOutHomeKeepsFigmaBodyVisibleWithLoginLink() {
        let app = XCUIApplication()
        let receipt = applyLoggedOutFigmaLaunch(app)
        app.launch()

        waitForLoggedOutHomeFixture(in: app, receipt: receipt)
        XCTAssertFalse(app.navigationBars["홈"].exists)

        XCTAssertEqual(app.staticTexts["home.greeting"].label, "안녕하세요, 게스트님!")
        XCTAssertEqual(app.staticTexts["home.greeting.meta"].label, "위치 미설정 · - °C")
        XCTAssertEqual(app.buttons["home.room.title"].label, "나의 미니 식물원 🏡")

        assertRoomVisualGeometry(in: app, state: "logged-out")
        XCTAssertTrue(app.staticTexts["home.weather.warning"].exists)
        XCTAssertEqual(
            app.staticTexts["home.weather.warning"].label,
            "로그인하면 내 지역의 날씨 기반 식물 관리 알림을 받을\u{00A0}수\u{00A0}있어요!"
        )

        XCTAssertEqual(app.staticTexts["home.care.badge"].label, "0개")
        XCTAssertTrue(app.staticTexts["home.care.empty"].exists)

        let screenshot = XCTAttachment(screenshot: app.screenshot())
        screenshot.name = "home-logged-out-blocker"
        screenshot.lifetime = .keepAlways
        add(screenshot)

        XCTAssertFalse(
            app.buttons["home.care.more"].exists,
            "§6.5: the trailing schedule action is absent in the zero state"
        )

        let link = app.buttons["home.login.link"]
        XCTAssertTrue(link.exists)
        XCTAssertEqual(link.label, "로그인하고 시작하기")
        assertSingleVerticalScrollOwner(app)
    }

    /// Every account-oriented Home action must use the same shell-owned login
    /// handoff as tabs and camera while signed out.
    func testLoggedOutAccountActionsOpenLoginWithoutEnteringDestinations() {
        for identifier in [
            "home.room.decorate",
            "home.room.share",
            "home.notifications"
        ] {
            let app = XCUIApplication()
            app.launchEnvironment["QA_SKIP_ONBOARDING"] = "1"
            app.launch()

            let action = app.buttons[identifier]
            XCTAssertTrue(action.waitForExistence(timeout: 10))
            action.tap()

            let login = app.buttons["auth.google"]
            XCTAssertTrue(
                login.waitForExistence(timeout: 5),
                "\(identifier) must hand off to the shell login gate"
            )
            XCTAssertFalse(app.scrollViews["mini-home.editor"].exists)
            XCTAssertFalse(app.scrollViews["quiet-hours.screen"].exists)
            app.terminate()
        }
    }
}
