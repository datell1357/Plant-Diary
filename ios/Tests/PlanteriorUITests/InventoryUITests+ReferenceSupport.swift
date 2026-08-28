import XCTest

@MainActor
extension InventoryUITests {
    var shopProductIDs: [String] {
        [
            "item-cozy-rug", "item-vintage-lamp", "item-green-wall",
            "item-succulent-pot", "item-christmas-tree", "item-autumn-frame"
        ]
    }

    func waitForShopReady(in app: XCUIApplication) {
        XCTAssertTrue(
            app.scrollViews["storage.screen"].waitForExistence(timeout: 10)
        )
        let ready = app.descendants(matching: .any)
            .matching(identifier: "shop.ready")
            .firstMatch
        XCTAssertTrue(ready.waitForExistence(timeout: 10))
    }

    func openBackgroundShop(in app: XCUIApplication) {
        waitForShopRows(["shop.row.item-green-wall"], in: app) {
            app.buttons["storage.category.background"].tap()
        }
    }

    func openWarehouse(in app: XCUIApplication) {
        let title = app.staticTexts["storage.title"]
        waitForElement(title) {
            app.buttons["storage.mode.warehouse"].tap()
        }
    }
}
