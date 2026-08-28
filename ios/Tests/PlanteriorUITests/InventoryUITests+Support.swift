import XCTest

protocol InventoryUITestSupport {}

@MainActor
extension InventoryUITestSupport where Self: XCTestCase {
    func inventoryApp(
        failFirstAcquisition: Bool = false,
        shopMode: Bool = false
    ) -> XCUIApplication {
        let app = XCUIApplication()
        app.launchEnvironment["QA_SKIP_ONBOARDING"] = "1"
        app.launchEnvironment["QA_AUTHENTICATED"] = "1"
        app.launchEnvironment["QA_INVENTORY_FIXTURE"] = "1"
        app.launchEnvironment["QA_INVENTORY_ROUTE"] = "1"
        app.launchEnvironment["QA_RESET_COLLECTION"] = "1"
        app.launchEnvironment["QA_INVENTORY_RESET_TOKEN"] = UUID().uuidString
        app.launchEnvironment["QA_INVENTORY_NOW"] = "2026-08-11T02:00:00Z"
        app.launchEnvironment["QA_HOME_FIXTURE"] = "1"
        app.launchEnvironment["QA_MINIHOME_RESET_TOKEN"] = UUID().uuidString
        app.launchEnvironment["QA_WEATHER_AUTHORIZATION"] = "denied"
        app.launchEnvironment["QA_NOTIFICATION_AUTHORIZATION"] = "authorized"
        app.launchEnvironment["QA_NOTIFICATION_ENDPOINT"] = "registered"
        if failFirstAcquisition {
            app.launchEnvironment["QA_INVENTORY_FAIL_ONCE"] = "1"
        }
        if shopMode {
            app.launchEnvironment["QA_INVENTORY_MODE"] = "shop"
        }
        return app
    }

    func localCatalogInventoryApp(accountID: String) -> XCUIApplication {
        let app = XCUIApplication()
        app.launchEnvironment["QA_SKIP_ONBOARDING"] = "1"
        app.launchEnvironment["QA_AUTHENTICATED"] = "1"
        app.launchEnvironment["QA_INVENTORY_ROUTE"] = "1"
        app.launchEnvironment["QA_INVENTORY_MODE"] = "shop"
        app.launchEnvironment["QA_INVENTORY_ACCOUNT_ID"] = accountID
        app.launchEnvironment["QA_INVENTORY_NOW"] = "2026-08-11T02:00:00Z"
        app.launchEnvironment["QA_RESET_COLLECTION"] = "1"
        app.launchEnvironment["QA_WEATHER_AUTHORIZATION"] = "denied"
        app.launchEnvironment["QA_NOTIFICATION_AUTHORIZATION"] = "authorized"
        app.launchEnvironment["QA_NOTIFICATION_ENDPOINT"] = "registered"
        return app
    }

    func openStorage(in app: XCUIApplication) {
        XCTAssertTrue(
            app.scrollViews["storage.screen"].waitForExistence(timeout: 10)
        )
        let ready = app.descendants(matching: .any).matching(
            NSPredicate(
                format: "identifier IN %@",
                ["storage.count", "shop.ready"]
            )
        ).firstMatch
        XCTAssertTrue(ready.waitForExistence(timeout: 10))
    }

    func openShop(in app: XCUIApplication) {
        let ready = app.descendants(matching: .any)
            .matching(identifier: "shop.ready")
            .firstMatch
        XCTAssertTrue(ready.waitForExistence(timeout: 10))
        XCTAssertTrue(
            app.buttons["shop.row.item-vintage-lamp"]
                .waitForExistence(timeout: 10)
        )
    }
}
