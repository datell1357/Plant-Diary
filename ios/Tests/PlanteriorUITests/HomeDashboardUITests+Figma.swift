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
        XCTAssertTrue(app.staticTexts["home.greeting.meta"].exists)
        XCTAssertTrue(app.buttons["home.notifications"].exists)

        let title = app.buttons["home.room.title"]
        XCTAssertTrue(title.exists, "room title must be tappable to rename")
        XCTAssertEqual(title.label, "민지의 미니 식물원 🏡")

        XCTAssertTrue(app.images["home.room.hero"].exists)
        XCTAssertTrue(app.staticTexts["home.weather.warning"].exists)

        XCTAssertTrue(app.staticTexts["home.care.header"].exists)
        XCTAssertEqual(app.staticTexts["home.care.header"].label, "오늘의 식물 관리")
        XCTAssertTrue(app.staticTexts["home.care.badge"].exists)
        // The QA collection fixture registers two plants.
        XCTAssertEqual(app.staticTexts["home.care.badge"].label, "오늘 2개")
        XCTAssertTrue(app.staticTexts["home.care.row.0"].exists)
        XCTAssertTrue(app.buttons["home.care.more"].exists)

        assertSingleVerticalScrollOwner(app)
        assertMinimumTargets(app, identifiers: ["home.notifications", "home.room.title"])
    }

    // MARK: - home.loggedOut

    /// §8.3: the signed-out body still renders; it is never hidden behind a
    /// login wall. Guest header, room hero, weather login warning, empty care
    /// state, and the green start link all stay live.
    func testLoggedOutHomeKeepsFigmaBodyVisibleWithLoginLink() {
        let app = XCUIApplication()
        app.launchEnvironment["QA_SKIP_ONBOARDING"] = "1"
        app.launch()

        XCTAssertTrue(app.scrollViews["home.screen"].waitForExistence(timeout: 10))
        XCTAssertFalse(app.navigationBars["홈"].exists)

        XCTAssertEqual(app.staticTexts["home.greeting"].label, "안녕하세요, 게스트님!")
        XCTAssertEqual(app.staticTexts["home.greeting.meta"].label, "위치 미설정 · - °C")
        XCTAssertEqual(app.buttons["home.room.title"].label, "나의 미니 식물원 🏡")

        XCTAssertTrue(app.images["home.room.hero"].exists, "signed-out keeps the room hero")
        XCTAssertTrue(app.staticTexts["home.weather.warning"].exists)
        XCTAssertEqual(
            app.staticTexts["home.weather.warning"].label,
            "로그인하면 내 지역의 날씨 기반 식물 관리 알림을 받을 수 있어요!"
        )

        XCTAssertEqual(app.staticTexts["home.care.badge"].label, "0개")
        XCTAssertTrue(app.staticTexts["home.care.empty"].exists)
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

    // MARK: - home.signInSheet

    /// §6.8: Google above Apple, live dimmed Home behind the sheet, and the
    /// provider screens themselves stay native (no app-drawn credential form).
    func testSignInSheetAtKoreanAX5KeepsProviderActionsAndCopyReadable() {
        let app = XCUIApplication()
        app.launchEnvironment["QA_SKIP_ONBOARDING"] = "1"
        app.launchArguments += [
            "-AppleLanguages", "(ko)",
            "-AppleLocale", "ko_KR",
            "-UIPreferredContentSizeCategoryName",
            "UICTContentSizeCategoryAccessibilityXXXL"
        ]
        app.launch()

        XCTAssertTrue(app.buttons["home.login.link"].waitForExistence(timeout: 10))
        app.buttons["home.login.link"].tap()
        let google = app.buttons["auth.google"]
        let apple = app.buttons["auth.apple"]
        XCTAssertTrue(google.waitForExistence(timeout: 5))
        XCTAssertGreaterThanOrEqual(google.frame.height, 52)
        XCTAssertGreaterThanOrEqual(google.frame.width, 300)
        XCTAssertGreaterThanOrEqual(apple.frame.height, 52)
        XCTAssertEqual(google.label, "Google로 계속하기")
        XCTAssertEqual(app.staticTexts["auth.subtitle"].label, "소셜 계정으로 간편하게 시작하세요")
    }

    func testSignInSheetOrdersGoogleAboveAppleOverLiveDimmedHome() {
        let app = XCUIApplication()
        app.launchEnvironment["QA_SKIP_ONBOARDING"] = "1"
        app.launch()

        XCTAssertTrue(app.buttons["home.login.link"].waitForExistence(timeout: 10))
        app.buttons["home.login.link"].tap()

        let google = app.buttons["auth.google"]
        let apple = app.buttons["auth.apple"]
        XCTAssertTrue(google.waitForExistence(timeout: 5))
        XCTAssertTrue(apple.exists)
        XCTAssertLessThan(
            google.frame.minY,
            apple.frame.minY,
            "§6.8 order is Google then Apple"
        )
        XCTAssertEqual(google.label, "Google로 계속하기")
        XCTAssertTrue(app.staticTexts["auth.title"].exists)
        XCTAssertEqual(app.staticTexts["auth.title"].label, "로그인")
        XCTAssertEqual(app.staticTexts["auth.subtitle"].label, "소셜 계정으로 간편하게 시작하세요")

        XCTAssertTrue(
            app.staticTexts["home.greeting"].exists,
            "the live Home stays behind the dimmed login overlay"
        )
        XCTAssertFalse(
            app.textFields["auth.google.email"].exists,
            "provider credential entry must stay native, never app-drawn"
        )
        XCTAssertFalse(app.secureTextFields["auth.google.password"].exists)

        app.buttons["auth.cancel"].tap()
        XCTAssertTrue(google.waitForNonExistence(timeout: 5))
    }
}
