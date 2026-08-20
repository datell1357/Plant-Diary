import XCTest

@MainActor
final class AppLaunchUITests: XCTestCase {
    func testCaptureRenderedShell() {
        let app = XCUIApplication()
        app.launchEnvironment["QA_SKIP_ONBOARDING"] = "1"
        app.launch()

        XCTAssertTrue(app.otherElements["app.shell"].waitForExistence(timeout: 5))
        let screenshot = XCTAttachment(screenshot: XCUIScreen.main.screenshot())
        screenshot.name = "task-4-rendered-shell"
        screenshot.lifetime = .keepAlways
        add(screenshot)
    }

    func testAppShellPreservesTabStacksAndPresentsCameraAction() {
        let app = XCUIApplication()
        app.launchEnvironment["QA_SKIP_ONBOARDING"] = "1"
        app.launchEnvironment["QA_AUTHENTICATED"] = "1"
        app.launch()

        XCTAssertTrue(app.otherElements["app.shell"].waitForExistence(timeout: 5))

        XCTAssertTrue(app.scrollViews["home.screen"].waitForExistence(timeout: 5))
        XCTAssertFalse(app.navigationBars["홈"].exists)

        app.buttons["tab.collection"].tap()
        XCTAssertTrue(app.buttons["collection.open-detail"].waitForExistence(timeout: 5))
        app.buttons["collection.open-detail"].tap()
        XCTAssertTrue(app.otherElements["collection.detail"].waitForExistence(timeout: 5))

        app.buttons["tab.home"].tap()
        XCTAssertTrue(app.scrollViews["home.screen"].waitForExistence(timeout: 5))
        XCTAssertFalse(app.navigationBars["홈"].exists)

        app.buttons["tab.storage"].tap()
        XCTAssertTrue(app.scrollViews["storage.screen"].waitForExistence(timeout: 5))

        app.buttons["tab.settings"].tap()
        XCTAssertTrue(app.buttons["settings.milestones"].waitForExistence(timeout: 5))
        app.buttons["settings.milestones"].tap()
        XCTAssertTrue(app.scrollViews["milestones.screen"].waitForExistence(timeout: 5))

        app.buttons["tab.collection"].tap()
        XCTAssertTrue(app.otherElements["collection.detail"].waitForExistence(timeout: 5))

        app.buttons["tab.camera"].tap()
        XCTAssertTrue(app.otherElements["camera.sheet"].waitForExistence(timeout: 5))
        app.buttons["camera.dismiss"].tap()
        XCTAssertTrue(app.otherElements["camera.sheet"].waitForNonExistence(timeout: 5))
        XCTAssertTrue(app.otherElements["collection.detail"].waitForExistence(timeout: 5))
    }

    func testEveryPrimaryNavigationControlIsReachable() {
        let app = XCUIApplication()
        app.launchEnvironment["QA_SKIP_ONBOARDING"] = "1"
        app.launchEnvironment["QA_AUTHENTICATED"] = "1"
        app.launch()

        for identifier in [
            "tab.home",
            "tab.collection",
            "tab.camera",
            "tab.storage",
            "tab.settings"
        ] {
            let control = app.buttons[identifier]
            XCTAssertTrue(control.waitForExistence(timeout: 5), "\(identifier) should exist")
            XCTAssertTrue(control.isHittable, "\(identifier) should have a hittable target")
        }
    }

    func testReduceMotionLaunchContract() {
        let app = XCUIApplication()
        app.launchEnvironment["QA_REDUCE_MOTION"] = "1"
        app.launchEnvironment["QA_SKIP_ONBOARDING"] = "1"
        app.launch()
        XCTAssertTrue(
            app.otherElements["app.shell.reduce-motion"].waitForExistence(timeout: 5)
        )
    }

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
        XCTAssertTrue(app.staticTexts["사진 분석 결과"].waitForExistence(timeout: 5))
        XCTAssertFalse(app.buttons["identification.confirm"].isEnabled)
        app.buttons["identification.candidate.0"].tap()
        XCTAssertTrue(app.buttons["identification.confirm"].isEnabled)
        app.buttons["identification.confirm"].tap()
        XCTAssertTrue(app.navigationBars["식물 등록"].waitForExistence(timeout: 5))
        XCTAssertFalse(app.buttons["registration.submit"].isEnabled)
        app.textFields["registration.name"].tap()
        app.textFields["registration.name"].typeText("몬스테라")
        XCTAssertTrue(app.buttons["registration.submit"].isEnabled)
        app.buttons["registration.submit"].tap()
        XCTAssertTrue(app.staticTexts["registration.saved"].waitForExistence(timeout: 5))
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
        app.launch()
        XCTAssertTrue(app.buttons["home.identify"].waitForExistence(timeout: 5))
        app.buttons["home.identify"].tap()
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
        XCTAssertTrue(app.images["photo.review"].exists)
        XCTAssertTrue(app.buttons["photo.retake"].exists)
        XCTAssertTrue(app.buttons["photo.replace"].exists)
    }

    func testDeniedCameraAndCorruptPhotoPreserveFallbackActions() {
        let denied = XCUIApplication()
        denied.launchEnvironment["QA_SKIP_ONBOARDING"] = "1"
        denied.launchEnvironment["QA_AUTHENTICATED"] = "1"
        denied.launchEnvironment["QA_CAMERA_DENIED"] = "1"
        denied.launch()
        denied.buttons["tab.camera"].tap()
        denied.buttons["photo.camera"].tap()
        XCTAssertTrue(denied.staticTexts["photo.error"].waitForExistence(timeout: 5))
        XCTAssertTrue(denied.buttons["photo.settings"].exists)
        XCTAssertTrue(denied.buttons["photo.library"].exists)
        XCTAssertTrue(denied.buttons["photo.manual"].exists)
        denied.terminate()

        let corrupt = XCUIApplication()
        corrupt.launchEnvironment["QA_SKIP_ONBOARDING"] = "1"
        corrupt.launchEnvironment["QA_AUTHENTICATED"] = "1"
        corrupt.launchEnvironment["QA_PHOTO_FIXTURE"] = "corrupt"
        corrupt.launch()
        corrupt.buttons["tab.camera"].tap()
        XCTAssertTrue(corrupt.staticTexts["photo.error"].waitForExistence(timeout: 5))
        XCTAssertTrue(corrupt.buttons["photo.library"].exists)
        XCTAssertTrue(corrupt.buttons["photo.manual"].exists)
    }
}
