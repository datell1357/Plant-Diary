import XCTest

@MainActor
extension AppLaunchUITests {
    func testIdentificationRequiresCandidateConfirmationBeforeRegistration() {
        let app = XCUIApplication()
        app.launchEnvironment["QA_SKIP_ONBOARDING"] = "1"
        app.launchEnvironment["QA_AUTHENTICATED"] = "1"
        app.launchEnvironment["QA_RESET_COLLECTION"] = "1"
        app.launchEnvironment["QA_PHOTO_FIXTURE"] = "valid"
        app.launch()

        XCTAssertTrue(app.buttons["tab.camera"].waitForExistence(timeout: 5))
        app.buttons["tab.camera"].tap()
        XCTAssertTrue(app.images["photo.review"].waitForExistence(timeout: 5))
        app.buttons["photo.acknowledge"].tap()
        XCTAssertTrue(app.alerts["사진 처리 안내"].waitForExistence(timeout: 5))
        app.alerts["사진 처리 안내"].buttons["동의하고 계속"].tap()
        XCTAssertTrue(
            app.otherElements["capture.identification-result"].waitForExistence(timeout: 10)
        )
        // §6.11: one primary registration action, re-targeted by the selected
        // alternate candidate.
        app.buttons["identification.candidate.1"].tap()
        app.buttons["capture.result.register"].tap()
        XCTAssertTrue(app.navigationBars["식물 등록"].waitForExistence(timeout: 5))
        XCTAssertEqual(
            app.textFields["registration.name"].value as? String,
            "몬스테라 아단소니"
        )
        XCTAssertTrue(app.buttons["registration.submit"].isEnabled)
        app.buttons["registration.submit"].tap()
        XCTAssertTrue(
            app.navigationBars["식물 등록"].waitForNonExistence(timeout: 5)
        )
        app.terminate()

        let collectionApp = XCUIApplication()
        collectionApp.launchEnvironment["QA_SKIP_ONBOARDING"] = "1"
        collectionApp.launchEnvironment["QA_AUTHENTICATED"] = "1"
        collectionApp.launchEnvironment["QA_INITIAL_TAB"] = "collection"
        collectionApp.launch()
        XCTAssertTrue(
            collectionApp.buttons["collection.row.0"].waitForExistence(timeout: 5)
        )
    }

    func testLoginSheetPresentsAndCancelsWithoutPrivateRows() {
        let app = XCUIApplication()
        app.launchEnvironment["QA_SKIP_ONBOARDING"] = "1"
        app.launchEnvironment["QA_AUTHENTICATED"] = "0"
        app.launch()
        XCTAssertTrue(app.buttons["tab.camera"].waitForExistence(timeout: 5))
        app.buttons["tab.camera"].tap()
        XCTAssertTrue(app.buttons["auth.apple"].waitForExistence(timeout: 5))
        XCTAssertTrue(app.buttons["auth.google"].exists)
        app.buttons["auth.cancel"].tap()
        XCTAssertFalse(app.buttons["auth.apple"].exists)
        XCTAssertFalse(app.staticTexts["private-account-row"].exists)
    }

    func testFirstRunOnboardingCompletesOnce() {
        let app = XCUIApplication()
        app.launchEnvironment["QA_RESET_ONBOARDING"] = "1"
        app.launch()
        XCTAssertTrue(app.otherElements["onboarding.screen"].waitForExistence(timeout: 5))
        app.buttons["onboarding.complete"].tap()
        XCTAssertTrue(app.otherElements["app.shell"].waitForExistence(timeout: 5))
        app.terminate()
        app.launchEnvironment.removeValue(forKey: "QA_RESET_ONBOARDING")
        app.launchEnvironment["QA_SKIP_ONBOARDING"] = "1"
        app.launch()
        XCTAssertFalse(app.otherElements["onboarding.screen"].exists)
        XCTAssertTrue(app.otherElements["app.shell"].waitForExistence(timeout: 5))
    }

    func testPhotoReviewReplaceAndAcknowledgementCancellation() {
        let app = XCUIApplication()
        app.launchEnvironment["QA_SKIP_ONBOARDING"] = "1"
        app.launchEnvironment["QA_AUTHENTICATED"] = "1"
        app.launchEnvironment["QA_PHOTO_FIXTURE"] = "valid"
        app.launch()
        app.buttons["tab.camera"].tap()

        XCTAssertTrue(app.images["photo.review"].waitForExistence(timeout: 5))
        app.buttons["photo.acknowledge"].tap()
        XCTAssertTrue(app.alerts["사진 처리 안내"].waitForExistence(timeout: 5))
        app.alerts["사진 처리 안내"].buttons["취소"].tap()
        XCTAssertTrue(app.otherElements["capture.photo-review"].exists)
        XCTAssertTrue(app.images["photo.review"].exists)
        XCTAssertTrue(app.buttons["photo.acknowledge"].exists)
        XCTAssertTrue(app.buttons["photo.more"].exists)
        XCTAssertFalse(app.buttons["photo.replace"].exists)
        XCTAssertFalse(app.buttons["photo.manual"].exists)
    }

    func testDeniedCameraAndCorruptPhotoPreserveFallbackActions() {
        let denied = XCUIApplication()
        denied.launchEnvironment["QA_SKIP_ONBOARDING"] = "1"
        denied.launchEnvironment["QA_AUTHENTICATED"] = "1"
        denied.launchEnvironment["QA_CAMERA_DENIED"] = "1"
        denied.launch()
        denied.buttons["tab.camera"].tap()
        XCTAssertTrue(denied.otherElements["capture.camera"].waitForExistence(timeout: 5))
        denied.buttons["capture.shutter"].tap()
        XCTAssertTrue(denied.staticTexts["capture.error"].waitForExistence(timeout: 5))
        XCTAssertTrue(denied.buttons["capture.settings"].exists)
        XCTAssertTrue(denied.buttons["capture.library"].exists)
        denied.terminate()

        let corrupt = XCUIApplication()
        corrupt.launchEnvironment["QA_SKIP_ONBOARDING"] = "1"
        corrupt.launchEnvironment["QA_AUTHENTICATED"] = "1"
        corrupt.launchEnvironment["QA_PHOTO_FIXTURE"] = "corrupt"
        corrupt.launch()
        corrupt.buttons["tab.camera"].tap()
        // A corrupt fixture never produces a draft, so the flow stays on the
        // camera step and surfaces recovery there.
        XCTAssertTrue(corrupt.staticTexts["capture.error"].waitForExistence(timeout: 5))
        XCTAssertTrue(corrupt.buttons["capture.library"].exists)
    }
}
