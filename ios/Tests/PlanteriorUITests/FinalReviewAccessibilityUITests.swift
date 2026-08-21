import XCTest

@MainActor
extension CaptureFlowUITests {
    func testAX5StickyActionsReserveVisibleContentSpace() {
        let app = XCUIApplication()
        app.launchArguments += accessibilityArguments
        launchCapture(app, environment: ["QA_PHOTO_FIXTURE": "valid"])
        openCamera(app)

        let photo = app.images["photo.review"]
        let caption = app.staticTexts["capture.review.caption"]
        let identify = app.buttons["photo.acknowledge"]
        XCTAssertTrue(photo.waitForExistence(timeout: 10))
        XCTAssertLessThanOrEqual(
            photo.frame.maxY,
            identify.frame.minY,
            "the review action must not cover the selected photo"
        )
        XCTAssertLessThanOrEqual(
            caption.frame.maxY,
            identify.frame.minY,
            "the review action must not cover the review caption"
        )
        let screenshot = XCTAttachment(screenshot: app.screenshot())
        screenshot.name = "ax5-photo-review-final"
        screenshot.lifetime = .keepAlways
        add(screenshot)

        identify.tap()
        app.alerts["사진 처리 안내"].buttons["동의하고 계속"].tap()
        let species = app.staticTexts["capture.result.species"]
        let register = app.buttons["capture.result.register"]
        XCTAssertTrue(species.waitForExistence(timeout: 15))
        XCTAssertLessThanOrEqual(
            species.frame.maxY,
            register.frame.minY,
            "the registration action must not cover the selected species"
        )
    }
}

@MainActor
extension HomeDashboardUITests {
    func testAX5PaidRenameContainsTheFullBalanceInsideSaveAction() {
        let app = XCUIApplication()
        applyAuthenticatedFigmaLaunch(app)
        app.launchEnvironment["QA_HOME_RENAME_MODE"] = "paid"
        app.launchEnvironment["QA_HOME_SIZE_CATEGORY"] = "AX5"
        app.launchArguments += accessibilityArguments
        app.launch()

        let title = app.buttons["home.room.title"]
        XCTAssertTrue(title.waitForExistence(timeout: 10))
        title.tap()
        let save = app.buttons["home.rename.save"]
        let balance = app.staticTexts["home.rename.balance"]
        XCTAssertTrue(balance.waitForExistence(timeout: 5))
        XCTAssertTrue(save.frame.contains(balance.frame))
        XCTAssertGreaterThan(
            balance.frame.minY,
            app.staticTexts["home.rename.cost"].frame.minY,
            "AX5 spending balance must use its own visible line"
        )
        XCTAssertFalse(balance.label.contains("\u{2026}"))
    }
}

@MainActor
extension MiniHomeFigmaUITests {
    func testAX5RoomCanvasRetainsMeaningfulHeight() {
        let app = figmaEditorApp(token: "final-review-canvas")
        app.launchEnvironment["QA_MINIHOME_SIZE_CATEGORY"] = "AX5"
        app.launchArguments += accessibilityArguments
        app.launch()
        openFigmaEditor(in: app)

        let canvas = app.otherElements["minihome.editor.canvas"]
        XCTAssertTrue(canvas.waitForExistence(timeout: 10))
        let viewport = app.scrollViews["minihome.editor"].frame
        XCTAssertGreaterThanOrEqual(
            canvas.frame.intersection(viewport).height,
            180
        )
    }
}

@MainActor
extension InventoryUITests {
    func testAX5WarehouseMediaPreservesTheScreenGutter() {
        let app = inventoryApp()
        app.launchEnvironment["QA_INVENTORY_SIZE_CATEGORY"] = "AX5"
        app.launchArguments += accessibilityArguments
        app.launch()
        openStorage(in: app)

        let image = app.images["storage.image.item-chair"]
        XCTAssertTrue(image.waitForExistence(timeout: 10))
        let screen = app.windows.element(boundBy: 0).frame
        XCTAssertGreaterThanOrEqual(image.frame.minX, screen.minX + 16)
        XCTAssertLessThanOrEqual(image.frame.maxX, screen.maxX - 16)
    }
}

@MainActor
extension PlantCollectionFigmaUITests {
    func testAX5EmptyIllustrationAndRemedyChromeKeepTheirGeometry() {
        let empty = collectionApp(empty: true)
        empty.launchArguments += accessibilityArguments
        empty.launch()
        let illustration = empty.images["collection.empty.illustration"]
        XCTAssertTrue(illustration.waitForExistence(timeout: 10))
        XCTAssertGreaterThanOrEqual(illustration.frame.height, 96)
        empty.terminate()

        let remedy = collectionApp(empty: false)
        remedy.launchArguments += accessibilityArguments
        remedy.launch()
        let row = remedy.buttons["collection.row.0"]
        XCTAssertTrue(row.waitForExistence(timeout: 10))
        row.tap()
        let link = remedy.buttons["plant.detail.remedy"]
        scrollToHittable(link, in: remedy.scrollViews["plant.detail.screen"])
        link.tap()
        let context = remedy.staticTexts["remedy.context"]
        let navigationBar = remedy.navigationBars["증상 대처법"]
        XCTAssertTrue(context.waitForExistence(timeout: 5))
        for content in [
            context,
            remedy.buttons["remedy.symptom.0"],
            remedy.staticTexts["remedy.cause.0"],
            remedy.staticTexts["remedy.action.0"]
        ] where content.exists {
            XCTAssertFalse(
                navigationBar.frame.intersects(content.frame),
                "remedy content must stay below navigation chrome"
            )
        }
    }

    private func collectionApp(empty: Bool) -> XCUIApplication {
        let app = XCUIApplication()
        app.launchEnvironment["QA_SKIP_ONBOARDING"] = "1"
        app.launchEnvironment["QA_AUTHENTICATED"] = "1"
        app.launchEnvironment["QA_INITIAL_TAB"] = "collection"
        app.launchEnvironment["QA_COLLECTION_FIXTURE"] = "1"
        app.launchEnvironment["QA_RESET_COLLECTION"] = "1"
        app.launchEnvironment["QA_WATERING_TODAY"] = "2026-08-11"
        if empty {
            app.launchEnvironment["QA_COLLECTION_EMPTY"] = "1"
        }
        return app
    }
}

@MainActor
extension SettingsDeletionUITests {
    func testAX5RegionLabelKeepsAReservedIconColumn() {
        let app = figmaSettingsApp()
        app.launchEnvironment["QA_SETTINGS_SIZE_CATEGORY"] = "AX5"
        app.launchArguments += accessibilityArguments
        app.launch()
        openFigmaSettings(in: app)

        let region = app.buttons["settings.region.open"]
        scrollToHittable(region, in: app.scrollViews["settings.screen"])
        region.tap()
        let card = app.buttons["weather.use-current-location"]
        let label = app.staticTexts["현재 위치로 설정"]
        XCTAssertTrue(label.waitForExistence(timeout: 5))
        XCTAssertGreaterThanOrEqual(label.frame.minX, card.frame.minX + 56)
    }
}

private let accessibilityArguments = [
    "-AppleLanguages", "(ko)",
    "-AppleLocale", "ko_KR",
    "-UIPreferredContentSizeCategoryName",
    "UICTContentSizeCategoryAccessibilityXXXL"
]

@MainActor
private func scrollToHittable(
    _ element: XCUIElement,
    in scrollView: XCUIElement
) {
    for _ in 0 ..< 8 where !element.isHittable {
        scrollView.swipeUp()
    }
    XCTAssertTrue(element.isHittable)
}
