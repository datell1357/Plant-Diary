import XCTest

@MainActor
extension MiniHomeConflictUITests {
    func triggerReapplyAndWaitForCommit(
        in app: XCUIApplication,
        reapply: XCUIElement
    ) {
        let committedName = app.staticTexts["minihome.committed.name"]
        let committed = XCTNSPredicateExpectation(
            predicate: NSPredicate(
                format: "exists == true AND label == %@",
                "내 충돌 초안"
            ),
            object: committedName
        )
        reapply.tap()
        XCTAssertEqual(
            XCTWaiter.wait(for: [committed], timeout: 10),
            .completed,
            "committed MiniHome after reapply: \(committedName.label)"
        )
        XCTAssertFalse(
            app.descendants(matching: .any)["minihome.editor"].exists
        )
    }

    func assertReappliedConflictSurvivesRemount(
        in app: XCUIApplication,
        expectedPlacementIDs: [String]
    ) {
        let committedName = app.staticTexts["minihome.committed.name"]
        XCTAssertTrue(committedName.exists)
        XCTAssertEqual(committedName.label, "내 충돌 초안")
        let committedCanvas = app.otherElements["minihome.canvas"]
        XCTAssertTrue(committedCanvas.waitForExistence(timeout: 5))
        let committedPlacements = committedCanvas.images.allElementsBoundByIndex
            .filter { $0.identifier.hasPrefix("minihome.placement.") }
        XCTAssertEqual(committedPlacements.map(\.identifier), expectedPlacementIDs)
        let committedLabels = committedPlacements.map(\.label)

        app.terminate()
        app.launch()
        XCTAssertTrue(committedName.waitForExistence(timeout: 10))
        XCTAssertEqual(committedName.label, "내 충돌 초안")
        let remountedPlacements = app.otherElements["minihome.canvas"].images
            .allElementsBoundByIndex
            .filter { $0.identifier.hasPrefix("minihome.placement.") }
        XCTAssertEqual(remountedPlacements.map(\.identifier), expectedPlacementIDs)
        XCTAssertEqual(remountedPlacements.map(\.label), committedLabels)
        for identifier in expectedPlacementIDs {
            XCTAssertEqual(app.images.matching(identifier: identifier).count, 1)
        }
        XCTAssertFalse(app.buttons["minihome.conflict"].exists)
        XCTAssertEqual(
            app.buttons.matching(identifier: "minihome.conflict.save").count,
            0
        )

        let revision = app.staticTexts["minihome.share.revision"]
        waitForMiniHomeElement(revision) {
            app.buttons["minihome.share"].tap()
        }
        XCTAssertEqual(revision.label, "저장된 3판")
        attachJSON(
            [
                "observedConflictState": "충돌 · 저장본 2판",
                "draftAfterCancel": "내 충돌 초안",
                "observedReapplyState": "저장 완료",
                "committedRoomName": committedName.label,
                "committedRevision": 3,
                "placementOrder": expectedPlacementIDs,
                "placementLabels": committedLabels,
                "duplicatePlacementCount": 0,
                "staleConflictAlert": false
            ],
            named: "task-14-mini-home-conflict"
        )
    }
}
