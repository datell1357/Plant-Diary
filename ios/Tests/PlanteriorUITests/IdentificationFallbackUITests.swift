import XCTest

@MainActor
final class IdentificationFallbackUITests: XCTestCase {
    func testIdentificationFallbackReturnsToPhotoSelection() {
        let app = XCUIApplication()
        app.launchEnvironment["QA_SKIP_ONBOARDING"] = "1"
        // The shell gates the capture action behind authentication (§8.1), so
        // these flows must launch signed in to reach it.
        app.launchEnvironment["QA_AUTHENTICATED"] = "1"
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
        // The shell gates the capture action behind authentication (§8.1), so
        // these flows must launch signed in to reach it.
        app.launchEnvironment["QA_AUTHENTICATED"] = "1"
        app.launchEnvironment["QA_PHOTO_FIXTURE"] = "valid"
        app.launchEnvironment["QA_IDENTIFICATION_STATE"] = "failure"
        app.launchEnvironment["QA_IDENTIFICATION_RETRY_DELAY_MS"] = "5000"
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
            app.staticTexts["identification.failed"].waitForNonExistence(timeout: 1)
        )
        XCTAssertTrue(
            app.staticTexts["identification.pending"].waitForExistence(timeout: 1)
        )
        XCTAssertFalse(app.buttons["identification.retry"].exists)
        XCTAssertFalse(app.otherElements["capture.identification-result"].exists)
        // §6.11: retry lands on the result screen, where the top match is the
        // summary card and the remaining matches are the "다른 후보" rows.
        XCTAssertTrue(
            app.otherElements["capture.identification-result"].waitForExistence(timeout: 10)
        )
        XCTAssertTrue(app.staticTexts["capture.result.species"].exists)
        XCTAssertTrue(app.buttons["identification.candidate.1"].exists)
    }

    func testIdentificationPendingIsObservedBeforeCandidates() {
        let app = XCUIApplication()
        app.launchEnvironment["QA_SKIP_ONBOARDING"] = "1"
        // The shell gates the capture action behind authentication (§8.1), so
        // these flows must launch signed in to reach it.
        app.launchEnvironment["QA_AUTHENTICATED"] = "1"
        app.launchEnvironment["QA_PHOTO_FIXTURE"] = "valid"
        app.launchEnvironment["QA_IDENTIFICATION_STATE"] = "pending"
        app.launch()
        app.buttons["tab.camera"].tap()
        XCTAssertTrue(app.images["photo.review"].waitForExistence(timeout: 5))
        app.buttons["photo.acknowledge"].tap()
        XCTAssertTrue(app.alerts["사진 처리 안내"].waitForExistence(timeout: 5))
        app.alerts["사진 처리 안내"].buttons["동의하고 계속"].tap()
        XCTAssertTrue(
            app.staticTexts["identification.pending"].waitForExistence(timeout: 5)
        )
        XCTAssertEqual(
            app.staticTexts["identification.pending"].label,
            "AI가 식물을 분석하고 있어요..."
        )
    }
}
