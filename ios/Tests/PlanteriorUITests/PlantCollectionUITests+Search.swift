import XCTest

@MainActor
extension PlantCollectionUITests {
    func testSearchDetailTimelineAndDeleteConfirmation() {
        let app = XCUIApplication()
        app.launchEnvironment["QA_SKIP_ONBOARDING"] = "1"
        app.launchEnvironment["QA_AUTHENTICATED"] = "1"
        app.launchEnvironment["QA_COLLECTION_FIXTURE"] = "1"
        app.launchEnvironment["QA_RESET_COLLECTION"] = "1"
        app.launch()

        app.buttons["tab.collection"].tap()
        app.buttons["collection.search.action"].tap()
        XCTAssertTrue(app.textFields["collection.search"].waitForExistence(timeout: 5))
        app.textFields["collection.search"].tap()
        app.textFields["collection.search"].typeText("몬\n")
        XCTAssertTrue(
            app.keyboards.firstMatch.waitForNonExistence(timeout: 5)
        )
        XCTAssertTrue(app.buttons["collection.row.0"].waitForExistence(timeout: 5))
        let screenshot = XCUIScreen.main.screenshot()
        let attachment = XCTAttachment(screenshot: screenshot)
        attachment.name = "task-10-ios-app-implementation"
        attachment.lifetime = .keepAlways
        add(attachment)
        app.buttons["collection.row.0"].tap()
        app.buttons["plant.detail.edit"].tap()

        XCTAssertTrue(app.textFields["plant.detail.nickname"].waitForExistence(timeout: 5))
        app.textFields["plant.detail.location"].tap()
        app.textFields["plant.detail.location"].typeText("거실\n")
        app.textFields["plant.detail.private-memo"].tap()
        app.textFields["plant.detail.private-memo"].typeText("창가에서 관리\n")
        app.buttons["plant.detail.save"].tap()
        addTimelineNote(in: app)
        assertDeleteIsReachableAndRequiresConfirmation(in: app)
    }

    func addTimelineNote(in app: XCUIApplication) {
        app.swipeUp()
        let note = app.textFields["plant.detail.note"]
        note.tap()
        let noteEntered = XCTNSPredicateExpectation(
            predicate: NSPredicate(format: "value == %@", "새잎이 자랐어요"),
            object: note
        )
        let keyboardDismissed = XCTNSPredicateExpectation(
            predicate: NSPredicate(format: "exists == false"),
            object: app.keyboards.firstMatch
        )
        note.typeText("새잎이 자랐어요\n")
        XCTAssertEqual(
            XCTWaiter.wait(
                for: [noteEntered, keyboardDismissed],
                timeout: 5,
                enforceOrder: false
            ),
            .completed
        )
        let addNote = app.buttons["plant.detail.add-note"]
        XCTAssertTrue(addNote.isEnabled)
        XCTAssertTrue(addNote.isHittable)
        let timeline = app.staticTexts["plant.detail.timeline"]
        let timelineAppeared = XCTNSPredicateExpectation(
            predicate: NSPredicate(format: "exists == true"),
            object: timeline
        )
        addNote.tap()
        XCTAssertEqual(
            XCTWaiter.wait(for: [timelineAppeared], timeout: 5),
            .completed
        )
    }
}
