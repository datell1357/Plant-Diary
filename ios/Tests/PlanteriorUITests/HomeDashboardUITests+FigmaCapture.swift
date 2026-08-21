import XCTest

/// Visual-matrix capture harness for the Figma Home states that are only
/// reachable through a real interaction, so `simctl` launch-only capture
/// cannot produce them: `home.rename.free`, `home.rename.paid`, and
/// `home.signInSheet`.
///
/// Behaviour is already asserted by `HomeDashboardUITests+Figma` and
/// `+FigmaRename`; this file only drives each state and attaches a named
/// screenshot. Assertions here stay structural (identifier, hittability,
/// stacking order) so the harness never pins copy or pixel geometry.
extension HomeDashboardUITests {
    func testCaptureRenameFreeState() {
        let app = XCUIApplication()
        applyAuthenticatedFigmaLaunch(app)
        app.launchEnvironment["QA_HOME_RENAME_MODE"] = "free"
        app.launch()

        openRenameDialog(app)
        attachFigmaScreenshot(named: "home-rename-free")
    }

    func testCaptureRenamePaidState() {
        let app = XCUIApplication()
        applyAuthenticatedFigmaLaunch(app)
        app.launchEnvironment["QA_HOME_RENAME_MODE"] = "paid"
        app.launch()

        openRenameDialog(app)
        XCTAssertTrue(app.staticTexts["home.rename.balance"].exists)
        attachFigmaScreenshot(named: "home-rename-paid")
    }

    func testCaptureSignInSheetState() {
        let app = XCUIApplication()
        app.launchEnvironment["QA_SKIP_ONBOARDING"] = "1"
        app.launch()

        let link = app.buttons["home.login.link"]
        XCTAssertTrue(link.waitForExistence(timeout: 10))
        link.tap()

        let google = app.buttons["auth.google"]
        let apple = app.buttons["auth.apple"]
        XCTAssertTrue(google.waitForExistence(timeout: 5))
        XCTAssertTrue(apple.exists)
        XCTAssertLessThan(google.frame.minY, apple.frame.minY)
        attachFigmaScreenshot(named: "home-sign-in-sheet")
    }

    func testAuthenticatedHomeResetsFirstRunWeatherAlertBanner() {
        let seeded = XCUIApplication()
        applyAuthenticatedFigmaLaunch(seeded)
        seeded.launchEnvironment["QA_RESET_WEATHER"] = "1"
        seeded.launch()
        let seededCount = seeded.staticTexts["weather.alert-count"]
        for _ in 0 ..< 6 where !seededCount.exists {
            seeded.swipeUp()
        }
        XCTAssertEqual(seededCount.label, "예정 위험 알림 4건")
        seeded.terminate()

        let fresh = XCUIApplication()
        applyAuthenticatedFigmaLaunch(fresh)
        fresh.launch()
        let freshCount = fresh.staticTexts["weather.alert-count"]
        for _ in 0 ..< 6 where !freshCount.exists {
            fresh.swipeUp()
        }
        XCTAssertEqual(freshCount.label, "예정 위험 알림 4건")
    }

    private func openRenameDialog(_ app: XCUIApplication) {
        let title = app.buttons["home.room.title"]
        XCTAssertTrue(title.waitForExistence(timeout: 10))
        title.tap()

        let dialog = app.descendants(matching: .any)["home.rename.dialog"]
        XCTAssertTrue(dialog.waitForExistence(timeout: 5))
        XCTAssertTrue(app.textFields["home.rename.input"].exists)
        XCTAssertTrue(app.buttons["home.rename.save"].isHittable)
    }

    private func attachFigmaScreenshot(named name: String) {
        let attachment = XCTAttachment(
            screenshot: XCUIScreen.main.screenshot()
        )
        attachment.name = name
        attachment.lifetime = .keepAlways
        add(attachment)
    }

    /// Accessibility: exactly one vertical scroll owner on Home, and it is the
    /// identified Home surface itself.
    func assertSingleVerticalScrollOwner(_ app: XCUIApplication) {
        XCTAssertEqual(
            app.scrollViews.count,
            1,
            "Home must expose exactly one vertical scroll owner"
        )
        XCTAssertEqual(app.scrollViews.element(boundBy: 0).identifier, "home.screen")
    }

    func assertMinimumTargets(
        _ app: XCUIApplication,
        identifiers: [String]
    ) {
        for identifier in identifiers {
            let control = app.buttons[identifier]
            XCTAssertTrue(control.exists, "\(identifier) should exist")
            XCTAssertGreaterThanOrEqual(
                control.frame.height.rounded(),
                44,
                "\(identifier) must keep a 44pt target"
            )
        }
    }
}
