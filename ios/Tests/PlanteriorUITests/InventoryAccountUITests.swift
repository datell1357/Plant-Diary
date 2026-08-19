import XCTest

@MainActor
final class InventoryAccountUITests: XCTestCase, InventoryUITestSupport {
    func testAccountRemountDoesNotLeakOwnership() {
        let app = inventoryApp(shopMode: true)
        app.launchEnvironment["QA_INVENTORY_ACCOUNT_ID"] = "account-a"
        app.launch()
        openBackgroundShop(in: app)
        let acquireA = app.buttons["shop.acquire.item-green-wall"]
        _ = triggerAndReadMessage(in: app, previous: nil) {
            acquireA.tap()
        }
        XCTAssertFalse(acquireA.isEnabled)

        app.terminate()
        app.launchEnvironment["QA_INVENTORY_ACCOUNT_ID"] = "account-b"
        app.launch()
        openBackgroundShop(in: app)
        XCTAssertTrue(
            app.buttons["shop.acquire.item-green-wall"].isEnabled
        )

        app.terminate()
        app.launchEnvironment["QA_INVENTORY_ACCOUNT_ID"] = "account-a"
        app.launch()
        openBackgroundShop(in: app)
        XCTAssertFalse(
            app.buttons["shop.acquire.item-green-wall"].isEnabled
        )
        attachJSON(
            [
                "accountAOwned": true,
                "accountBOwned": false,
                "accountARestored": true
            ],
            named: "task-15-account-remount"
        )
    }

    private func openBackgroundShop(in app: XCUIApplication) {
        openStorage(in: app)
        openShop(in: app)
        waitForShopRows(["shop.row.item-green-wall"], in: app) {
            app.buttons["storage.category.background"].tap()
        }
    }
}
