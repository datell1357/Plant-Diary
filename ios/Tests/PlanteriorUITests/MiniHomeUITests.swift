import XCTest

@MainActor
final class MiniHomeUITests: XCTestCase, MiniHomeUITestSupport {
    func testEditsSavesAndRestoresCommittedRoom() {
        let app = miniHomeApp()
        app.launchEnvironment["QA_MINIHOME_RESET_TOKEN"] =
            "todo14-main-\(UUID())"
        app.launch()
        openEditor(in: app)
        replaceRoomName(with: "창가 정원", in: app)
        dragPlantToEdgeAndAttachGeometry(in: app)
        saveAndAttachRoom(in: app)
        app.buttons["minihome.close"].tap()
        XCTAssertTrue(app.staticTexts["창가 정원"].waitForExistence(timeout: 5))
        app.navigationBars.buttons.element(boundBy: 0).tap()
        waitForCommittedRoom(named: "창가 정원", in: app)

        app.terminate()
        app.launchEnvironment.removeValue(forKey: "QA_MINIHOME_ROUTE")
        app.launch()
        waitForCommittedRoom(named: "창가 정원", in: app)
    }

    func testUnsavedDraftNeverAppearsOnHome() {
        let app = miniHomeApp()
        app.launchEnvironment["QA_MINIHOME_RESET_TOKEN"] =
            "todo14-committed-only-\(UUID())"
        app.launch()
        openEditor(in: app)
        replaceRoomName(with: "미저장 초안", in: app)

        app.terminate()
        app.launchEnvironment.removeValue(forKey: "QA_MINIHOME_ROUTE")
        app.launch()
        waitForCommittedRoom(named: "초록 방", in: app)
        attachJSON(
            [
                "unsavedDraft": "미저장 초안",
                "homeCommittedRoom": "초록 방",
                "committedOnly": true
            ],
            named: "task-14-home-committed-only"
        )
    }
}
