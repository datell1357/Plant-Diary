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
        app.launchEnvironment["QA_COLLECTION_FIXTURE"] = "1"
        app.launchEnvironment["QA_RESET_COLLECTION"] = "1"
        app.launchEnvironment["QA_AUTH_PROFILE_NAME"] = "민지"
        app.launchEnvironment["QA_AUTH_PROFILE_EMAIL"] = "minji@email.com"
        app.launch()
        XCTAssertTrue(app.otherElements["app.shell"].waitForExistence(timeout: 5))
        XCTAssertTrue(app.scrollViews["home.screen"].waitForExistence(timeout: 5))
        XCTAssertFalse(app.navigationBars["홈"].exists)
        app.buttons["tab.collection"].tap()
        XCTAssertTrue(app.buttons["collection.open-detail"].waitForExistence(timeout: 5))
        app.buttons["collection.open-detail"].tap()
        XCTAssertTrue(app.scrollViews["collection.summary.screen"].waitForExistence(timeout: 5))
        app.buttons["tab.home"].tap()
        XCTAssertTrue(app.scrollViews["home.screen"].waitForExistence(timeout: 5))
        XCTAssertFalse(app.navigationBars["홈"].exists)

        app.buttons["tab.storage"].tap()
        XCTAssertTrue(app.scrollViews["storage.screen"].waitForExistence(timeout: 5))

        app.buttons["tab.settings"].tap()
        XCTAssertTrue(app.buttons["settings.milestones"].waitForExistence(timeout: 5))
        XCTAssertEqual(app.staticTexts["settings.profile.name"].label, "민지")
        XCTAssertEqual(app.staticTexts["settings.profile.email"].label, "minji@email.com")
        app.buttons["settings.milestones"].tap()
        XCTAssertTrue(app.scrollViews["milestones.screen"].waitForExistence(timeout: 5))
        attachScreenshot(app, named: "shell-milestone-402")

        app.buttons["tab.collection"].tap()
        XCTAssertTrue(app.scrollViews["collection.summary.screen"].waitForExistence(timeout: 5))

        app.buttons["tab.camera"].tap()
        XCTAssertTrue(app.otherElements["capture.camera"].waitForExistence(timeout: 5))
        app.buttons["capture.close"].tap()
        XCTAssertTrue(app.otherElements["capture.camera"].waitForNonExistence(timeout: 5))
        XCTAssertTrue(app.scrollViews["collection.summary.screen"].waitForExistence(timeout: 5))
        attachScreenshot(app, named: "shell-camera-return-402")
    }

    func testCanonicalSharedTabsRemainFunctionalAcrossRepresentativeDomains() {
        let app = XCUIApplication()
        app.launchEnvironment["QA_SKIP_ONBOARDING"] = "1"
        app.launchEnvironment["QA_AUTHENTICATED"] = "1"
        app.launchEnvironment["QA_COLLECTION_FIXTURE"] = "1"
        app.launchEnvironment["QA_RESET_COLLECTION"] = "1"
        app.launch()

        XCTAssertTrue(app.scrollViews["home.screen"].waitForExistence(timeout: 5))
        app.buttons["tab.collection"].tap()
        XCTAssertTrue(app.scrollViews["collection.screen"].waitForExistence(timeout: 5))
        app.buttons["tab.storage"].tap()
        XCTAssertTrue(app.scrollViews["storage.screen"].waitForExistence(timeout: 5))
        app.buttons["tab.home"].tap()
        XCTAssertTrue(app.scrollViews["home.screen"].waitForExistence(timeout: 5))
        app.buttons["tab.camera"].tap()
        XCTAssertTrue(app.otherElements["capture.camera"].waitForExistence(timeout: 5))
        app.buttons["capture.close"].tap()
        XCTAssertTrue(app.scrollViews["home.screen"].waitForExistence(timeout: 5))
    }

    func testEveryPrimaryNavigationControlIsReachable() {
        let app = XCUIApplication()
        app.launchEnvironment["QA_SKIP_ONBOARDING"] = "1"
        app.launchEnvironment["QA_AUTHENTICATED"] = "1"
        app.launch()

        assertPrimaryNavigationControls(in: app)

        let ordinaryTab = app.buttons["tab.home"]
        let camera = app.buttons["tab.camera"]
        let homeScreen = app.scrollViews["home.screen"]
        let expectedSurfaceMinimumY = app.frame.maxY - 84
        XCTAssertGreaterThanOrEqual(
            ordinaryTab.frame.minY,
            expectedSurfaceMinimumY,
            "ordinary tab targets should remain inside the shared surface"
        )
        XCTAssertLessThanOrEqual(
            ordinaryTab.frame.maxY,
            app.frame.maxY - 14,
            "tab targets should leave the system home-indicator edge clear"
        )
        XCTAssertGreaterThanOrEqual(camera.frame.height, 52)
        XCTAssertLessThan(
            camera.frame.minY,
            ordinaryTab.frame.minY,
            "the camera action should remain visibly raised"
        )
        XCTAssertLessThanOrEqual(
            camera.frame.maxY,
            app.frame.maxY - 14,
            "the raised camera should leave the home-indicator edge clear"
        )
        XCTAssertLessThanOrEqual(
            homeScreen.frame.maxY,
            ordinaryTab.frame.minY,
            "the in-flow shell must keep navigation content above tab hit targets"
        )
        print(
            "SHELL_GEOMETRY viewport=\(app.frame) home=\(homeScreen.frame) "
                + "tab=\(ordinaryTab.frame) camera=\(camera.frame)"
        )
        attachScreenshot(app, named: "shell-responsive")
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
        XCTAssertTrue(app.buttons["photo.retake"].exists)
        XCTAssertFalse(app.buttons["photo.replace"].exists)
        XCTAssertFalse(app.buttons["photo.manual"].exists)
    }

    private func assertPrimaryNavigationControls(
        in app: XCUIApplication
    ) {
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
            XCTAssertGreaterThanOrEqual(
                control.frame.height,
                44,
                "\(identifier) should preserve its minimum target"
            )
        }
    }

    private func attachScreenshot(
        _ app: XCUIApplication,
        named name: String
    ) {
        let attachment = XCTAttachment(screenshot: app.screenshot())
        attachment.name = name
        attachment.lifetime = .keepAlways
        add(attachment)
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
