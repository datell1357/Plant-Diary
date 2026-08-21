import XCTest

@MainActor
extension InventoryUITestSupport where Self: XCTestCase {
    func waitForHittable(
        _ element: XCUIElement,
        timeout: TimeInterval = 5
    ) {
        let hittable = XCTNSPredicateExpectation(
            predicate: NSPredicate(format: "hittable == true"),
            object: element
        )
        XCTAssertEqual(
            XCTWaiter.wait(for: [hittable], timeout: timeout),
            .completed
        )
    }

    func scrollToHittable(
        _ element: XCUIElement,
        in scrollView: XCUIElement
    ) {
        for _ in 0 ..< 8 where !element.isHittable {
            scrollView.swipeUp()
        }
        XCTAssertTrue(element.isHittable)
    }

    func waitForShopRows(
        _ expected: [String],
        in app: XCUIApplication,
        trigger: () -> Void = {}
    ) {
        let rows = app.buttons.matching(
            NSPredicate(format: "identifier BEGINSWITH 'shop.row.'")
        )
        let changed = XCTNSPredicateExpectation(
            predicate: NSPredicate { _, _ in
                rows.allElementsBoundByIndex.map(\.identifier) == expected
            },
            object: rows
        )
        trigger()
        XCTAssertEqual(
            XCTWaiter.wait(for: [changed], timeout: 5),
            .completed
        )
    }

    func waitForElement(
        _ element: XCUIElement,
        trigger: () -> Void
    ) {
        let appeared = XCTNSPredicateExpectation(
            predicate: NSPredicate(format: "exists == true"),
            object: element
        )
        trigger()
        XCTAssertEqual(
            XCTWaiter.wait(for: [appeared], timeout: 5),
            .completed
        )
    }

    func triggerAndReadMessage(
        in app: XCUIApplication,
        previous: String?,
        trigger: () -> Void
    ) -> String {
        let message = app.staticTexts["storage.message"]
        let predicate = previous.map {
            NSPredicate(format: "exists == true AND label != %@", $0)
        } ?? NSPredicate(format: "exists == true")
        let changed = XCTNSPredicateExpectation(
            predicate: predicate,
            object: message
        )
        trigger()
        XCTAssertEqual(
            XCTWaiter.wait(for: [changed], timeout: 5),
            .completed
        )
        return message.label
    }
}
