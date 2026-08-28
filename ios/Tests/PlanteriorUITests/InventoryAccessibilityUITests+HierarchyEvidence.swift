import XCTest

@MainActor
extension InventoryAccessibilityUITests {
    func testWarehouseAX5PaintsCompleteFiltersAndCountBeforeCards() {
        let app = inventoryApp()
        app.launchEnvironment["QA_INVENTORY_SIZE_CATEGORY"] = "AX5"
        app.launchArguments += ["-AppleLanguages", "(ko)", "-AppleLocale", "ko_KR"]
        app.launch()
        openStorage(in: app)

        let filters = ["all", "background", "furniture", "decoration"].map {
            app.buttons["storage.category.\($0)"]
        }
        assertWarehouseAX5Filters(filters)

        let count = app.staticTexts["storage.count"]
        let cardIdentifiers = [
            "storage.row.item-mini-shelf",
            "storage.row.item-small-rug",
            "storage.row.item-window-frame"
        ]
        assertWarehouseAX5Cards(cardIdentifiers, count: count, in: app)
        assertAXTraversal(
            in: app,
            isExactly: [
                "storage.category.all",
                "storage.category.background",
                "storage.category.furniture",
                "storage.category.decoration",
                "storage.count"
            ] + cardIdentifiers
        )
        attachAXHierarchy(
            named: "inventory-ax5-order",
            elements: [("count", count), ("first-card", app.buttons[cardIdentifiers[0]])]
        )
    }

    private func assertWarehouseAX5Filters(_ filters: [XCUIElement]) {
        let filterWidthTolerance: CGFloat = 0.001
        for filter in filters {
            XCTAssertTrue(filter.exists)
            XCTAssertFalse(filter.label.contains("\u{2026}"))
            XCTAssertGreaterThanOrEqual(
                filter.frame.width,
                80 - filterWidthTolerance,
                "AX5 filter width must paint its complete Korean caption"
            )
        }
    }

    private func assertWarehouseAX5Cards(
        _ cardIdentifiers: [String],
        count: XCUIElement,
        in app: XCUIApplication
    ) {
        let cards = cardIdentifiers.map { app.buttons[$0] }
        let firstCard = cards[0]
        XCTAssertTrue(count.exists)
        for (identifier, card) in zip(cardIdentifiers, cards) {
            XCTAssertTrue(card.waitForExistence(timeout: 5))
            XCTAssertEqual(app.buttons.matching(identifier: identifier).count, 1)
        }
        XCTAssertGreaterThanOrEqual(count.frame.height, 44)
        XCTAssertFalse(count.label.contains("\u{2026}"))
        XCTAssertEqual(
            app.staticTexts.matching(identifier: "storage.count").count,
            1
        )
        XCTAssertLessThanOrEqual(
            count.frame.maxY + 10,
            firstCard.frame.minY,
            "AX5 count and first card need the reference grid spacing"
        )
        XCTAssertTrue(firstCard.isHittable)
    }
}
