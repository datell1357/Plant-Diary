import XCTest

@MainActor
extension InventoryUITests {
    func testShopMatchesReferenceCreditFiltersAndProducts() {
        let app = inventoryApp(shopMode: true)
        app.launch()
        waitForShopReady(in: app)

        assertShopHeaderAndFilters(in: app)
        let products = shopProductIDs
        assertShopRows(products, in: app)
        assertShopAcquisitionSemantics(in: app)
        assertShopPromoBadges(in: app)
        assertShopClearsTabMaterial(in: app)
        attachScreenshot(named: "storage-shop-402x874")
    }

    func testCatalogFilterSortAndDetail() {
        let firstPageApp = inventoryApp(shopMode: true)
        firstPageApp.launchEnvironment["QA_INVENTORY_VISIBLE_LIMIT"] = "2"
        firstPageApp.launch()
        waitForShopReady(in: firstPageApp)
        waitForShopRows(
            ["shop.row.item-cozy-rug", "shop.row.item-vintage-lamp"],
            in: firstPageApp
        )
        firstPageApp.terminate()

        let descendingApp = inventoryApp(shopMode: true)
        descendingApp.launchEnvironment["QA_INVENTORY_SORT"] = "descending"
        descendingApp.launch()
        waitForShopReady(in: descendingApp)
        let descending = shopProductIDs.reversed().map { "shop.row.\($0)" }
        waitForShopRows(Array(descending), in: descendingApp)

        let winter = descendingApp.buttons["shop.acquire.item-christmas-tree"]
        let autumn = descendingApp.buttons["shop.acquire.item-autumn-frame"]
        XCTAssertFalse(winter.isEnabled)
        XCTAssertFalse(autumn.isEnabled)
        XCTAssertEqual(winter.value as? String, "조건 미충족 · 조건 확인 필요")
        XCTAssertEqual(autumn.value as? String, "조건 미충족 · 조건 확인 필요")
        XCTAssertEqual(
            descendingApp.staticTexts["shop.promo.item-christmas-tree"].label,
            "크리스마스 트리 프로모션, 겨울 시즌 한정"
        )
        XCTAssertEqual(
            descendingApp.staticTexts["shop.promo.item-autumn-frame"].label,
            "가을 단풍 벽장식 프로모션, 가을 시즌 한정"
        )

        let seasonal = descendingApp.buttons["storage.category.seasonal"]
        waitForShopRows(
            ["shop.row.item-autumn-frame", "shop.row.item-christmas-tree"],
            in: descendingApp
        ) {
            seasonal.tap()
        }
        waitForShopRows(Array(descending), in: descendingApp) {
            seasonal.tap()
        }

        let vintage = descendingApp.buttons["shop.row.item-vintage-lamp"]
        let detail = descendingApp.scrollViews["storage.detail.item-vintage-lamp"]
        waitForElement(detail) { vintage.tap() }
        XCTAssertTrue(
            descendingApp.images["storage.detail.hero.item-vintage-lamp"].exists
        )
    }

    private func assertShopHeaderAndFilters(in app: XCUIApplication) {
        XCTAssertEqual(app.staticTexts["shop.title"].label, "아이템 상점")
        XCTAssertEqual(app.staticTexts["shop.credit.label"].label, "보유 크레딧")
        XCTAssertEqual(app.images["shop.credit.icon"].label, "크레딧")
        XCTAssertEqual(app.staticTexts["shop.credit.amount"].label, "1,250")
        XCTAssertFalse(app.buttons["storage.mode.shop"].exists)
        for filter in ["all", "background", "furniture", "decoration", "seasonal"] {
            XCTAssertTrue(app.buttons["storage.category.\(filter)"].exists)
        }
    }

    private func assertShopRows(
        _ products: [String],
        in app: XCUIApplication
    ) {
        waitForShopRows(products.map { "shop.row.\($0)" }, in: app)
        XCTAssertEqual(
            app.buttons.matching(
                NSPredicate(format: "identifier BEGINSWITH 'shop.row.'")
            ).count,
            6
        )
        let firstImage = app.images["shop.image.item-cozy-rug"].frame
        let firstRow = app.buttons["shop.row.item-cozy-rug"].frame
        XCTAssertEqual(firstImage.minY, 199, accuracy: 1)
        XCTAssertEqual(firstImage.height, 110, accuracy: 1)
        XCTAssertEqual(firstRow.minY, firstImage.minY, accuracy: 1)
        XCTAssertEqual(firstRow.maxY, 336.5, accuracy: 0.5)
    }

    private func assertShopAcquisitionSemantics(in app: XCUIApplication) {
        let eligible = app.buttons["shop.acquire.item-vintage-lamp"]
        let owned = app.buttons["shop.acquire.item-cozy-rug"]
        let exactSemantics = [
            "item-cozy-rug": "가구, 보유 중",
            "item-vintage-lamp": "소품, 획득 가능",
            "item-green-wall": "배경, 획득 가능",
            "item-succulent-pot": "소품, 획득 가능",
            "item-christmas-tree": "소품, 조건 미충족 · 조건 확인 필요",
            "item-autumn-frame": "소품, 조건 미충족 · 조건 확인 필요"
        ]
        for (itemID, value) in exactSemantics {
            XCTAssertEqual(app.buttons["shop.row.\(itemID)"].value as? String, value)
        }
        XCTAssertEqual(eligible.value as? String, "획득 가능")
        XCTAssertEqual(owned.value as? String, "보유 중")
        XCTAssertTrue(eligible.isEnabled)
        XCTAssertFalse(owned.isEnabled)
        XCTAssertGreaterThanOrEqual(eligible.frame.width, 44)
        XCTAssertGreaterThanOrEqual(eligible.frame.height, 44)
    }

    private func assertShopClearsTabMaterial(in app: XCUIApplication) {
        let ready = app.descendants(matching: .any)
            .matching(identifier: "shop.ready")
            .firstMatch
        let materialMinY = app.frame.maxY - 62 - 34
        XCTAssertLessThanOrEqual(ready.frame.maxY, materialMinY)
    }

    private func assertShopPromoBadges(in app: XCUIApplication) {
        let exactBadges = [
            "item-green-wall": "체크무늬 커튼 창문 프로모션, 7일 연속 출석",
            "item-christmas-tree": "크리스마스 트리 프로모션, 겨울 시즌 한정",
            "item-autumn-frame": "가을 단풍 벽장식 프로모션, 가을 시즌 한정"
        ]
        for (itemID, label) in exactBadges {
            XCTAssertEqual(app.staticTexts["shop.promo.\(itemID)"].label, label)
        }
    }
}
