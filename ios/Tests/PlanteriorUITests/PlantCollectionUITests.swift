import XCTest

@MainActor
final class PlantCollectionUITests: XCTestCase {
    func testWateringDueCompletionUpdatesNextDate() {
        let app = XCUIApplication()
        app.launchEnvironment["QA_SKIP_ONBOARDING"] = "1"
        app.launchEnvironment["QA_AUTHENTICATED"] = "1"
        app.launchEnvironment["QA_COLLECTION_FIXTURE"] = "1"
        app.launchEnvironment["QA_RESET_COLLECTION"] = "1"
        app.launchEnvironment["QA_WATERING_TODAY"] = "2026-08-11"
        app.launch()

        app.buttons["tab.collection"].tap()
        XCTAssertTrue(app.buttons["collection.row.0"].waitForExistence(timeout: 5))
        app.buttons["collection.row.0"].tap()
        app.buttons["plant.detail.edit"].tap()

        let nextDate = app.staticTexts["watering.next-date"]
        XCTAssertTrue(nextDate.waitForExistence(timeout: 5))
        XCTAssertTrue(nextDate.label.contains("2026-08-11"))
        let completeButton = app.otherElements[
            "plant.detail.watering-card"
        ].buttons["watering.complete"]
        XCTAssertTrue(completeButton.isHittable)
        completeButton.tap()
        expectation(
            for: NSPredicate(format: "label CONTAINS %@", "기록했어요"),
            evaluatedWith: completeButton
        )
        waitForExpectations(timeout: 5)
        XCTAssertTrue(app.staticTexts["watering.last-date"].label.contains("2026-08-11"))
        XCTAssertTrue(nextDate.label.contains("2026-08-21"))
        let undoButton = app.buttons["watering.undo"]
        XCTAssertTrue(undoButton.waitForExistence(timeout: 5))
        XCTAssertTrue(undoButton.isHittable)
        undoButton.tap()
        expectation(
            for: NSPredicate(format: "value == %@", "기록 전"),
            evaluatedWith: completeButton
        )
        waitForExpectations(timeout: 5)
        XCTAssertEqual(
            app.staticTexts["watering.compact-date"].label,
            "2026. 08. 01 (10일 전)"
        )
        XCTAssertTrue(app.staticTexts["watering.last-date"].label.contains("2026-08-01"))
        XCTAssertTrue(nextDate.label.contains("2026-08-11"))
        attachScreenshot(named: "task-11-watering-complete")
    }

    func testAlreadyRecordedWateringDoesNotOfferUndo() {
        let accountID = "watering-already-recorded"
        let first = XCUIApplication()
        first.launchEnvironment["QA_SKIP_ONBOARDING"] = "1"
        first.launchEnvironment["QA_AUTHENTICATED"] = "1"
        first.launchEnvironment["QA_ACCOUNT_ID"] = accountID
        first.launchEnvironment["QA_COLLECTION_FIXTURE"] = "1"
        first.launchEnvironment["QA_RESET_COLLECTION"] = "1"
        first.launchEnvironment["QA_WATERING_TODAY"] = "2026-08-11"
        first.launch()

        first.buttons["tab.collection"].tap()
        XCTAssertTrue(first.buttons["collection.row.0"].waitForExistence(timeout: 5))
        first.buttons["collection.row.0"].tap()
        let firstCompletion = first.buttons["watering.complete"]
        XCTAssertTrue(firstCompletion.waitForExistence(timeout: 5))
        firstCompletion.tap()
        XCTAssertTrue(first.buttons["watering.undo"].waitForExistence(timeout: 5))
        first.terminate()

        let second = XCUIApplication()
        second.launchEnvironment["QA_SKIP_ONBOARDING"] = "1"
        second.launchEnvironment["QA_AUTHENTICATED"] = "1"
        second.launchEnvironment["QA_ACCOUNT_ID"] = accountID
        second.launchEnvironment["QA_WATERING_TODAY"] = "2026-08-11"
        second.launch()

        second.buttons["tab.collection"].tap()
        XCTAssertTrue(second.buttons["collection.row.0"].waitForExistence(timeout: 5))
        second.buttons["collection.row.0"].tap()
        let secondCompletion = second.buttons["watering.complete"]
        XCTAssertTrue(secondCompletion.waitForExistence(timeout: 5))
        XCTAssertEqual(
            second.staticTexts["watering.compact-date"].label,
            "2026. 08. 11 (0일 전)"
        )
        secondCompletion.tap()
        expectation(
            for: NSPredicate(format: "value == %@", "오늘 물 주기는 이미 기록했어요."),
            evaluatedWith: secondCompletion
        )
        waitForExpectations(timeout: 5)
        XCTAssertFalse(second.buttons["watering.undo"].exists)
        XCTAssertEqual(
            second.staticTexts["watering.compact-date"].label,
            "2026. 08. 11 (0일 전)"
        )
    }

