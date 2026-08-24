import XCTest

@MainActor
final class PlantCollectionIntegrityUITests: XCTestCase {
    func testCollectionSummaryOpensModelDerivedCareCounts() {
        let app = collectionApp()
        app.launch()

        let summary = app.buttons["collection.open-detail"]
        XCTAssertTrue(summary.waitForExistence(timeout: 10))
        summary.tap()

        XCTAssertTrue(
            app.scrollViews["collection.summary.screen"].waitForExistence(timeout: 5)
        )
        XCTAssertEqual(app.staticTexts["collection.summary.total"].label, "등록 식물 2개")
        XCTAssertEqual(app.staticTexts["collection.summary.due"].label, "오늘 돌봄 1개")
        XCTAssertEqual(app.staticTexts["collection.summary.unconfigured"].label, "설정 필요 1개")
        XCTAssertFalse(app.otherElements["collection.detail"].exists)
    }

    func testSuccessfulPlantEditShowsObservableSaveFeedback() {
        let app = collectionApp()
        app.launch()

        let row = app.buttons["collection.row.0"]
        XCTAssertTrue(row.waitForExistence(timeout: 10))
        row.tap()
        let edit = app.buttons["plant.detail.edit"]
        XCTAssertTrue(edit.waitForExistence(timeout: 5))
        edit.tap()
        let location = app.textFields["plant.detail.location"]
        XCTAssertTrue(location.waitForExistence(timeout: 5))
        location.tap()
        location.typeText("거실\n")
        app.buttons["plant.detail.save"].tap()

        let feedback = app.staticTexts["plant.detail.save-success"]
        XCTAssertTrue(feedback.waitForExistence(timeout: 5))
        XCTAssertEqual(feedback.label, "변경사항을 저장했어요.")
        XCTAssertFalse(app.staticTexts["plant.detail.save-error"].exists)
    }

    private func collectionApp() -> XCUIApplication {
        let app = XCUIApplication()
        app.launchEnvironment["QA_SKIP_ONBOARDING"] = "1"
        app.launchEnvironment["QA_AUTHENTICATED"] = "1"
        app.launchEnvironment["QA_INITIAL_TAB"] = "collection"
        app.launchEnvironment["QA_COLLECTION_FIXTURE"] = "1"
        app.launchEnvironment["QA_RESET_COLLECTION"] = "1"
        app.launchEnvironment["QA_WATERING_TODAY"] = "2026-08-11"
        return app
    }
}
