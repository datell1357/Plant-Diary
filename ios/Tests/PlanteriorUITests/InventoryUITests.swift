import XCTest

@MainActor
final class InventoryUITests: XCTestCase, InventoryUITestSupport {
    func testWarehouseMatchesReferenceCatalogAndGrid() {
        let app = inventoryApp()
        app.launch()
        openStorage(in: app)

        XCTAssertEqual(app.staticTexts["storage.title"].label, "나의 창고")
        XCTAssertEqual(app.staticTexts["storage.count"].label, "보유 아이템 12개")
        XCTAssertFalse(app.buttons["storage.mode.warehouse"].exists)

        let ownedItems = [
            "item-mini-shelf", "item-small-rug", "item-window-frame",
            "item-flower-stand", "item-lamp", "item-wall-art",
            "item-chair", "item-cushion", "item-book-cart",
            "item-plant-rack", "item-round-mat", "item-cozy-rug"
        ]
        for itemID in ownedItems.prefix(8) {
            XCTAssertTrue(
                app.buttons["storage.row.\(itemID)"].waitForExistence(timeout: 5),
                "missing warehouse fixture item \(itemID)"
            )
        }
        let restingGrid = app.descendants(matching: .any)["storage.resting.grid"]
        XCTAssertTrue(restingGrid.exists)
        let visibleAtRest = restingGrid.buttons.allElementsBoundByIndex
            .map(\.identifier)
        XCTAssertEqual(
            visibleAtRest,
            ownedItems.prefix(8).map { "storage.row.\($0)" },
            "the resting 402x874 composition exposes the reference eight cards"
        )
        for itemID in ["item-mini-shelf", "item-small-rug", "item-flower-stand"] {
            XCTAssertTrue(app.staticTexts["storage.applied.\(itemID)"].exists)
        }

        let first = app.buttons["storage.row.item-mini-shelf"].frame
        let second = app.buttons["storage.row.item-small-rug"].frame
        XCTAssertEqual(first.width, 110, accuracy: 1)
        XCTAssertEqual(second.width, 110, accuracy: 1)
        XCTAssertEqual(first.minX, 16, accuracy: 1)
        XCTAssertEqual(first.minY, 179, accuracy: 1)
        XCTAssertEqual(first.height, 130, accuracy: 1)
        XCTAssertEqual(second.minX - first.maxX, 10, accuracy: 1)
        let eighth = app.buttons["storage.row.item-cushion"].frame
        XCTAssertEqual(eighth.minX, 136, accuracy: 1)
        XCTAssertEqual(eighth.minY, 459, accuracy: 1)
        XCTAssertEqual(eighth.width, 110, accuracy: 1)
        XCTAssertEqual(eighth.height, 130, accuracy: 1)
        attachScreenshot(named: "storage-warehouse-402x874")

        for itemID in ownedItems.dropFirst(8) {
            let row = app.buttons["storage.row.\(itemID)"]
            scrollToHittable(row, in: app.scrollViews["storage.screen"])
            XCTAssertTrue(
                row.isHittable,
                "all 12 owned items remain scroll-reachable"
            )
        }
    }

    func testShopMatchesReferenceCreditFiltersAndProducts() {
        let app = inventoryApp(shopMode: true)
        app.launch()
        waitForShopReady(in: app)

        XCTAssertEqual(app.staticTexts["shop.title"].label, "아이템 상점")
        XCTAssertEqual(app.staticTexts["shop.credit.label"].label, "보유 크레딧")
        XCTAssertEqual(app.images["shop.credit.icon"].label, "크레딧")
        XCTAssertEqual(app.staticTexts["shop.credit.amount"].label, "1,250")
        XCTAssertFalse(app.buttons["storage.mode.shop"].exists)
        for filter in ["all", "background", "furniture", "decoration", "seasonal"] {
            XCTAssertTrue(app.buttons["storage.category.\(filter)"].exists)
        }

        let products = shopProductIDs
        waitForShopRows(products.map { "shop.row.\($0)" }, in: app)
        XCTAssertEqual(
            app.buttons.matching(
                NSPredicate(format: "identifier BEGINSWITH 'shop.row.'")
            ).count,
            6
        )
        XCTAssertEqual(
            app.images["shop.image.item-cozy-rug"].frame.height,
            110,
            accuracy: 1
        )
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
        let exactBadges = [
            "item-green-wall": "체크무늬 커튼 창문 프로모션, 7일 연속 출석",
            "item-christmas-tree": "크리스마스 트리 프로모션, 겨울 시즌 한정",
            "item-autumn-frame": "가을 단풍 벽장식 프로모션, 가을 시즌 한정"
        ]
        for (itemID, label) in exactBadges {
            XCTAssertEqual(app.staticTexts["shop.promo.\(itemID)"].label, label)
        }
        attachScreenshot(named: "storage-shop-402x874")
    }