    func testWateringMissingDateShowsSetupGuidance() {
        let app = XCUIApplication()
        app.launchEnvironment["QA_SKIP_ONBOARDING"] = "1"
        app.launchEnvironment["QA_AUTHENTICATED"] = "1"
        app.launchEnvironment["QA_COLLECTION_FIXTURE"] = "1"
        app.launchEnvironment["QA_RESET_COLLECTION"] = "1"
        app.launchEnvironment["QA_WATERING_TODAY"] = "2026-08-11"
        app.launch()

        app.buttons["tab.collection"].tap()
        XCTAssertTrue(app.buttons["collection.row.1"].waitForExistence(timeout: 5))
        app.buttons["collection.row.1"].tap()

        let completeButton = app.buttons["watering.complete"]
        XCTAssertTrue(completeButton.waitForExistence(timeout: 5))
        XCTAssertTrue(completeButton.isEnabled)
        XCTAssertTrue(completeButton.isHittable)
        XCTAssertEqual(app.staticTexts["watering.compact-date"].label, "기록 없음")
        completeButton.tap()
        expectation(
            for: NSPredicate(format: "label CONTAINS %@", "기록했어요"),
            evaluatedWith: completeButton
        )
        waitForExpectations(timeout: 5)
        XCTAssertEqual(
            app.staticTexts["watering.compact-date"].label,
            "2026. 08. 11 (0일 전)"
        )

        app.buttons["plant.detail.edit"].tap()
        XCTAssertFalse(app.staticTexts["watering.missing-date"].exists)
        XCTAssertTrue(
            app.staticTexts["watering.last-date"].label.contains("2026-08-11")
        )
        let nextDate = app.staticTexts["watering.next-date"]
        XCTAssertTrue(nextDate.waitForExistence(timeout: 5))
        XCTAssertTrue(nextDate.label.contains("2026-08-21"))
        XCTAssertTrue(app.otherElements["app.shell"].exists)
        XCTAssertTrue(app.buttons["tab.collection"].exists)
        attachScreenshot(named: "task-11-watering-first-baseline")
    }

    func testWateringDraftDateUpdatesScheduleBeforeSave() {
        let app = XCUIApplication()
        app.launchEnvironment["QA_SKIP_ONBOARDING"] = "1"
        app.launchEnvironment["QA_AUTHENTICATED"] = "1"
        app.launchEnvironment["QA_COLLECTION_FIXTURE"] = "1"
        app.launchEnvironment["QA_RESET_COLLECTION"] = "1"
        app.launchEnvironment["QA_WATERING_TODAY"] = "2026-08-11"
        app.launchEnvironment["QA_WATERING_DRAFT_DATE"] = "2026-08-02"
        app.launch()

        app.buttons["tab.collection"].tap()
        XCTAssertTrue(app.buttons["collection.row.0"].waitForExistence(timeout: 5))
        app.buttons["collection.row.0"].tap()
        app.buttons["plant.detail.edit"].tap()

        let nextDate = app.staticTexts["watering.next-date"]
        XCTAssertTrue(nextDate.waitForExistence(timeout: 5))
        XCTAssertTrue(nextDate.label.contains("2026-08-12"))
    }

    func testFilteredEmptyDoesNotClaimCollectionIsEmpty() {
        let app = XCUIApplication()
        app.launchEnvironment["QA_SKIP_ONBOARDING"] = "1"
        app.launchEnvironment["QA_AUTHENTICATED"] = "1"
        app.launchEnvironment["QA_COLLECTION_FIXTURE"] = "1"
        app.launchEnvironment["QA_RESET_COLLECTION"] = "1"
        app.launch()

        app.buttons["tab.collection"].tap()
        app.buttons["collection.search.action"].tap()
        let search = app.textFields["collection.search"]
        XCTAssertTrue(search.waitForExistence(timeout: 5))
        search.tap()
        XCTAssertTrue(search.waitForExistence(timeout: 2))
        search.typeText("없는 식물")
        XCTAssertTrue(app.staticTexts["검색 결과가 없어요"].waitForExistence(timeout: 5))
        XCTAssertFalse(app.staticTexts["등록한 식물이 없어요"].exists)
    }

    func testLoadingStateDoesNotLeakPrivateContent() {
        assertState("loading", label: "도감을 불러오는 중")
    }

    func testErrorStateDoesNotLeakPrivateContent() {
        assertState("error", label: "도감을 불러오지 못했어요")
    }

    func testPartialStateDoesNotLeakPrivateContent() {
        assertState("partial", label: "일부 식물 정보만 표시 중이에요.")
    }

    func testStaleStateDoesNotLeakPrivateContent() {
        assertState("stale", label: "저장된 정보를 표시하고 있어요.")
    }

    private func assertState(_ state: String, label: String) {
        let app = XCUIApplication()
        app.launchEnvironment["QA_SKIP_ONBOARDING"] = "1"
        app.launchEnvironment["QA_AUTHENTICATED"] = "1"
        app.launchEnvironment["QA_COLLECTION_FIXTURE"] = "1"
        app.launchEnvironment["QA_COLLECTION_PRIVATE_FIXTURE"] = "1"
        app.launchEnvironment["QA_COLLECTION_STATE"] = state
        app.launch()
        app.buttons["tab.collection"].tap()

        XCTAssertTrue(
            app.staticTexts[label].waitForExistence(timeout: 5)
                || app.progressIndicators[label].exists
        )
        XCTAssertFalse(app.staticTexts["비공개 식물"].exists)
    }

    func attachScreenshot(named name: String) {
        let attachment = XCTAttachment(screenshot: XCUIScreen.main.screenshot())
        attachment.name = name
        attachment.lifetime = .keepAlways
        add(attachment)
    }
}
