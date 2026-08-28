import XCTest

@MainActor
extension AppLaunchUITests {
    func assertPrimaryNavigationControls(in app: XCUIApplication) {
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

    func attachScreenshot(_ app: XCUIApplication, named name: String) {
        let attachment = XCTAttachment(screenshot: app.screenshot())
        attachment.name = name
        attachment.lifetime = .keepAlways
        add(attachment)
    }
}
