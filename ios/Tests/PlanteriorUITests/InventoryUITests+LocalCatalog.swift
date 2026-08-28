import Foundation
import XCTest

extension InventoryUITests {
    func testLocalCatalogAcquisitionReachesWarehouseDetailAndPlacement() {
        let accountID = "local-catalog-\(UUID().uuidString)"
        let app = localCatalogInventoryApp(accountID: accountID)
        app.launch()
        waitForLocalCatalogShopReady(in: app)

        let acquire = app.buttons["shop.acquire.item-vintage-lamp"]
        waitForHittable(acquire)
        XCTAssertEqual(
            triggerAndReadMessage(in: app, previous: nil) { acquire.tap() },
            "창고에 추가했어요 · 빈티지 스탠드 조명"
        )
        XCTAssertFalse(acquire.isEnabled)

        let warehouse = app.buttons["storage.mode.warehouse"]
        let ownedRow = app.buttons["storage.row.item-vintage-lamp"]
        waitForElement(ownedRow) { warehouse.tap() }
        let detail = app.scrollViews["storage.detail.item-vintage-lamp"]
        waitForElement(detail) { ownedRow.tap() }
        let apply = app.buttons["storage.detail.apply.item-vintage-lamp"]
        waitForHittable(apply)
        XCTAssertEqual(
            triggerAndReadMessage(in: app, previous: nil) { apply.tap() },
            "미니홈에 적용했어요 · 빈티지 스탠드 조명"
        )
        XCTAssertEqual(app.staticTexts["storage.detail.status"].label, "적용 중")

        app.terminate()
        app.launch()
        waitForLocalCatalogShopReady(in: app)
        let restoredWarehouse = app.buttons["storage.mode.warehouse"]
        let restoredRow = app.buttons["storage.row.item-vintage-lamp"]
        waitForElement(restoredRow) { restoredWarehouse.tap() }
        let restoredDetail = app.scrollViews["storage.detail.item-vintage-lamp"]
        waitForElement(restoredDetail) { restoredRow.tap() }
        XCTAssertEqual(app.staticTexts["storage.detail.status"].label, "적용 중")
    }

    private func waitForLocalCatalogShopReady(in app: XCUIApplication) {
        XCTAssertTrue(
            app.scrollViews["storage.screen"].waitForExistence(timeout: 10)
        )
        XCTAssertTrue(
            app.descendants(matching: .any)
                .matching(identifier: "shop.ready")
                .firstMatch
                .waitForExistence(timeout: 10)
        )
        XCTAssertTrue(
            app.buttons["shop.row.item-vintage-lamp"]
                .waitForExistence(timeout: 10)
        )
    }
}
