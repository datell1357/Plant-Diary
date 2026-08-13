import XCTest

final class AppLaunchUITests: XCTestCase {
    func testAppShellLaunches() {
        let app = XCUIApplication()
        app.launch()
        XCTAssertTrue(app.otherElements["app.shell"].waitForExistence(timeout: 5))
        XCTAssertTrue(app.staticTexts["초보 식집사"].exists)
    }
}
