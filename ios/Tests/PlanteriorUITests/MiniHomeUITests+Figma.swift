import XCTest

/// Figma `myroom-editor` (`35:4`) live surface contract. The frame's own layer
/// skeleton is `editor-header` / `room-canvas-container` / `category-tab-bar` /
/// `items-selector-panel` / `action-footer`. The capture is taken from the
/// initial state; scrolling must never be needed to manufacture the reference.
@MainActor
final class MiniHomeFigmaUITests: XCTestCase, MiniHomeUITestSupport {
    func testEditorRendersFigmaHeaderCanvasTabBarTrayAndFooter() {
        let app = figmaEditorApp(token: "figma-anatomy")
        app.launchEnvironment["QA_MINIHOME_FIGMA_FIXTURE"] = "1"
        app.launch()
        openFigmaEditor(in: app)

        XCTAssertEqual(app.staticTexts["minihome.editor.title"].label, "마이룸 편집")
        XCTAssertTrue(app.buttons["minihome.close"].isHittable)
        XCTAssertEqual(app.buttons["minihome.save"].label, "저장")
        XCTAssertFalse(
            app.images["minihome.editor.room"].exists,
            "the decorative room base must not become a VoiceOver stop"
        )
        XCTAssertEqual(
            app.staticTexts["minihome.editor.hint"].label,
            "길게 눌러서 가구 이동"
        )
        for category in ["plant", "wall", "floor", "furniture", "decoration"] {
            let tab = app.buttons["minihome.editor.category.\(category)"]
            XCTAssertTrue(tab.exists, "missing category tab: \(category)")
            XCTAssertGreaterThanOrEqual(tab.frame.height, 44)
        }
        let firstPlant = app.buttons["minihome.editor.tray.0"]
        XCTAssertTrue(firstPlant.exists)
        XCTAssertEqual(firstPlant.value as? String, "선택됨")
        XCTAssertTrue(app.buttons["minihome.editor.tray.4"].exists)
        XCTAssertTrue(app.images["minihome.editor.tray.image.0"].exists)
        XCTAssertTrue(app.buttons["minihome.editor.undo"].exists)
        XCTAssertTrue(app.buttons["minihome.editor.reset"].exists)

        if app.windows.element(boundBy: 0).frame.width >= 400 {
            assertEditorGeometry(in: app, firstPlant: firstPlant)
        } else {
            assertCompactEditorGeometry(in: app, firstPlant: firstPlant)
        }
        assertDefaultCanvasHasNoPlacementBasePixels(in: app)
        XCTAssertFalse(app.textFields["minihome.room-name"].exists)
        XCTAssertFalse(app.buttons["minihome.add-plant"].exists)
        attachScreenshot(named: "mini-room-editor-402x874-light")

        // Secondary operations remain available without changing the reference
        // surface: tapping its title opens the room-settings action.
        app.staticTexts["minihome.editor.title"].tap()
        XCTAssertTrue(
            app.textFields["minihome.room-name"].waitForExistence(timeout: 5)
        )
        let addPlant = app.buttons["minihome.add-plant"]
        XCTAssertTrue(addPlant.isHittable)
        XCTAssertGreaterThanOrEqual(addPlant.frame.width, 44)
        XCTAssertGreaterThanOrEqual(addPlant.frame.height, 44)
        XCTAssertTrue(
            app.windows.element(boundBy: 0).frame.contains(addPlant.frame),
            "add-plant must remain fully visible inside room settings"
        )
        XCTAssertTrue(app.staticTexts["minihome.state"].exists)
    }

