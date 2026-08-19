import XCTest

@MainActor
final class MiniHomeConflictUITests: XCTestCase, MiniHomeUITestSupport {
    func testFailedSavePreservesDraftAndUnsavedChoices() {
        let app = miniHomeApp()
        app.launchEnvironment["QA_MINIHOME_RESET_TOKEN"] =
            "todo14-failure-\(UUID())"
        app.launchEnvironment["QA_MINIHOME_SAVE_FAILURE"] = "1"
        app.launch()
        openEditor(in: app)
        replaceRoomName(with: "실패한 초안", in: app)

        app.buttons["minihome.save"].tap()
        waitForMiniHomeState("저장 실패", in: app)
        XCTAssertEqual(
            app.textFields["minihome.room-name"].value as? String,
            "실패한 초안"
        )
        app.buttons["minihome.close"].tap()
        let saveDraft = app.buttons
            .matching(identifier: "minihome.unsaved.save")
            .firstMatch
        let discardDraft = app.buttons
            .matching(identifier: "minihome.unsaved.discard")
            .firstMatch
        XCTAssertTrue(saveDraft.waitForExistence(timeout: 5))
        XCTAssertTrue(discardDraft.exists)
        dismissConfirmationPopover(in: app)
        XCTAssertEqual(
            app.textFields["minihome.room-name"].value as? String,
            "실패한 초안"
        )
        app.buttons["minihome.close"].tap()
        let discard = app.buttons
            .matching(identifier: "minihome.unsaved.discard")
            .firstMatch
        XCTAssertTrue(discard.waitForExistence(timeout: 5))
        discard.tap()
        XCTAssertTrue(
            app.scrollViews["minihome.editor"]
                .waitForNonExistence(timeout: 5)
        )
        XCTAssertTrue(app.staticTexts["초록 방"].waitForExistence(timeout: 5))
    }

    func testConflictPreservesDraftThenExplicitlyReapplies() {
        let app = miniHomeApp()
        app.launchEnvironment["QA_MINIHOME_RESET_TOKEN"] =
            "todo14-conflict-\(UUID())"
        app.launchEnvironment["QA_MINIHOME_CONFLICT_ONCE"] = "1"
        app.launch()
        openEditor(in: app)
        replaceRoomName(with: "내 충돌 초안", in: app)

        app.buttons["minihome.save"].tap()
        waitForMiniHomeState("충돌 · 서버 2판", in: app)
        dismissConfirmationPopover(in: app)
        XCTAssertEqual(
            app.textFields["minihome.room-name"].value as? String,
            "내 충돌 초안"
        )
        app.buttons["minihome.conflict"].tap()
        let reapply = app.buttons
            .matching(identifier: "minihome.conflict.save")
            .firstMatch
        XCTAssertTrue(reapply.waitForExistence(timeout: 5))
        reapply.tap()
        waitForMiniHomeState("저장 완료", in: app)
        app.buttons["minihome.close"].tap()
        XCTAssertTrue(
            app.scrollViews["minihome.editor"]
                .waitForNonExistence(timeout: 5)
        )
        XCTAssertTrue(
            app.staticTexts["내 충돌 초안"].waitForExistence(timeout: 5)
        )
        let conflictState = "충돌 · 서버 2판"
        let committedRoom = "내 충돌 초안"
        attachJSON(
            [
                "observedConflictState": conflictState,
                "draftAfterCancel": committedRoom,
                "observedReapplyState": "저장 완료",
                "committedRoomName": committedRoom
            ],
            named: "task-14-mini-home-conflict"
        )
    }
}
