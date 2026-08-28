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
        assertSinglePersistentTabBar(in: app, selected: "tab.home")
        app.buttons["tab.collection"].tap()
        XCTAssertTrue(app.buttons["collection.open-detail"].waitForExistence(timeout: 5))
        assertSinglePersistentTabBar(in: app, selected: "tab.collection")
        app.buttons["collection.open-detail"].tap()
        XCTAssertTrue(app.scrollViews["collection.summary.screen"].waitForExistence(timeout: 5))
        assertSinglePersistentTabBar(in: app, selected: "tab.collection")
        app.buttons["tab.home"].tap()
        XCTAssertTrue(app.scrollViews["home.screen"].waitForExistence(timeout: 5))
        XCTAssertFalse(app.navigationBars["홈"].exists)
        assertSinglePersistentTabBar(in: app, selected: "tab.home")

        app.buttons["tab.storage"].tap()
        XCTAssertTrue(app.scrollViews["storage.screen"].waitForExistence(timeout: 5))
        assertSinglePersistentTabBar(in: app, selected: "tab.storage")

        app.buttons["tab.settings"].tap()
        XCTAssertTrue(app.buttons["settings.milestones"].waitForExistence(timeout: 5))
        assertSinglePersistentTabBar(in: app, selected: "tab.settings")
        XCTAssertEqual(app.staticTexts["settings.profile.name"].label, "민지")
        XCTAssertEqual(app.staticTexts["settings.profile.email"].label, "minji@email.com")
        openMilestonesAfterRecordingHitGeometry(in: app)
        assertSinglePersistentTabBar(in: app, selected: "tab.settings")
        attachScreenshot(app, named: "shell-milestone-402")

        app.buttons["tab.collection"].tap()
        XCTAssertTrue(app.scrollViews["collection.summary.screen"].waitForExistence(timeout: 5))
        assertSinglePersistentTabBar(in: app, selected: "tab.collection")

        app.buttons["tab.camera"].tap()
        XCTAssertTrue(app.otherElements["capture.camera"].waitForExistence(timeout: 5))
        assertNoPersistentAppTabBar(in: app)
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
        assertSinglePersistentTabBar(in: app, selected: "tab.home")
        app.buttons["tab.collection"].tap()
        XCTAssertTrue(app.scrollViews["collection.screen"].waitForExistence(timeout: 5))
        assertSinglePersistentTabBar(in: app, selected: "tab.collection")
        app.buttons["tab.storage"].tap()
        XCTAssertTrue(app.scrollViews["storage.screen"].waitForExistence(timeout: 5))
        assertSinglePersistentTabBar(in: app, selected: "tab.storage")
        app.buttons["tab.home"].tap()
        XCTAssertTrue(app.scrollViews["home.screen"].waitForExistence(timeout: 5))
        assertSinglePersistentTabBar(in: app, selected: "tab.home")
        app.buttons["tab.camera"].tap()
        XCTAssertTrue(app.otherElements["capture.camera"].waitForExistence(timeout: 5))
        assertNoPersistentAppTabBar(in: app)
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
        assertHomePaintedAndInteractiveBoundary(
            in: app,
            homeScreen: homeScreen,
            materialMinY: app.frame.maxY - 96
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
}
