import XCTest

@MainActor
final class InventoryUITests: XCTestCase, InventoryUITestSupport {
    func testCatalogFilterSortAndDetail() {
        let app = inventoryApp(shopMode: true)
        app.launch()
        openStorage(in: app)
        openShop(in: app)
        assertFigmaShopSurface(in: app)
        attachScreenshot(named: "storage-shop-402x874")

        let firstPage = assertInitialShopPage(in: app)
        let ascending = firstPage + ["shop.row.item-green-wall"]
        let descending = Array(ascending.reversed())
        waitForShopRows(ascending, in: app) {
            app.buttons["shop.load-more"].tap()
        }
        waitForShopRows(Array(descending.prefix(2)), in: app) {
            app.buttons["shop.sort"].tap()
        }
        waitForShopRows(descending, in: app) {
            app.buttons["shop.load-more"].tap()
        }
        waitForShopRows(["shop.row.item-lamp"], in: app) {
            app.buttons["storage.category.decoration"].tap()
        }
        let lamp = app.buttons["shop.row.item-lamp"]
        waitForHittable(lamp)
        let detail = app.scrollViews["storage.detail.item-lamp"]
        waitForElement(detail) {
            lamp.tap()
        }
        assertFigmaDetailSurface(itemID: "item-lamp", in: app)
        attachScreenshot(named: "storage-detail-shop-402x874")
        let condition = app.staticTexts["storage.detail.condition"]
        XCTAssertEqual(
            condition.label,
            "등록한 식물이 있어야 획득할 수 있어요."
        )
        let conditionToken = condition.value as? String
        XCTAssertEqual(conditionToken, "registered-plant")
        attachCatalogEvidence(
            firstPage: firstPage,
            ascending: ascending,
            descending: descending,
            condition: condition,
            conditionToken: conditionToken ?? ""
        )
    }

    func testAcquireRetryApplyRemovePreservesOwnership() {
        let app = inventoryApp(
            failFirstAcquisition: true,
            shopMode: true
        )
        app.launch()
        openStorage(in: app)
        openShop(in: app)
        let acquisition = acquireWall(in: app)
        attachJSON(
            [
                "firstFeedback": acquisition.failure,
                "retryFeedback": acquisition.success,
                "retryEnabled": true,
                "duplicatePolicy": "InventoryPolicyTests"
            ],
            named: "task-15-acquisition-retry"
        )

        let placement = applyAndRemoveWall(
            in: app,
            previousFeedback: acquisition.success
        )
        attachJSON(
            [
                "appliedFeedback": placement.applied,
                "removedFeedback": placement.removed,
                "ownedRow": placement.ownedRow,
                "applyControl": placement.applyControl,
                "limits": ["background": 1, "furniture": 10, "decoration": 10]
            ],
            named: "task-15-placement"
        )
        attachScreenshot(named: "task-15-inventory")
    }

    func testWarehouseGridDetailAndPlacementUseFigmaSurface() {
        let app = inventoryApp()
        app.launch()
        openStorage(in: app)
        assertFigmaWarehouseSurface(in: app)
        attachScreenshot(named: "storage-warehouse-402x874")

        let chair = app.buttons["storage.row.item-chair"]
        let detail = app.scrollViews["storage.detail.item-chair"]
        waitForElement(detail) {
            chair.tap()
        }
        assertFigmaDetailSurface(itemID: "item-chair", in: app)
        attachScreenshot(named: "storage-detail-402x874")

        let apply = app.buttons["storage.detail.apply.item-chair"]
        scrollToHittable(
            apply,
            in: app.scrollViews["storage.detail.item-chair"]
        )
        apply.tap()
        XCTAssertTrue(
            app.staticTexts["storage.detail.status"]
                .waitForExistence(timeout: 5)
        )
        XCTAssertEqual(
            app.staticTexts["storage.detail.status"].label,
            "적용 중"
        )
    }

    func testReduceMotionWarehouseEvidence() {
        let app = inventoryApp()
        app.launchEnvironment["QA_REDUCE_MOTION"] = "1"
        app.launch()
        openStorage(in: app)
        assertFigmaWarehouseSurface(in: app)
        XCTAssertTrue(
            app.otherElements["app.shell.reduce-motion"].exists
        )
        attachScreenshot(named: "storage-warehouse-reduce-motion-light")
    }
}