    private func assertCompactEditorGeometry(
        in app: XCUIApplication,
        firstPlant: XCUIElement
    ) {
        let canvas = app.otherElements["minihome.editor.canvas"]
        let categoryBar = app.otherElements["minihome.editor.category-bar"]
        let undo = app.buttons["minihome.editor.undo"]

        XCTAssertEqual(canvas.frame, CGRect(x: 16, y: 199, width: 358, height: 330))
        XCTAssertEqual(categoryBar.frame.minY, 598, accuracy: 0.5)
        XCTAssertEqual(firstPlant.frame.minY, 669, accuracy: 0.5)
        XCTAssertEqual(undo.frame.minY, 770, accuracy: 0.5)
    }

    func testTrayCategorySelectionAndTapPlaceOneRoomItem() {
        let app = figmaEditorApp(token: "figma-tray")
        app.launchEnvironment["QA_INVENTORY_FIXTURE"] = "1"
        app.launchEnvironment["QA_INVENTORY_NOW"] = "2026-08-11T02:00:00Z"
        app.launchEnvironment["QA_INVENTORY_RESET_TOKEN"] =
            "figma-tray-\(UUID())"
        app.launch()
        openFigmaEditor(in: app)

        let plantTab = app.buttons["minihome.editor.category.plant"]
        XCTAssertEqual(plantTab.value as? String, "선택됨")
        let firstPlant = app.buttons["minihome.editor.tray.0"]
        firstPlant.tap()
        XCTAssertEqual(firstPlant.value as? String, "선택됨")
        XCTAssertTrue(
            app.images["minihome.placement.placement-1"]
                .waitForExistence(timeout: 5)
        )

        // The canonical 12-owned fixture preserves catalog order. Furniture
        // therefore starts with `item-mini-shelf` / `미니 책장`.
        let furniture = app.buttons["minihome.editor.category.furniture"]
        furniture.tap()
        XCTAssertEqual(furniture.value as? String, "선택됨")
        XCTAssertEqual(plantTab.value as? String, "선택 안 됨")
        let shelf = app.buttons["minihome.editor.tray.0"]
        XCTAssertTrue(shelf.waitForExistence(timeout: 5))
        XCTAssertEqual(shelf.identifier, "minihome.editor.tray.0")
        XCTAssertEqual(shelf.label, "미니 책장")
        shelf.tap()
        XCTAssertTrue(
            app.images["minihome.placement.placement-2"]
                .waitForExistence(timeout: 5)
        )

        // Decoration filtering keeps the same canonical order and begins with
        // the owned `item-small-rug` / `작은 러그`, not an empty state.
        let decoration = app.buttons[
            "minihome.editor.category.decoration"
        ]
        decoration.tap()
        XCTAssertEqual(decoration.value as? String, "선택됨")
        XCTAssertEqual(furniture.value as? String, "선택 안 됨")
        XCTAssertFalse(app.staticTexts["minihome.editor.tray.empty"].exists)
        let rug = app.buttons["minihome.editor.tray.0"]
        XCTAssertTrue(rug.waitForExistence(timeout: 5))
        XCTAssertEqual(rug.identifier, "minihome.editor.tray.0")
        XCTAssertEqual(rug.label, "작은 러그")
    }

    func testUndoAndResetRestoreDraftWithoutTouchingCommittedRoom() {
        let app = figmaEditorApp(token: "figma-undo")
        app.launch()
        openFigmaEditor(in: app)

        app.buttons["minihome.editor.tray.0"].tap()
        let placement = app.images["minihome.placement.placement-1"]
        XCTAssertTrue(placement.waitForExistence(timeout: 5))
        app.buttons["minihome.editor.tray.1"].tap()
        XCTAssertTrue(
            app.images["minihome.placement.placement-2"]
                .waitForExistence(timeout: 5)
        )

        app.buttons["minihome.editor.undo"].tap()
        XCTAssertTrue(
            app.images["minihome.placement.placement-2"]
                .waitForNonExistence(timeout: 5)
        )
        XCTAssertTrue(placement.exists)

        app.buttons["minihome.editor.reset"].tap()
        XCTAssertTrue(placement.waitForNonExistence(timeout: 5))
    }
}
