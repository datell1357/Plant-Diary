import XCTest

@MainActor
extension SettingsDeletionUITests {
    func assertSwitch(
        _ element: XCUIElement,
        reachesValue value: String
    ) {
        let expectation = XCTNSPredicateExpectation(
            predicate: NSPredicate(format: "value == %@", value),
            object: element
        )
        XCTAssertEqual(
            XCTWaiter.wait(for: [expectation], timeout: 5),
            .completed
        )
    }

    func notificationStatus(
        _ label: String,
        in app: XCUIApplication
    ) -> XCUIElement {
        app.staticTexts
            .matching(identifier: "settings.permission.notifications")
            .matching(NSPredicate(format: "label == %@", label))
            .firstMatch
    }
}
