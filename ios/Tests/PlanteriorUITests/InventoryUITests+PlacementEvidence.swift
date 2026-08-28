import XCTest

@MainActor
extension InventoryUITests {
    func testWarehouseGridDetailAndPlacementUseFigmaSurface() {
        let app = inventoryApp()
        app.launch()
        openStorage(in: app)

        let chair = app.buttons["storage.row.item-chair"]
        XCTAssertTrue(chair.waitForExistence(timeout: 5))
        chair.tap()
        let detail = app.scrollViews["storage.detail.item-chair"]
        XCTAssertTrue(detail.waitForExistence(timeout: 5))

        let apply = app.buttons["storage.detail.apply.item-chair"]
        scrollToHittable(apply, in: detail)
        apply.tap()
        XCTAssertEqual(app.staticTexts["storage.detail.status"].label, "적용 중")
        XCTAssertTrue(app.buttons["storage.detail.remove.item-chair"].exists)
    }
}
