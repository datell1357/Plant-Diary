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
        XCTAssertEqual(
            caption.label,
            "식물의 초점이 맞고 잎이 선명한지 확인해주세요"
        )
        XCTAssertGreaterThanOrEqual(
            caption.frame.height,
            70,
            "the complete AX5 helper needs its multiline visual frame"
        )
        XCTAssertTrue(identify.isHittable)
        XCTAssertLessThanOrEqual(
            caption.frame.maxY,
            identify.frame.minY,
            "the review action must not cover the review caption"
        )
        XCTAssertEqual(
            app.descendants(matching: .any)
                .matching(identifier: "capture.review.content").count,
            0,
            "a visual geometry probe must not create an accessibility stop"
        )
        XCTAssertEqual(
            app.images.matching(
                NSPredicate(format: "label == %@", "촬영한 식물 사진")
            ).count,
            1,
            "only the actual selected image should describe the photo"
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
        XCTAssertGreaterThanOrEqual(
            save.frame.maxY - balance.frame.maxY,
            8,
            "AX5 balance glyphs need painted-bottom clearance inside Save"
        )
        XCTAssertGreaterThan(
            balance.frame.minY,
            app.staticTexts["home.rename.cost"].frame.minY,
            "AX5 spending balance must use its own visible line"
        )
        XCTAssertEqual(balance.label, "보유 12")
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
        let viewport = app.descendants(matching: .any)["minihome.editor"].frame
        XCTAssertGreaterThanOrEqual(
            canvas.frame.intersection(viewport).height,
            180
        )
        let undo = app.buttons["minihome.editor.undo"]
        XCTAssertTrue(undo.exists)
        XCTAssertEqual(undo.label, "되돌리기")
        XCTAssertFalse(undo.label.contains("\u{2026}"))
        XCTAssertGreaterThanOrEqual(undo.frame.height, 44)
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
        let emptyIdentity = CollectionQAFixtureIdentity(
            testID: #function,
            variant: "empty",
            mode: .standard,
            empty: true
        )
        let empty = app(identity: emptyIdentity, empty: true)
        empty.launchArguments += accessibilityArguments
        empty.launch()
        waitForCollectionQAFixture(in: empty, identity: emptyIdentity)
        let illustration = empty.images["collection.empty.illustration"]
        XCTAssertTrue(illustration.exists)
        XCTAssertEqual(
            illustration.frame.height,
            96,
            accuracy: 0.5,
            "the intended 96pt illustration may differ only by raster precision"
        )
        empty.terminate()

        let remedyIdentity = CollectionQAFixtureIdentity(
            testID: #function,
            variant: "remedy",
            mode: .standard,
            empty: false
        )
        let remedy = app(identity: remedyIdentity, empty: false)
        remedy.launchArguments += accessibilityArguments
        remedy.launch()
        waitForCollectionQAFixture(in: remedy, identity: remedyIdentity)
        let row = remedy.buttons["collection.row.0"]
        XCTAssertTrue(row.exists)
        row.tap()
        let link = remedy.buttons["plant.detail.remedy"]
        scrollToHittable(link, in: remedy.scrollViews["plant.detail.screen"])
        link.tap()
        let context = remedy.staticTexts["remedy.context"]
        let topBar = remedy.otherElements["remedy.top-bar"]
        XCTAssertTrue(context.waitForExistence(timeout: 5))
        for content in [
            context,
            remedy.buttons["remedy.symptom.0"],
            remedy.staticTexts["remedy.cause.0"],
            remedy.staticTexts["remedy.action.0"]
        ] where content.exists {
            XCTAssertFalse(
                topBar.frame.intersects(content.frame),
                "remedy content must stay below navigation chrome"
            )
        }
    }

    private func app(identity: CollectionQAFixtureIdentity, empty: Bool) -> XCUIApplication {
        let app = XCUIApplication()
        configureCollectionQAFixture(app, identity: identity, mode: .standard)
        app.launchEnvironment["QA_WATERING_TODAY"] = "2026-08-11"
        if empty {
            app.launchEnvironment["QA_COLLECTION_EMPTY"] = "1"
        }
        return app
    }
}
