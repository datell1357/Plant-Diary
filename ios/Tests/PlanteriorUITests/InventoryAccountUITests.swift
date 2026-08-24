import XCTest

@MainActor
final class InventoryAccountUITests: XCTestCase, InventoryUITestSupport {
    func testAccountRemountDoesNotLeakOwnership() {
        let accountScope = UUID().uuidString
        let accountA = "account-a-\(accountScope)"
        let accountB = "account-b-\(accountScope)"
        let app = inventoryApp(shopMode: true)
        app.launchEnvironment["QA_INVENTORY_ACCOUNT_ID"] = accountA
        app.launch()
        openBackgroundShop(in: app)
        let acquireA = app.buttons["shop.acquire.item-green-wall"]
        waitForHittable(acquireA)
        _ = triggerAndReadMessage(in: app, previous: nil) {
            acquireA.tap()
        }
        XCTAssertTrue(acquireA.exists)
        XCTAssertFalse(acquireA.isEnabled)
        XCTAssertEqual(acquireA.value as? String, "보유 중")

        app.terminate()
        app.launchEnvironment["QA_INVENTORY_ACCOUNT_ID"] = accountB
        app.launch()
        openBackgroundShop(in: app)
        let acquireB = app.buttons["shop.acquire.item-green-wall"]
        XCTAssertTrue(acquireB.exists)
        XCTAssertTrue(acquireB.isEnabled)
        XCTAssertEqual(acquireB.value as? String, "획득 가능")

        app.terminate()
        app.launchEnvironment["QA_INVENTORY_ACCOUNT_ID"] = accountA
        app.launch()
        openBackgroundShop(in: app)
        let restoredA = app.buttons["shop.acquire.item-green-wall"]
        XCTAssertTrue(restoredA.exists)
        XCTAssertFalse(restoredA.isEnabled)
        XCTAssertEqual(restoredA.value as? String, "보유 중")
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
        XCTAssertTrue(
            app.scrollViews["storage.screen"].waitForExistence(timeout: 10)
        )
        let ready = app.descendants(matching: .any)
            .matching(identifier: "shop.ready")
            .firstMatch
        XCTAssertTrue(ready.waitForExistence(timeout: 10))
        let background = app.buttons["storage.category.background"]
        waitForHittable(background)
        waitForShopRows(["shop.row.item-green-wall"], in: app) {
            background.tap()
        }
    }
}
