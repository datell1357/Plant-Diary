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
        app.swipeDown()
        XCTAssertTrue(app.staticTexts["watering.last-date"].label.contains("2026-08-11"))
        XCTAssertTrue(nextDate.label.contains("2026-08-21"))
        attachScreenshot(named: "task-11-watering-complete")
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
        app.swipeUp()
        app.textFields["plant.detail.note"].tap()
        app.textFields["plant.detail.note"].typeText("새잎이 자랐어요\n")
        app.buttons["plant.detail.add-note"].tap()
        XCTAssertTrue(app.staticTexts["plant.detail.timeline"].waitForExistence(timeout: 5))
        assertDeleteIsReachableAndRequiresConfirmation(in: app)
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

    private func assertDeleteIsReachableAndRequiresConfirmation(
        in app: XCUIApplication
    ) {
        let detailScroll = app.scrollViews["plant.detail.screen"]
        let delete = app.buttons["plant.detail.delete"]
        scrollToHittable(delete, in: detailScroll)
        let tabBar = app.buttons["tab.collection"]
        XCTAssertTrue(delete.isHittable)
        XCTAssertFalse(
            delete.frame.intersects(tabBar.frame),
            "the delete action must clear the persistent tab bar"
        )
        attachScreenshot(named: "collection-detail-delete-hittable")
        delete.tap()
        XCTAssertTrue(app.sheets.firstMatch.waitForExistence(timeout: 5))
        XCTAssertTrue(
            app.buttons["plant.detail.delete-confirm"].waitForExistence(timeout: 5)
        )
        app.buttons.matching(
            identifier: "plant.detail.delete-cancel"
        ).firstMatch.tap()
        XCTAssertFalse(app.sheets.firstMatch.exists)
        XCTAssertTrue(app.buttons["plant.detail.delete"].exists)
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

    private func attachScreenshot(named name: String) {
        let attachment = XCTAttachment(screenshot: XCUIScreen.main.screenshot())
        attachment.name = name
        attachment.lifetime = .keepAlways
        add(attachment)
    }
}