    func testLampDetailMatchesReferenceHeroFavoriteAndContext() {
        let app = inventoryApp()
        app.launch()
        openStorage(in: app)

        let lamp = app.buttons["storage.row.item-lamp"]
        XCTAssertTrue(lamp.waitForExistence(timeout: 5))
        lamp.tap()

        let detail = app.scrollViews["storage.detail.item-lamp"]
        XCTAssertTrue(detail.waitForExistence(timeout: 5))
        XCTAssertEqual(app.staticTexts["storage.detail.title"].label, "감성 조명")
        let back = app.buttons["storage.detail.back"]
        let chromeTitle = app.staticTexts["storage.detail.chrome.title"]
        let favorite = app.buttons["storage.detail.favorite.item-lamp"]
        XCTAssertTrue(back.isHittable)
        XCTAssertEqual(back.frame.width, 44, accuracy: 1)
        XCTAssertEqual(back.frame.height, 44, accuracy: 1)
        XCTAssertEqual(chromeTitle.frame.minX, 59, accuracy: 2)
        XCTAssertLessThan(chromeTitle.frame.maxX, favorite.frame.minX)
        XCTAssertEqual(favorite.frame.width, 44, accuracy: 1)
        XCTAssertEqual(favorite.frame.height, 44, accuracy: 1)
        XCTAssertTrue(favorite.isHittable)
        XCTAssertTrue(app.buttons["tab.storage"].exists)
        XCTAssertTrue(app.buttons["tab.storage"].isHittable)

        let heroFrame = app.images["storage.detail.hero.item-lamp"].frame
        XCTAssertEqual(heroFrame.minX, 20, accuracy: 1)
        XCTAssertEqual(heroFrame.minY, 101, accuracy: 1)
        XCTAssertEqual(heroFrame.width, 362, accuracy: 1)
        XCTAssertEqual(heroFrame.height, 218, accuracy: 2)
        let categoryFrame = app.staticTexts["storage.detail.category"].frame
        XCTAssertEqual(categoryFrame.minX, 32, accuracy: 1)
        XCTAssertEqual(categoryFrame.minY, 116, accuracy: 1)

        favorite.tap()
        XCTAssertTrue(app.buttons["storage.detail.favorite.item-lamp"].exists)
        XCTAssertEqual(
            app.buttons["storage.detail.favorite.item-lamp"].label,
            "즐겨찾기 해제"
        )
        app.buttons["storage.detail.favorite.item-lamp"].tap()
        XCTAssertEqual(
            app.buttons["storage.detail.favorite.item-lamp"].label,
            "즐겨찾기"
        )
        XCTAssertEqual(app.staticTexts["storage.detail.status"].label, "보관 중")
        XCTAssertEqual(
            app.staticTexts["storage.detail.context.title"].label,
            "내 방 벽면에 걸기"
        )
        XCTAssertEqual(app.staticTexts["storage.detail.category"].label, "장식")
        attachScreenshot(named: "storage-detail-402x874")
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

    func testWarehouseGridDetailAndPlacementUseFigmaSurface() {
        let app = inventoryApp()
        app.launch()
        openStorage(in: app)

        let chair = app.buttons["storage.row.item-chair"]
        XCTAssertTrue(chair.waitForExistence(timeout: 5))
        chair.tap()
        let detail = app.scrollViews["storage.detail.item-chair"]
        XCTAssertTrue(detail.waitForExistence(timeout: 5))

        let apply = app.buttons["storage.detail.apply.item-chair"]
        scrollToHittable(apply, in: detail)
        apply.tap()
        XCTAssertEqual(app.staticTexts["storage.detail.status"].label, "적용 중")
        XCTAssertTrue(app.buttons["storage.detail.remove.item-chair"].exists)
    }

    func testReduceMotionWarehouseEvidence() {
        let app = inventoryApp()
        app.launchEnvironment["QA_REDUCE_MOTION"] = "1"
        app.launch()
        openStorage(in: app)

        XCTAssertTrue(app.otherElements["app.shell.reduce-motion"].exists)
        XCTAssertEqual(app.staticTexts["storage.count"].label, "보유 아이템 12개")
        XCTAssertTrue(app.buttons["storage.row.item-mini-shelf"].exists)
        attachScreenshot(named: "storage-warehouse-reduce-motion-light")
    }

    private var shopProductIDs: [String] {
        [
            "item-cozy-rug", "item-vintage-lamp", "item-green-wall",
            "item-succulent-pot", "item-christmas-tree", "item-autumn-frame"
        ]
    }

    private func waitForShopReady(in app: XCUIApplication) {
        XCTAssertTrue(
            app.scrollViews["storage.screen"].waitForExistence(timeout: 10)
        )
        let ready = app.descendants(matching: .any)
            .matching(identifier: "shop.ready")
            .firstMatch
        XCTAssertTrue(ready.waitForExistence(timeout: 10))
    }

    private func openBackgroundShop(in app: XCUIApplication) {
        waitForShopRows(["shop.row.item-green-wall"], in: app) {
            app.buttons["storage.category.background"].tap()
        }
    }

    private func openWarehouse(in app: XCUIApplication) {
        let title = app.staticTexts["storage.title"]
        waitForElement(title) {
            app.buttons["storage.mode.warehouse"].tap()
        }
    }
}
