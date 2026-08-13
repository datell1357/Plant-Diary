import XCTest

@MainActor
final class IdentificationFallbackUITests: XCTestCase {
    func testIdentificationFallbackReturnsToPhotoSelection() {
        let app = XCUIApplication()
        app.launchEnvironment["QA_SKIP_ONBOARDING"] = "1"
        app.launchEnvironment["QA_PHOTO_FIXTURE"] = "valid"
        app.launchEnvironment["QA_IDENTIFICATION_STATE"] = "empty"
        app.launch()

        app.buttons["tab.camera"].tap()
        XCTAssertTrue(app.images["photo.review"].waitForExistence(timeout: 5))
        app.buttons["photo.acknowledge"].tap()
        app.alerts["사진 처리 안내"].buttons["동의하고 계속"].tap()
        XCTAssertTrue(
            app.staticTexts["identification.empty"].waitForExistence(timeout: 5)
        )
        app.buttons["identification.replace"].tap()
        XCTAssertTrue(app.buttons["tab.camera"].waitForExistence(timeout: 5))
    }

    func testIdentificationFailureRetriesToCandidates() {
        let app = XCUIApplication()
        app.launchEnvironment["QA_SKIP_ONBOARDING"] = "1"
        app.launchEnvironment["QA_PHOTO_FIXTURE"] = "valid"
        app.launchEnvironment["QA_IDENTIFICATION_STATE"] = "failure"
        app.launch()

        app.buttons["tab.camera"].tap()
        XCTAssertTrue(app.images["photo.review"].waitForExistence(timeout: 5))
        app.buttons["photo.acknowledge"].tap()
        app.alerts["사진 처리 안내"].buttons["동의하고 계속"].tap()
        XCTAssertTrue(
            app.staticTexts["identification.failed"].waitForExistence(timeout: 5)
        )
        app.buttons["identification.retry"].tap()
        XCTAssertTrue(
            app.buttons["identification.candidate.0"].waitForExistence(timeout: 5)
        )
    }

    func testIdentificationPendingIsObservedBeforeCandidates() {
        let app = XCUIApplication()
        app.launchEnvironment["QA_SKIP_ONBOARDING"] = "1"
        app.launchEnvironment["QA_PHOTO_FIXTURE"] = "valid"
        app.launchEnvironment["QA_IDENTIFICATION_STATE"] = "pending"
        app.launch()
        app.buttons["tab.camera"].tap()
        XCTAssertTrue(app.images["photo.review"].waitForExistence(timeout: 5))
        app.buttons["photo.acknowledge"].tap()
        XCTAssertTrue(app.alerts["사진 처리 안내"].waitForExistence(timeout: 5))
        app.alerts["사진 처리 안내"].buttons["동의하고 계속"].tap()
        XCTAssertTrue(
            app.staticTexts["식물을 찾고 있어요"].waitForExistence(timeout: 5)
        )
    }
}
