import XCTest

@MainActor
extension InventoryUITests {
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
        assertLampChrome(back: back, title: chromeTitle, favorite: favorite)
        assertSinglePersistentTabBar(in: app, selected: "tab.storage")
        assertLampBarGeometry(in: app, back: back, favorite: favorite)
        assertLampGeometry(in: app)
        assertLampFavoriteState(in: app, favorite: favorite)
        attachScreenshot(named: "storage-detail-402x874")

        back.tap()
        XCTAssertTrue(
            app.scrollViews["storage.screen"].waitForExistence(timeout: 5)
        )
        assertSinglePersistentTabBar(in: app, selected: "tab.storage")
    }

    private func assertLampChrome(
        back: XCUIElement,
        title: XCUIElement,
        favorite: XCUIElement
    ) {
        XCTAssertTrue(back.isHittable)
        XCTAssertEqual(back.frame.width, 44, accuracy: 1)
        XCTAssertEqual(back.frame.height, 44, accuracy: 1)
        XCTAssertEqual(title.frame.minX, back.frame.maxX, accuracy: 2)
        XCTAssertLessThan(title.frame.maxX, favorite.frame.minX)
        XCTAssertEqual(favorite.frame.width, 44, accuracy: 1)
        XCTAssertEqual(favorite.frame.height, 44, accuracy: 1)
        XCTAssertTrue(favorite.isHittable)
    }

    private func assertLampBarGeometry(
        in app: XCUIApplication,
        back: XCUIElement,
        favorite: XCUIElement
    ) {
        // Machine-consumed mirrors of the 62pt bar and 34pt native safe area.
        let materialMinY = app.frame.maxY - 62 - 34
        XCTAssertGreaterThanOrEqual(
            app.buttons["tab.storage"].frame.minY,
            materialMinY
        )
        for control in [
            back,
            favorite,
            app.buttons["storage.detail.apply.item-lamp"]
        ] {
            XCTAssertTrue(control.exists)
            XCTAssertLessThanOrEqual(control.frame.maxY, materialMinY)
        }
    }

    private func assertLampGeometry(in app: XCUIApplication) {
        let heroFrame = app.images["storage.detail.hero.item-lamp"].frame
        XCTAssertEqual(heroFrame.minX, (app.frame.width - 362) / 2, accuracy: 1)
        XCTAssertEqual(heroFrame.minY, 101, accuracy: 1)
        XCTAssertEqual(heroFrame.width, 362, accuracy: 1)
        XCTAssertEqual(heroFrame.height, 218, accuracy: 2)
        let categoryFrame = app.staticTexts["storage.detail.category"].frame
        XCTAssertEqual(categoryFrame.minX, heroFrame.minX + 12, accuracy: 1)
        XCTAssertEqual(categoryFrame.minY, 116, accuracy: 1)
        XCTAssertEqual(
            app.staticTexts["storage.detail.title"].frame.minY,
            340,
            accuracy: 1
        )
        XCTAssertEqual(
            app.buttons["storage.detail.apply.item-lamp"].frame.minY,
            509,
            accuracy: 1
        )
        XCTAssertEqual(
            app.staticTexts["storage.detail.context.title"].frame.minY,
            621,
            accuracy: 1
        )
    }

    private func assertLampFavoriteState(
        in app: XCUIApplication,
        favorite: XCUIElement
    ) {
        XCTAssertEqual(favorite.label, "즐겨찾기")
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
    }
}
