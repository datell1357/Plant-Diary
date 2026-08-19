import XCTest

@MainActor
final class InventoryUITests: XCTestCase, InventoryUITestSupport {
    func testCatalogFilterSortAndDetail() {
        let app = inventoryApp(shopMode: true)
        app.launch()
        openStorage(in: app)
        openShop(in: app)

        let firstPage = [
            "shop.row.item-lamp",
            "shop.row.item-chair"
        ]
        let ascending = firstPage + [
            "shop.row.item-green-wall"
        ]
        let descending = Array(ascending.reversed())
        waitForShopRows(firstPage, in: app)
        XCTAssertTrue(
            app.staticTexts["조건 미충족 · 식물 등록 필요"].exists
        )
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
        let detail = app.otherElements["storage.detail.item-lamp"]
        waitForElement(detail) {
            lamp.tap()
        }
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
}
