import XCTest

@MainActor
extension MiniHomeFigmaUITests {
    func testRoomAX5PaintsFullUndoAndOnlyLiveCanvasImages() {
        let app = figmaEditorApp(token: "ax-painted-copy")
        app.launchEnvironment["QA_MINIHOME_SIZE_CATEGORY"] = "AX5"
        app.launchEnvironment["QA_MINIHOME_FIGMA_FIXTURE"] = "1"
        app.launchArguments += ["-AppleLanguages", "(ko)", "-AppleLocale", "ko_KR"]
        app.launch()
        openFigmaEditor(in: app)

        let undo = app.buttons["minihome.editor.undo"]
        let reset = app.buttons["minihome.editor.reset"]
        assertRoomAX5Controls(undo: undo, reset: reset)

        let canvas = app.otherElements["minihome.editor.canvas"]
        assertRoomAX5Placements(in: canvas, app: app)
        assertAXTraversal(
            in: app,
            isExactly: [
                "minihome.placement.figma-room-placement-2",
                "minihome.placement.figma-room-placement-1",
                "minihome.placement.figma-room-placement-3",
                "minihome.editor.category.plant",
                "minihome.editor.tray.0",
                "minihome.editor.undo",
                "minihome.editor.reset"
            ]
        )
        attachAXHierarchy(
            named: "room-ax5-order",
            elements: [
                ("canvas", canvas),
                ("category", app.buttons["minihome.editor.category.plant"]),
                ("tray", app.buttons["minihome.editor.tray.0"]),
                ("undo", undo),
                ("reset", reset)
            ]
        )
    }

    private func assertRoomAX5Controls(
        undo: XCUIElement,
        reset: XCUIElement
    ) {
        XCTAssertEqual(undo.label, "되돌리기")
        XCTAssertFalse(undo.label.contains("\u{2026}"))
        XCTAssertGreaterThan(
            undo.frame.width,
            reset.frame.width,
            "the longer painted undo caption needs more width than reset at AX5"
        )
    }

    private func assertRoomAX5Placements(
        in canvas: XCUIElement,
        app: XCUIApplication
    ) {
        let placementImages = canvas.images.allElementsBoundByIndex.filter {
            $0.identifier.hasPrefix("minihome.placement.")
        }
        XCTAssertEqual(placementImages.count, 3)
        XCTAssertEqual(
            placementImages.map(\.identifier),
            [
                "minihome.placement.figma-room-placement-2",
                "minihome.placement.figma-room-placement-1",
                "minihome.placement.figma-room-placement-3"
            ],
            "canvas traversal must follow stable painted z-order"
        )
        for placement in placementImages {
            XCTAssertEqual(
                app.images.matching(identifier: placement.identifier).count,
                1,
                "the covered committed room must not repeat \(placement.identifier)"
            )
        }
        XCTAssertEqual(
            Set(placementImages.map(\.label)).count,
            placementImages.count,
            "each live placement needs a unique spoken identity"
        )
    }
}
