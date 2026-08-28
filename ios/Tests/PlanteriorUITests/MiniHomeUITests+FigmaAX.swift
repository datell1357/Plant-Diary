import XCTest

/// One editor control that must stay fully onscreen at AX5, with the exact
/// Korean caption it has to paint.
struct MiniHomeAX5ReachStop {
    let identifier: String
    let element: XCUIElement
    let label: String
}

@MainActor
extension MiniHomeFigmaUITests {
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

        assertAXHeader(in: app)
        assertAXCategoryControls(in: app)
        assertAX5ControlsStayInsideViewport(in: app)
        assertAXTrayCellsAndRenderedPixels(in: app)
        attachAXHierarchy(
            named: "minihome-ax5-reach",
            elements: axReachEvidenceStops(in: app)
        )
        attachScreenshot(named: "mini-room-editor-korean-ax5-reduce-motion")
    }

    /// Every editor control the customer needs must paint its complete Korean
    /// caption inside the window at AX5. The editor declares no scroll owner
    /// of its own — the room region and both wrapped strips are plain
    /// containers — so "reachable" here means fully onscreen, not scrollable.
    private func assertAX5ControlsStayInsideViewport(in app: XCUIApplication) {
        let screen = app.windows.element(boundBy: 0).frame
        for stop in Self.ax5ReachableControls(in: app) {
            let control = stop.element
            XCTAssertTrue(
                control.exists,
                "missing AX5 control: \(stop.identifier)"
            )
            XCTAssertTrue(
                screen.contains(control.frame),
                """
                \(stop.identifier) must stay fully onscreen at AX5 \
                frame=\(NSCoder.string(for: control.frame)) \
                screen=\(NSCoder.string(for: screen))
                """
            )
            XCTAssertGreaterThanOrEqual(
                control.frame.height,
                Self.minimumTarget,
                """
                \(stop.identifier) must keep a 44pt target at AX5 \
                frame=\(NSCoder.string(for: control.frame))
                """
            )
            XCTAssertEqual(
                control.label,
                stop.label,
                "\(stop.identifier) must paint its exact Korean caption at AX5"
            )
            XCTAssertFalse(control.label.contains("\u{2026}"))
        }
    }

    /// The 44pt minimum target every AX5 control must still paint.
    private static let minimumTarget: CGFloat = 44

    private static func ax5ReachableControls(
        in app: XCUIApplication
    ) -> [MiniHomeAX5ReachStop] {
        [
            MiniHomeAX5ReachStop(
                identifier: "minihome.close",
                element: app.buttons["minihome.close"],
                label: "편집 닫기"
            ),
            MiniHomeAX5ReachStop(
                identifier: "minihome.save",
                element: app.buttons["minihome.save"],
                label: "저장"
            ),
            MiniHomeAX5ReachStop(
                identifier: "minihome.editor.title",
                element: app.staticTexts["minihome.editor.title"],
                label: "마이룸 편집"
            ),
            MiniHomeAX5ReachStop(
                identifier: "minihome.editor.category.plant",
                element: app.buttons["minihome.editor.category.plant"],
                label: "식물"
            ),
            MiniHomeAX5ReachStop(
                identifier: "minihome.editor.tray.0",
                element: app.buttons["minihome.editor.tray.0"],
                label: "몬스테라"
            ),
            MiniHomeAX5ReachStop(
                identifier: "minihome.editor.undo",
                element: app.buttons["minihome.editor.undo"],
                label: "되돌리기"
            ),
            MiniHomeAX5ReachStop(
                identifier: "minihome.editor.reset",
                element: app.buttons["minihome.editor.reset"],
                label: "초기화"
            )
        ]
    }

    /// The window and the four region containers frame the reachability
    /// contract; the controls themselves come from the asserted stop list, so
    /// the evidence can never drift from what the test checks.
    private func axReachEvidenceStops(
        in app: XCUIApplication
    ) -> [(String, XCUIElement)] {
        let regions = [
            "window": app.windows.element(boundBy: 0),
            "header": app.otherElements["minihome.editor.header"],
            "canvas": app.otherElements["minihome.editor.canvas"],
            "category-bar": app.otherElements["minihome.editor.category-bar"],
            "tray": app.otherElements["minihome.editor.tray"],
            "footer": app.otherElements["minihome.editor.footer"]
        ]
        let sortedRegions = regions.sorted { $0.key < $1.key }
            .map { ($0.key, $0.value) }
        return sortedRegions
            + Self.ax5ReachableControls(in: app)
            .map { ($0.identifier, $0.element) }
    }

    func assertEditorGeometry(
        in app: XCUIApplication,
        firstPlant: XCUIElement
    ) {
        let canvas = app.otherElements["minihome.editor.canvas"]
        let header = app.otherElements["minihome.editor.header"]
        let categoryBar = app.otherElements["minihome.editor.category-bar"]
        for region in [canvas, header, categoryBar] {
            XCTAssertTrue(region.exists, "missing editor geometry region")
        }
        XCTAssertEqual(canvas.frame.width, 358, accuracy: 1)
        XCTAssertEqual(canvas.frame.height, 330, accuracy: 1)
        XCTAssertEqual(canvas.frame.minY, 200, accuracy: 2)
        XCTAssertFalse(
            app.otherElements["minihome.editor.visual-frame"].exists,
            "a geometry probe must never become a VoiceOver stop"
        )
        XCTAssertEqual(categoryBar.frame.minY, 628, accuracy: 0.5)
        XCTAssertEqual(categoryBar.frame.height, 55, accuracy: 0.5)
        XCTAssertEqual(firstPlant.frame.minY, 699, accuracy: 0.5)
        let undo = app.buttons["minihome.editor.undo"]
        XCTAssertEqual(undo.frame.minY, 800, accuracy: 0.5)
        XCTAssertLessThanOrEqual(categoryBar.frame.maxY, firstPlant.frame.minY)
        XCTAssertLessThanOrEqual(firstPlant.frame.maxY, undo.frame.minY)
    }

    private func assertAXHeader(in app: XCUIApplication) {
        let close = app.buttons["minihome.close"]
        let save = app.buttons["minihome.save"]
        let title = app.staticTexts["minihome.editor.title"]
        XCTAssertTrue(close.isHittable)
        XCTAssertTrue(save.isHittable)
        XCTAssertEqual(title.label, "마이룸 편집")
        XCTAssertFalse(title.label.contains("\u{2026}"))
        XCTAssertGreaterThan(title.frame.width, 150)
        XCTAssertLessThanOrEqual(close.frame.maxX, title.frame.minX)
        XCTAssertLessThanOrEqual(title.frame.maxX, save.frame.minX)
        XCTAssertTrue(
            app.otherElements["minihome.editor.header"].frame
                .contains(title.frame)
        )
    }

    private func assertAXCategoryControls(in app: XCUIApplication) {
        let plant = app.buttons["minihome.editor.category.plant"]
        let wall = app.buttons["minihome.editor.category.wall"]
        let decoration = app.buttons["minihome.editor.category.decoration"]
        XCTAssertTrue(plant.isHittable)
        XCTAssertTrue(decoration.isHittable)
        decoration.tap()
        XCTAssertEqual(decoration.value as? String, "선택됨")
        plant.tap()
        XCTAssertTrue(app.buttons["minihome.editor.tray.0"].isHittable)
        XCTAssertTrue(app.buttons["minihome.editor.reset"].isHittable)
        XCTAssertGreaterThanOrEqual(
            app.buttons["minihome.editor.tray.0"].frame.width,
            120
        )
        XCTAssertFalse(plant.label.contains("\u{2026}"))
        XCTAssertGreaterThanOrEqual(wall.frame.minX, plant.frame.maxX)
        assertCategoryCaptionsStayOnOneLine(in: app)
    }
}
