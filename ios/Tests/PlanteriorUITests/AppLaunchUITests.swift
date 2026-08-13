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
        app.launch()

        XCTAssertTrue(app.otherElements["app.shell"].waitForExistence(timeout: 5))

        let homeDetail = app.buttons["home.open-detail"]
        XCTAssertTrue(homeDetail.waitForExistence(timeout: 5))
        homeDetail.tap()
        XCTAssertTrue(app.otherElements["home.detail"].waitForExistence(timeout: 5))

        app.buttons["tab.collection"].tap()
        XCTAssertTrue(app.buttons["collection.open-detail"].waitForExistence(timeout: 5))
        app.buttons["collection.open-detail"].tap()
        XCTAssertTrue(app.otherElements["collection.detail"].waitForExistence(timeout: 5))

        app.buttons["tab.home"].tap()
        XCTAssertTrue(app.otherElements["home.detail"].waitForExistence(timeout: 5))

        app.buttons["tab.storage"].tap()
        XCTAssertTrue(app.buttons["storage.open-detail"].waitForExistence(timeout: 5))
        app.buttons["storage.open-detail"].tap()
        XCTAssertTrue(app.otherElements["storage.detail"].waitForExistence(timeout: 5))

        app.buttons["tab.settings"].tap()
        XCTAssertTrue(app.buttons["settings.open-detail"].waitForExistence(timeout: 5))
        app.buttons["settings.open-detail"].tap()
        XCTAssertTrue(app.otherElements["settings.detail"].waitForExistence(timeout: 5))

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

    func testHostileAndDeletedURLsFallBackWithoutMetadata() {
        let hostileApp = XCUIApplication()
        hostileApp.launchEnvironment["QA_DEEP_LINK"] = "https://evil.test/plant/private-plant"
        hostileApp.launchEnvironment["QA_SKIP_ONBOARDING"] = "1"
        hostileApp.launch()
        XCTAssertTrue(hostileApp.otherElements["route.unavailable"].waitForExistence(timeout: 5))
        XCTAssertFalse(hostileApp.staticTexts["private-plant"].exists)
        hostileApp.terminate()

        let deletedApp = XCUIApplication()
        deletedApp.launchEnvironment["QA_DEEP_LINK"] = "planterior://plant/deleted-plant"
        deletedApp.launchEnvironment["QA_TARGET_DELETED"] = "1"
        deletedApp.launchEnvironment["QA_AUTHENTICATED"] = "1"
        deletedApp.launchEnvironment["QA_SKIP_ONBOARDING"] = "1"
        deletedApp.launch()
        XCTAssertTrue(deletedApp.otherElements["route.unavailable"].waitForExistence(timeout: 5))
        XCTAssertFalse(deletedApp.staticTexts["deleted-plant"].exists)
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

    func testLoginSheetPresentsAndCancelsWithoutPrivateRows() {
        let app = XCUIApplication()
        app.launchEnvironment["QA_SKIP_ONBOARDING"] = "1"
        app.launch()
        XCTAssertTrue(app.buttons["auth.open"].waitForExistence(timeout: 5))
        app.buttons["auth.open"].tap()
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
        corrupt.launchEnvironment["QA_PHOTO_FIXTURE"] = "corrupt"
        corrupt.launch()
        corrupt.buttons["tab.camera"].tap()
        XCTAssertTrue(corrupt.staticTexts["photo.error"].waitForExistence(timeout: 5))
        XCTAssertTrue(corrupt.buttons["photo.library"].exists)
        XCTAssertTrue(corrupt.buttons["photo.manual"].exists)
    }
}
