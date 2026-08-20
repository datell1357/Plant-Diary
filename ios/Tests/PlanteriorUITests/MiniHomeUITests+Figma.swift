import XCTest

/// Figma `myroom-editor` (`35:4`) live surface contract. The frame's own layer
/// skeleton is `editor-header` / `room-canvas-container` / `category-tab-bar` /
/// `items-selector-panel` / `action-footer`, so each of those regions is
/// asserted here as a live, hittable SwiftUI element.
@MainActor
final class MiniHomeFigmaUITests: XCTestCase, MiniHomeUITestSupport {
    func testEditorRendersFigmaHeaderCanvasTabBarTrayAndFooter() {
        let app = figmaEditorApp(token: "figma-anatomy")
        app.launch()
        openFigmaEditor(in: app)

        XCTAssertEqual(app.staticTexts["minihome.editor.title"].label, "마이룸 편집")
        XCTAssertTrue(app.buttons["minihome.close"].isHittable)
        XCTAssertEqual(app.buttons["minihome.save"].label, "저장")
        XCTAssertTrue(app.images["minihome.editor.room"].exists)
        XCTAssertEqual(
            app.staticTexts["minihome.editor.hint"].label,
            "길게 눌러서 가구 이동"
        )
        for category in ["plant", "wall", "floor", "furniture", "decoration"] {
            let tab = app.buttons["minihome.editor.category.\(category)"]
            XCTAssertTrue(tab.exists, "missing category tab: \(category)")
            XCTAssertGreaterThanOrEqual(tab.frame.height, 44)
        }
        XCTAssertTrue(app.buttons["minihome.editor.tray.0"].exists)
        XCTAssertTrue(app.images["minihome.editor.tray.image.0"].exists)
        XCTAssertTrue(app.buttons["minihome.editor.undo"].exists)
        XCTAssertTrue(app.buttons["minihome.editor.reset"].exists)

        // The scroll owner must clear the fixed strips: the last scrolled
        // control cannot be cut off by the category bar.
        app.scrollViews["minihome.editor"].swipeUp()
        let addPlant = app.buttons["minihome.add-plant"]
        XCTAssertTrue(addPlant.waitForExistence(timeout: 5))
        let categoryBar = app.buttons["minihome.editor.category.plant"]
        XCTAssertLessThanOrEqual(
            addPlant.frame.maxY,
            categoryBar.frame.minY,
            "add-plant must not be clipped by the category tab bar"
        )
        attachScreenshot(named: "mini-room-editor-402x874-light")
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

        // Owned inventory drives non-plant trays: the QA fixture owns exactly
        // `item-chair` (furniture), so furniture lists it and decoration is empty.
        let furniture = app.buttons["minihome.editor.category.furniture"]
        furniture.tap()
        XCTAssertEqual(furniture.value as? String, "선택됨")
        XCTAssertEqual(plantTab.value as? String, "선택 안 됨")
        let chair = app.buttons["minihome.editor.tray.0"]
        XCTAssertTrue(chair.waitForExistence(timeout: 5))
        XCTAssertEqual(chair.label, "원목 의자")
        chair.tap()
        XCTAssertTrue(
            app.images["minihome.placement.placement-2"]
                .waitForExistence(timeout: 5)
        )

        app.buttons["minihome.editor.category.decoration"].tap()
        XCTAssertTrue(
            app.staticTexts["minihome.editor.tray.empty"]
                .waitForExistence(timeout: 5)
        )
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

    func testFigmaEditorAtKoreanAX5ReduceMotionKeepsControlsReachable() {
        let app = figmaEditorApp(token: "figma-ax5")
        app.launchEnvironment["QA_MINIHOME_SIZE_CATEGORY"] = "AX5"
        app.launchEnvironment["QA_REDUCE_MOTION"] = "1"
        app.launchArguments += [
            "-AppleLanguages", "(ko)",
            "-AppleLocale", "ko_KR"
        ]
        app.launch()
        openFigmaEditor(in: app)

        XCTAssertTrue(app.buttons["minihome.save"].isHittable)
        XCTAssertTrue(app.buttons["minihome.close"].isHittable)
        XCTAssertTrue(app.buttons["minihome.editor.category.plant"].isHittable)
        let decoration = app.buttons["minihome.editor.category.decoration"]
        guard decoration.isHittable else {
            XCTFail("decoration category must be directly reachable at AX5")
            return
        }
        decoration.tap()
        XCTAssertEqual(decoration.value as? String, "선택됨")
        app.buttons["minihome.editor.category.plant"].tap()
        XCTAssertTrue(app.buttons["minihome.editor.tray.0"].isHittable)
        XCTAssertTrue(app.buttons["minihome.editor.reset"].isHittable)

        // Korean AX5 must not collapse copy into ellipses or collide the tabs.
        let plantTab = app.buttons["minihome.editor.category.plant"]
        let wallTab = app.buttons["minihome.editor.category.wall"]
        // Korean captions must get a wide enough column at AX5; a narrow
        // column is what produces mid-word splits and ellipses.
        XCTAssertGreaterThanOrEqual(
            app.buttons["minihome.editor.tray.0"].frame.width,
            120,
            "AX5 tray caption column is too narrow for Korean plant names"
        )
        XCTAssertFalse(plantTab.label.contains("\u{2026}"))
        XCTAssertGreaterThanOrEqual(
            wallTab.frame.minX,
            plantTab.frame.maxX,
            "category tabs must not overlap at AX5"
        )
        let screen = app.windows.element(boundBy: 0).frame
        for identifier in [
            "minihome.save", "minihome.editor.reset", "minihome.editor.tray.0"
        ] {
            XCTAssertTrue(
                screen.contains(app.buttons[identifier].frame),
                "\(identifier) must stay fully onscreen at AX5"
            )
        }
        attachScreenshot(named: "mini-room-editor-korean-ax5-reduce-motion")
    }
}
