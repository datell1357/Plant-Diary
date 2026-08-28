import XCTest

extension HomeDashboardUITests {
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
