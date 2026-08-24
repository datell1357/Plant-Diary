import XCTest

@MainActor
final class MiniHomeConflictUITests: XCTestCase, MiniHomeUITestSupport {
    func testFailedSavePreservesDraftAndUnsavedChoices() {
        let app = miniHomeApp()
        app.launchEnvironment["QA_MINIHOME_RESET_TOKEN"] =
            "todo14-failure-\(UUID())"
        app.launchEnvironment["QA_MINIHOME_SAVE_FAILURE"] = "1"
        app.launch()

        let originalCommittedName = prepareFailedSaveDraft(in: app)
        let committedName = app.staticTexts["minihome.committed.name"]
        let roomSettings = app.descendants(matching: .any)
            .matching(identifier: "minihome.editor.room-settings")
            .firstMatch
        let roomName = app.textFields["minihome.room-name"]
        let done = app.navigationBars["방 설정"].buttons["완료"]
        XCTAssertTrue(done.waitForExistence(timeout: 5))
        waitForMiniHomeElementToDisappear(roomName) {
            done.tap()
        }

        let saveDraft = app.buttons
            .matching(identifier: "minihome.unsaved.save")
            .firstMatch
        let discardDraft = app.buttons
            .matching(identifier: "minihome.unsaved.discard")
            .firstMatch
        waitForMiniHomeElement(saveDraft) {
            app.buttons["minihome.close"].tap()
        }
        XCTAssertTrue(discardDraft.exists)

        // iOS omits the cancellation-role row from this compact popover.
        // Tapping the identified editor title exercises its cancel dismissal
        // without depending on the generic popover-dismiss element.
        let editorTitle = app.staticTexts["minihome.editor.title"]
        waitForMiniHomeElementToDisappear(saveDraft) {
            editorTitle.tap()
        }
        XCTAssertTrue(app.scrollViews["minihome.editor"].exists)
        waitForMiniHomeElement(roomSettings) {
            editorTitle.tap()
        }
        XCTAssertEqual(roomName.value as? String, "실패한 초안")
        waitForMiniHomeState("저장 실패", in: app)
        waitForMiniHomeElementToDisappear(roomName) {
            done.tap()
        }

        waitForMiniHomeElement(discardDraft) {
            app.buttons["minihome.close"].tap()
        }
        let editor = app.scrollViews["minihome.editor"]
        waitForMiniHomeElementToDisappear(editor) {
            discardDraft.tap()
        }
        XCTAssertTrue(committedName.waitForExistence(timeout: 5))
        XCTAssertEqual(committedName.label, originalCommittedName)
        XCTAssertFalse(app.staticTexts["실패한 초안"].exists)
    }

    func testConflictPreservesDraftThenExplicitlyReapplies() {
        let app = miniHomeApp()
        app.launchEnvironment["QA_MINIHOME_RESET_TOKEN"] =
            "todo14-conflict-\(UUID())"
        app.launchEnvironment["QA_MINIHOME_CONFLICT_ONCE"] = "1"
        app.launch()
        openEditor(in: app)
        replaceRoomName(with: "내 충돌 초안", in: app)

        let reapply = app.buttons
            .matching(identifier: "minihome.conflict.save")
            .firstMatch
        let useServer = app.buttons
            .matching(identifier: "minihome.conflict.discard")
            .firstMatch
        waitForMiniHomeElement(reapply) {
            app.buttons["minihome.save"].tap()
        }
        XCTAssertTrue(useServer.exists)
        assertConflictDraftAfterCancel(in: app, reapply: reapply)

        let conflict = app.buttons["minihome.conflict"]
        XCTAssertTrue(conflict.waitForExistence(timeout: 5))
        waitForMiniHomeElement(reapply) {
            conflict.tap()
        }
        let committedName = app.staticTexts["minihome.committed.name"]
        waitForMiniHomeElement(committedName) {
            reapply.tap()
        }
        XCTAssertEqual(committedName.label, "내 충돌 초안")
        let editor = app.scrollViews["minihome.editor"]
        XCTAssertTrue(editor.exists)
        waitForMiniHomeElementToDisappear(editor) {
            app.buttons["minihome.close"].tap()
        }
        XCTAssertEqual(committedName.label, "내 충돌 초안")
        attachJSON(
            [
                "observedConflictState": "충돌 · 서버 2판",
                "draftAfterCancel": "내 충돌 초안",
                "observedReapplyState": "저장 완료",
                "committedRoomName": committedName.label
            ],
            named: "task-14-mini-home-conflict"
        )
    }

    private func prepareFailedSaveDraft(
        in app: XCUIApplication
    ) -> String {
        let committedName = app.staticTexts["minihome.committed.name"]
        XCTAssertTrue(committedName.waitForExistence(timeout: 10))
        let originalCommittedName = committedName.label
        openEditor(in: app)
        replaceRoomName(with: "실패한 초안", in: app)

        let roomSettings = app.descendants(matching: .any)
            .matching(identifier: "minihome.editor.room-settings")
            .firstMatch
        waitForMiniHomeElement(roomSettings) {
            app.buttons["minihome.save"].tap()
        }
        waitForMiniHomeState("저장 실패", in: app)
        XCTAssertEqual(
            app.staticTexts["minihome.save-error"].label,
            "저장하지 못했어요. 초안은 그대로 남아 있어요."
        )
        XCTAssertEqual(
            app.textFields["minihome.room-name"].value as? String,
            "실패한 초안"
        )
        return originalCommittedName
    }

    private func assertConflictDraftAfterCancel(
        in app: XCUIApplication,
        reapply: XCUIElement
    ) {
        // The compact confirmation popover omits its cancellation-role row;
        // dismiss through an identified editor control, then inspect the draft.
        let editorTitle = app.staticTexts["minihome.editor.title"]
        waitForMiniHomeElementToDisappear(reapply) {
            editorTitle.tap()
        }

        let roomSettings = app.descendants(matching: .any)
            .matching(identifier: "minihome.editor.room-settings")
            .firstMatch
        waitForMiniHomeElement(roomSettings) {
            editorTitle.tap()
        }
        let roomName = app.textFields["minihome.room-name"]
        XCTAssertTrue(roomName.waitForExistence(timeout: 5))
        XCTAssertEqual(roomName.value as? String, "내 충돌 초안")
        waitForMiniHomeState("충돌 · 서버 2판", in: app)
    }
}
