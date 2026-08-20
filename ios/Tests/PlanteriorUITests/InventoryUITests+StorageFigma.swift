import XCTest

@MainActor
extension InventoryUITests {
    func assertFigmaWarehouseSurface(in app: XCUIApplication) {
        XCTAssertEqual(app.staticTexts["storage.title"].label, "나의 창고")
        XCTAssertEqual(
            app.staticTexts["storage.count"].label,
            "보유 아이템 1개"
        )
        XCTAssertTrue(app.images["storage.image.item-chair"].exists)
        XCTAssertEqual(
            app.buttons["storage.row.item-chair"].value as? String,
            "가구, 보유 중"
        )
    }

    func assertInitialShopPage(in app: XCUIApplication) -> [String] {
        let page = ["shop.row.item-lamp", "shop.row.item-chair"]
        waitForShopRows(page, in: app)
        XCTAssertEqual(
            app.buttons["shop.row.item-lamp"].value as? String,
            "소품, 조건 미충족 · 식물 등록 필요"
        )
        return page
    }

    func assertFigmaShopSurface(in app: XCUIApplication) {
        XCTAssertEqual(app.staticTexts["shop.title"].label, "아이템 상점")
        XCTAssertTrue(app.images["shop.image.item-lamp"].exists)
        XCTAssertEqual(
            app.buttons["shop.row.item-lamp"].value as? String,
            "소품, 조건 미충족 · 식물 등록 필요"
        )
    }

    func assertFigmaDetailSurface(
        itemID: String,
        in app: XCUIApplication
    ) {
        XCTAssertTrue(app.images["storage.detail.hero.\(itemID)"].exists)
        XCTAssertTrue(app.staticTexts["storage.detail.title"].exists)
        XCTAssertTrue(app.buttons["tab.storage"].exists)
        XCTAssertFalse(app.buttons["storage.detail.close"].exists)
    }
}
