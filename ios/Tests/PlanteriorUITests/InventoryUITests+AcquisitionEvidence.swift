import XCTest

@MainActor
extension InventoryUITests {
    func testAcquireRetryApplyRemovePreservesOwnership() {
        let app = inventoryApp(failFirstAcquisition: true, shopMode: true)
        app.launch()
        waitForShopReady(in: app)
        openBackgroundShop(in: app)

        let acquire = app.buttons["shop.acquire.item-green-wall"]
        XCTAssertTrue(acquire.isEnabled)
        let failure = triggerAndReadMessage(in: app, previous: nil) {
            acquire.tap()
        }
        XCTAssertEqual(failure, "획득하지 못했어요. 다시 시도해 주세요.")
        let success = triggerAndReadMessage(in: app, previous: failure) {
            acquire.tap()
        }
        XCTAssertEqual(success, "창고에 추가했어요 · 체크무늬 커튼 창문")
        XCTAssertFalse(acquire.isEnabled, "owned items cannot be acquired twice")

        openWarehouse(in: app)
        let ownedRow = app.buttons["storage.row.item-green-wall"]
        scrollToHittable(ownedRow, in: app.scrollViews["storage.screen"])
        ownedRow.tap()

        let apply = app.buttons["storage.detail.apply.item-green-wall"]
        XCTAssertTrue(apply.waitForExistence(timeout: 5))
        let applied = triggerAndReadMessage(in: app, previous: success) {
            apply.tap()
        }
        XCTAssertEqual(applied, "미니홈에 적용했어요 · 체크무늬 커튼 창문")
        XCTAssertEqual(app.staticTexts["storage.detail.status"].label, "적용 중")

        app.terminate()
        app.launch()
        waitForShopReady(in: app)
        openWarehouse(in: app)
        let restoredRow = app.buttons["storage.row.item-green-wall"]
        scrollToHittable(restoredRow, in: app.scrollViews["storage.screen"])
        restoredRow.tap()
        XCTAssertEqual(app.staticTexts["storage.detail.status"].label, "적용 중")

        let remove = app.buttons["storage.detail.remove.item-green-wall"]
        XCTAssertTrue(remove.waitForExistence(timeout: 5))
        let removed = triggerAndReadMessage(in: app, previous: nil) {
            remove.tap()
        }
        XCTAssertEqual(removed, "미니홈에서 제거했어요 · 체크무늬 커튼 창문")
        app.buttons["storage.detail.back"].tap()
        XCTAssertTrue(restoredRow.waitForExistence(timeout: 5))

        app.terminate()
        app.launch()
        waitForShopReady(in: app)
        openBackgroundShop(in: app)
        XCTAssertFalse(app.buttons["shop.acquire.item-green-wall"].isEnabled)
    }
}
