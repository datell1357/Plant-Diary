import XCTest

@MainActor
final class InventoryAccessibilityUITests: XCTestCase, InventoryUITestSupport {
    func testWarehouseControlsRemainReachableAtAX5() {
        let app = inventoryApp()
        app.launchEnvironment["QA_INVENTORY_SIZE_CATEGORY"] = "AX5"
        app.launch()
        openStorage(in: app)
        attachScreenshot(named: "task-15-inventory-ax5")

        let row = app.buttons["storage.row.item-chair"]
        XCTAssertTrue(row.waitForExistence(timeout: 5))
        XCTAssertTrue(row.isHittable)
        XCTAssertTrue(
            app.buttons["storage.apply.item-chair"].isHittable
        )
    }
}
