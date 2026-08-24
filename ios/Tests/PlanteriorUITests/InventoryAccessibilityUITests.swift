import XCTest

@MainActor
final class InventoryAccessibilityUITests: XCTestCase, InventoryUITestSupport {
    func testWarehouseControlsRemainReachableAtAX5() {
        let app = inventoryApp()
        app.launchEnvironment["QA_INVENTORY_SIZE_CATEGORY"] = "AX5"
        app.launch()
        openStorage(in: app)
        attachScreenshot(named: "task-15-inventory-ax5")
        XCTAssertEqual(
            app.scrollViews.matching(identifier: "storage.screen").count,
            1
        )

        let row = app.buttons["storage.row.item-chair"]
        XCTAssertTrue(row.waitForExistence(timeout: 5))
        scrollToHittable(row, in: app.scrollViews["storage.screen"])
        XCTAssertTrue(app.images["storage.image.item-chair"].exists)
        row.tap()

        let detail = app.scrollViews["storage.detail.item-chair"]
        XCTAssertTrue(detail.waitForExistence(timeout: 5))
        let action = app.buttons["storage.detail.apply.item-chair"]
        XCTAssertTrue(action.waitForExistence(timeout: 5))
        scrollToHittable(action, in: detail)
    }
}
