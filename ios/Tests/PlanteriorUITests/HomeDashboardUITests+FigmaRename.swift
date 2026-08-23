import XCTest

/// Figma `home-screen-rename-free` and `home-screen-rename-paid`
/// (figma-analysis §6.9): shared dialog geometry, free vs paid cost
/// affordance, explicit-save charging, and AX5 / Reduce Motion behaviour.
extension HomeDashboardUITests {
    // MARK: - home.rename.free

    /// §6.9 free variant: exact title/input/close/save geometry plus the
    /// "(1회 무료)" affordance on the first account-scoped rename.
    func testRenameDialogShowsFreeAffordanceAndFigmaGeometryOnFirstUse() {
        let app = XCUIApplication()
        applyAuthenticatedFigmaLaunch(app)
        app.launchEnvironment["QA_HOME_RENAME_MODE"] = "free"
        app.launch()

        XCTAssertTrue(app.buttons["home.room.title"].waitForExistence(timeout: 10))
        app.buttons["home.room.title"].tap()

        let dialog = app.descendants(matching: .any)["home.rename.dialog"]
        XCTAssertTrue(dialog.waitForExistence(timeout: 5))
        XCTAssertEqual(app.staticTexts["home.rename.title"].label, "홈피 이름 변경")

        let input = app.textFields["home.rename.input"]
        XCTAssertTrue(input.exists)
        XCTAssertEqual(input.placeholderValue, "새로운 이름을 입력하세요")

        let close = app.buttons["home.rename.close"]
        let save = app.buttons["home.rename.save"]
        XCTAssertTrue(close.exists)
        XCTAssertTrue(save.exists)

        // §6.9 geometry: 320 card, 48 input, 42 primary, 32 close.
        XCTAssertEqual(dialog.frame.width, 320, accuracy: 1)
        XCTAssertEqual(input.frame.height, 48, accuracy: 2)
        XCTAssertEqual(save.frame.height, 42, accuracy: 2)
        XCTAssertEqual(close.frame.width, 32, accuracy: 2)
        XCTAssertGreaterThan(input.frame.minY, close.frame.minY)
        XCTAssertGreaterThan(save.frame.minY, input.frame.minY)

        XCTAssertEqual(app.staticTexts["home.rename.cost"].label, "(1회 무료)")
        XCTAssertFalse(
            app.keyboards.element.exists,
            "the reference dialog presents without stealing focus"
        )

        input.tap()
        input.typeText("민지의 정원")
        save.tap()
        XCTAssertTrue(dialog.waitForNonExistence(timeout: 5))
        XCTAssertEqual(app.buttons["home.room.title"].label, "민지의 정원 🏡")
    }

    // MARK: - home.rename.paid

    /// §6.9 paid variant: after the free use is spent the same dialog exposes
    /// the coin cost 5, the committed name survives a relaunch through
    /// `LocalMiniHomeRepository`, and closing never charges the balance.
    func testSecondRenameChargesFiveOnlyOnExplicitSaveAndPersists() {
        let app = XCUIApplication()
        applyAuthenticatedFigmaLaunch(app)
        app.launchEnvironment["QA_HOME_RENAME_MODE"] = "paid"
        app.launch()

        XCTAssertTrue(app.buttons["home.room.title"].waitForExistence(timeout: 10))
        app.buttons["home.room.title"].tap()
        XCTAssertTrue(renameDialog(app).waitForExistence(timeout: 5))

        XCTAssertEqual(app.staticTexts["home.rename.cost"].label, "5")
        XCTAssertTrue(app.images["home.rename.cost.coin"].exists)
        XCTAssertFalse(
            app.staticTexts["home.rename.balance"].exists,
            "the paid reference shows only the five-credit price"
        )
        XCTAssertEqual(app.buttons["home.rename.save"].value as? String, "보유 12")
        XCTAssertFalse(app.keyboards.element.exists)

        // Dismissing must never charge.
        app.buttons["home.rename.close"].tap()
        XCTAssertTrue(renameDialog(app).waitForNonExistence(timeout: 5))
        app.buttons["home.room.title"].tap()
        XCTAssertEqual(
            app.buttons["home.rename.save"].value as? String,
            "보유 12",
            "closing the dialog must not silently charge"
        )

        let input = app.textFields["home.rename.input"]
        input.tap()
        input.typeText("이끼 정원")
        app.buttons["home.rename.save"].tap()
        XCTAssertTrue(renameDialog(app).waitForNonExistence(timeout: 5))
        XCTAssertEqual(app.buttons["home.room.title"].label, "이끼 정원 🏡")

        app.buttons["home.room.title"].tap()
        XCTAssertEqual(app.buttons["home.rename.save"].value as? String, "보유 7")
        app.buttons["home.rename.close"].tap()
        app.terminate()

        // The committed room title survives through LocalMiniHomeRepository.
        let relaunched = XCUIApplication()
        applyAuthenticatedFigmaLaunch(relaunched)
        relaunched.launchEnvironment.removeValue(forKey: "QA_MINIHOME_RESET_TOKEN")
        relaunched.launch()
        XCTAssertTrue(relaunched.buttons["home.room.title"].waitForExistence(timeout: 10))
        XCTAssertEqual(relaunched.buttons["home.room.title"].label, "이끼 정원 🏡")
    }

