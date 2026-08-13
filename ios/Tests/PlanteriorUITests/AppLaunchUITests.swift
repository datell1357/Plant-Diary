import XCTest

@MainActor
final class AppLaunchUITests: XCTestCase {
    func testCaptureRenderedShell() {
        let app = XCUIApplication()
        app.launch()

        XCTAssertTrue(app.otherElements["app.shell"].waitForExistence(timeout: 5))
        let screenshot = XCTAttachment(screenshot: XCUIScreen.main.screenshot())
        screenshot.name = "task-4-rendered-shell"
        screenshot.lifetime = .keepAlways
        add(screenshot)
    }

    func testAppShellPreservesTabStacksAndPresentsCameraAction() {
        let app = XCUIApplication()
        app.launch()

        XCTAssertTrue(app.otherElements["app.shell"].waitForExistence(timeout: 5))

        let homeDetail = app.buttons["home.open-detail"]
        XCTAssertTrue(homeDetail.waitForExistence(timeout: 5))
        homeDetail.tap()
        XCTAssertTrue(app.otherElements["home.detail"].exists)

        app.buttons["tab.collection"].tap()
        app.buttons["collection.open-detail"].tap()
        XCTAssertTrue(app.otherElements["collection.detail"].exists)

        app.buttons["tab.home"].tap()
        XCTAssertTrue(app.otherElements["home.detail"].exists)

        app.buttons["tab.storage"].tap()
        app.buttons["storage.open-detail"].tap()
        XCTAssertTrue(app.otherElements["storage.detail"].exists)

        app.buttons["tab.settings"].tap()
        app.buttons["settings.open-detail"].tap()
        XCTAssertTrue(app.otherElements["settings.detail"].exists)

        app.buttons["tab.collection"].tap()
        XCTAssertTrue(app.otherElements["collection.detail"].exists)

        app.buttons["tab.camera"].tap()
        XCTAssertTrue(app.otherElements["camera.sheet"].waitForExistence(timeout: 5))
        app.buttons["camera.dismiss"].tap()
        XCTAssertFalse(app.otherElements["camera.sheet"].exists)
        XCTAssertTrue(app.otherElements["collection.detail"].exists)
    }

    func testEveryPrimaryNavigationControlIsReachable() {
        let app = XCUIApplication()
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
        hostileApp.launch()
        XCTAssertTrue(hostileApp.otherElements["route.unavailable"].waitForExistence(timeout: 5))
        XCTAssertFalse(hostileApp.staticTexts["private-plant"].exists)
        hostileApp.terminate()

        let deletedApp = XCUIApplication()
        deletedApp.launchEnvironment["QA_DEEP_LINK"] = "planterior://plant/deleted-plant"
        deletedApp.launchEnvironment["QA_TARGET_DELETED"] = "1"
        deletedApp.launch()
        XCTAssertTrue(deletedApp.otherElements["route.unavailable"].waitForExistence(timeout: 5))
        XCTAssertFalse(deletedApp.staticTexts["deleted-plant"].exists)
    }

    func testReduceMotionLaunchContract() {
        let app = XCUIApplication()
        app.launchEnvironment["QA_REDUCE_MOTION"] = "1"
        app.launch()
        XCTAssertTrue(
            app.otherElements["app.shell.reduce-motion"].waitForExistence(timeout: 5)
        )
    }
}
