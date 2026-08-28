import XCTest

@MainActor
extension MiniHomeUITestSupport where Self: XCTestCase {
    func waitForMiniHomeElement(
        _ element: XCUIElement,
        timeout: TimeInterval = 5,
        trigger: () -> Void
    ) {
        let appeared = XCTNSPredicateExpectation(
            predicate: NSPredicate(format: "exists == true"),
            object: element
        )
        trigger()
        XCTAssertEqual(
            XCTWaiter.wait(for: [appeared], timeout: timeout),
            .completed
        )
    }

    func waitForMiniHomeElementToDisappear(
        _ element: XCUIElement,
        timeout: TimeInterval = 5,
        trigger: () -> Void
    ) {
        let disappeared = XCTNSPredicateExpectation(
            predicate: NSPredicate(format: "exists == false"),
            object: element
        )
        trigger()
        XCTAssertEqual(
            XCTWaiter.wait(for: [disappeared], timeout: timeout),
            .completed
        )
    }

    func triggerAndWaitForMiniHomeState(
        _ expected: String,
        in app: XCUIApplication,
        trigger: () -> Void
    ) {
        let state = app.staticTexts["minihome.state"]
        let changed = XCTNSPredicateExpectation(
            predicate: NSPredicate(
                format: "exists == true AND label == %@",
                expected
            ),
            object: state
        )
        trigger()
        XCTAssertEqual(
            XCTWaiter.wait(for: [changed], timeout: 5),
            .completed,
            "MiniHome state: \(state.label)"
        )
    }
}