    /// Keyboard Done only finishes editing. A paid rename remains pending and
    /// uncharged until the fee-bearing Save button is explicitly activated.
    func testPaidRenameKeyboardDoneDoesNotCommitOrCharge() {
        let app = XCUIApplication()
        applyAuthenticatedFigmaLaunch(app)
        app.launchEnvironment["QA_HOME_RENAME_MODE"] = "paid"
        app.launch()

        XCTAssertTrue(app.buttons["home.room.title"].waitForExistence(timeout: 10))
        app.buttons["home.room.title"].tap()
        let dialog = renameDialog(app)
        XCTAssertTrue(dialog.waitForExistence(timeout: 5))

        let input = app.textFields["home.rename.input"]
        input.tap()
        input.typeText("명시적 결제 정원\n")

        XCTAssertTrue(
            dialog.waitForExistence(timeout: 2),
            "keyboard submission must not commit a paid rename"
        )
        XCTAssertEqual(app.buttons["home.rename.save"].value as? String, "보유 12")
        XCTAssertEqual(app.buttons["home.room.title"].label, "민지의 미니 식물원 🏡")
    }

    /// §6.9: an unaffordable rename is disabled rather than silently failing.
    func testInsufficientBalanceDisablesSaveWithoutCharging() {
        let app = XCUIApplication()
        applyAuthenticatedFigmaLaunch(app)
        app.launchEnvironment["QA_HOME_RENAME_MODE"] = "insufficient"
        app.launch()

        XCTAssertTrue(app.buttons["home.room.title"].waitForExistence(timeout: 10))
        app.buttons["home.room.title"].tap()
        XCTAssertTrue(renameDialog(app).waitForExistence(timeout: 5))

        let input = app.textFields["home.rename.input"]
        input.tap()
        input.typeText("불가능한 이름")
        XCTAssertFalse(app.buttons["home.rename.save"].isEnabled)
        XCTAssertTrue(app.staticTexts["home.rename.insufficient"].exists)
    }

    /// Korean AX5 must reflow the Figma Home without hiding any action, and
    /// Reduce Motion must strip the rename dialog animation.
    func testAX5AndReduceMotionKeepRenameDialogUsable() {
        let app = XCUIApplication()
        applyAuthenticatedFigmaLaunch(app)
        app.launchEnvironment["QA_HOME_SIZE_CATEGORY"] = "AX5"
        app.launchEnvironment["QA_REDUCE_MOTION"] = "1"
        app.launchEnvironment["QA_HOME_RENAME_MODE"] = "free"
        app.launch()

        let title = app.buttons["home.room.title"]
        XCTAssertTrue(title.waitForExistence(timeout: 10))
        XCTAssertTrue(title.isHittable, "AX5 must not push the rename action off-screen")
        title.tap()

        let dialog = app.descendants(matching: .any)["home.rename.dialog"]
        XCTAssertTrue(dialog.waitForExistence(timeout: 5))
        XCTAssertTrue(
            app.descendants(matching: .any)["home.rename.dialog.reduce-motion"].exists,
            "Reduce Motion removes the rename dialog animation"
        )
        for identifier in ["home.rename.close", "home.rename.save"] {
            XCTAssertTrue(
                app.buttons[identifier].isHittable,
                "\(identifier) must stay reachable at AX5"
            )
        }
    }

    // MARK: - Support

    func applyAuthenticatedFigmaLaunch(_ app: XCUIApplication) {
        app.launchEnvironment["QA_SKIP_ONBOARDING"] = "1"
        app.launchEnvironment["QA_AUTHENTICATED"] = "1"
        app.launchEnvironment["QA_COLLECTION_FIXTURE"] = "1"
        app.launchEnvironment["QA_HOME_FIXTURE"] = "1"
        app.launchEnvironment["QA_HOME_PROFILE_NAME"] = "민지"
        app.launchEnvironment["QA_HOME_WEATHER_STATE"] = "high-dry"
        app.launchEnvironment["QA_WEATHER_MANUAL_REGION"] = "manual-seoul"
        app.launchEnvironment["QA_WEATHER_NOW"] = "2026-08-11T03:00:00Z"
        app.launchEnvironment["QA_RESET_WEATHER"] = "1"
        app.launchEnvironment["QA_WATERING_TODAY"] = "2026-08-11"
        app.launchEnvironment["QA_MINIHOME_NOW"] = "2026-08-11T00:00:00Z"
        app.launchEnvironment["QA_MINIHOME_RESET_TOKEN"] = UUID().uuidString
    }

    /// The rename modal carries `.isModal`, so it surfaces as an alert-class
    /// element rather than a plain container.
    func renameDialog(_ app: XCUIApplication) -> XCUIElement {
        app.descendants(matching: .any)["home.rename.dialog"]
    }
}
