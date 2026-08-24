import XCTest

/// Figma `home-screen-logged-out` §8.3: every signed-out shell affordance is
/// intercepted by the login sheet instead of navigating.
extension AppLaunchUITests {
    func testSignedOutShellInterceptsEveryTabAndCameraWithLogin() {
        let app = XCUIApplication()
        app.launchEnvironment["QA_SKIP_ONBOARDING"] = "1"
        app.launchEnvironment["QA_AUTHENTICATED"] = "0"
        app.launch()

        XCTAssertTrue(app.otherElements["app.shell"].waitForExistence(timeout: 5))
        XCTAssertFalse(app.buttons["auth.open"].exists)

        for identifier in [
            "tab.collection",
            "tab.storage",
            "tab.settings",
            "tab.camera",
            "tab.home"
        ] {
            let control = app.buttons[identifier]
            XCTAssertTrue(control.waitForExistence(timeout: 5), "\(identifier) should exist")
            control.tap()

            XCTAssertTrue(
                app.buttons["auth.apple"].waitForExistence(timeout: 5),
                "\(identifier) should present the login sheet"
            )
            XCTAssertFalse(app.otherElements["capture.camera"].exists)
            if identifier == "tab.camera" {
                let attachment = XCTAttachment(screenshot: app.screenshot())
                attachment.name = "shell-signed-out-login-402"
                attachment.lifetime = .keepAlways
                add(attachment)
            }
            app.buttons["auth.cancel"].tap()
            XCTAssertTrue(app.buttons["auth.apple"].waitForNonExistence(timeout: 5))
            XCTAssertTrue(app.scrollViews["home.screen"].waitForExistence(timeout: 5))
            XCTAssertFalse(app.navigationBars["홈"].exists)
        }
    }
}
