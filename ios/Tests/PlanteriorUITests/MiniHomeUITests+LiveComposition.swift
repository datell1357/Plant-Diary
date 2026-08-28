import XCTest

@MainActor
extension MiniHomeFigmaUITests {
    func testOverlappedNewPlacementsDragTheVisibleTopIdentity() {
        let app = figmaEditorApp(token: "overlap-visible-top")
        app.launchEnvironment["QA_MINIHOME_FIGMA_FIXTURE"] = "1"
        app.launch()
        openFigmaEditor(in: app)

        let firstTrayEntry = app.buttons["minihome.editor.tray.0"]
        XCTAssertTrue(firstTrayEntry.waitForExistence(timeout: 5))
        firstTrayEntry.tap()
        firstTrayEntry.tap()

        let canvas = app.otherElements["minihome.editor.canvas"]
        let hidden = canvas.images["minihome.placement.placement-1"]
        let visible = canvas.images["minihome.placement.placement-2"]
        XCTAssertTrue(hidden.waitForExistence(timeout: 5))
        XCTAssertTrue(visible.waitForExistence(timeout: 5))
        let hiddenValue = hidden.value as? String
        let visibleValue = visible.value as? String
        let visibleMoved = XCTNSPredicateExpectation(
            predicate: NSPredicate(format: "value != %@", visibleValue ?? ""),
            object: visible
        )

        visible.coordinate(
            withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5)
        ).press(
            forDuration: 0.1,
            thenDragTo: canvas.coordinate(
                withNormalizedOffset: CGVector(dx: 0.2, dy: 0.8)
            )
        )

        XCTAssertEqual(
            XCTWaiter.wait(for: [visibleMoved], timeout: 5),
            .completed,
            "dragging the overlap must move the visible frontmost placement"
        )
        XCTAssertEqual(hidden.value as? String, hiddenValue)
        XCTAssertNotEqual(visible.value as? String, visibleValue)
    }

    func testCanonicalReferencePlantsAreLiveMovableAndPersisted() {
        let app = figmaEditorApp(token: "figma-live-placements")
        app.launchEnvironment["QA_MINIHOME_FIGMA_FIXTURE"] = "1"
        app.launch()
        openFigmaEditor(in: app)

        let canvas = app.otherElements["minihome.editor.canvas"]
        assertCanonicalPlacements(in: canvas)
        attachScreenshot(named: "final-blocker-room-402")

        let placement = canvas.images["minihome.placement.figma-room-placement-1"]
        // Pinned from the authenticated 358x330 room reference. This physical
        // point intentionally does not derive from production frames or AX order.
        let dragStart = canvas.coordinate(
            withNormalizedOffset: CGVector(
                dx: 130.0 / 358.0,
                dy: 245.0 / 330.0
            )
        )
        let initialValue = placement.value as? String
        let valueChanged = XCTNSPredicateExpectation(
            predicate: NSPredicate(format: "value != %@", initialValue ?? ""),
            object: placement
        )
        dragStart.press(
            forDuration: 0.1,
            thenDragTo: canvas.coordinate(
                withNormalizedOffset: CGVector(dx: 0.25, dy: 0.75)
            )
        )
        XCTAssertEqual(
            XCTWaiter.wait(for: [valueChanged], timeout: 5),
            .completed,
            "a canonical placement must update through the normal drag path"
        )
        let movedValue = placement.value as? String

        app.buttons["minihome.save"].tap()
        app.staticTexts["minihome.editor.title"].tap()
        let saved = app.staticTexts["minihome.state"]
        let committed = XCTNSPredicateExpectation(
            predicate: NSPredicate(format: "label == %@", "저장 완료"),
            object: saved
        )
        XCTAssertEqual(XCTWaiter.wait(for: [committed], timeout: 5), .completed)
        app.navigationBars.buttons["완료"].tap()
        app.buttons["minihome.close"].tap()

        app.buttons["minihome.edit"].tap()
        let restored = app.otherElements["minihome.editor.canvas"].images[
            "minihome.placement.figma-room-placement-1"
        ]
        XCTAssertTrue(restored.waitForExistence(timeout: 5))
        XCTAssertEqual(restored.value as? String, movedValue)

        assertIdentityAfterHomeRemount(in: app)
    }

    private func assertIdentityAfterHomeRemount(in app: XCUIApplication) {
        app.terminate()
        app.launchEnvironment.removeValue(forKey: "QA_MINIHOME_ROUTE")
        app.launch()
        let identifier = "minihome.placement.figma-room-placement-1"
        let homePlacement = app.images[identifier]
        XCTAssertTrue(homePlacement.waitForExistence(timeout: 10))
        app.buttons["home.room.decorate"].tap()
        XCTAssertTrue(
            app.otherElements["minihome.canvas"]
                .waitForExistence(timeout: 10)
        )
        let committedPlacement = app.images[identifier]
        XCTAssertTrue(committedPlacement.exists)
        XCTAssertEqual(committedPlacement.label, homePlacement.label)
    }

    private func assertCanonicalPlacements(in canvas: XCUIElement) {
        let ids = [
            "figma-room-placement-1",
            "figma-room-placement-2",
            "figma-room-placement-3"
        ]
        let placements = ids.map {
            canvas.images["minihome.placement.\($0)"]
        }
        for (id, placement) in zip(ids, placements) {
            XCTAssertTrue(
                placement.waitForExistence(timeout: 5),
                "missing live canonical placement: \(id)"
            )
        }
        let labels = placements.map(\.label)
        let values = placements.compactMap { $0.value as? String }
        XCTAssertEqual(Set(labels).count, ids.count)
        XCTAssertEqual(Set(values).count, ids.count)
        XCTAssertTrue(labels.allSatisfy { !$0.contains("배치된 식물") })
        XCTAssertTrue(values.allSatisfy { $0.contains("가로") && $0.contains("세로") })
    }
}
