import XCTest

@MainActor
extension InventoryUITests {
    func testWarehouseMatchesReferenceCatalogAndGrid() {
        let app = inventoryApp()
        app.launch()
        openStorage(in: app)

        assertWarehouseHeader(in: app)
        assertWarehouseRoomFilters(in: app)
        let ownedItems = [
            "item-mini-shelf", "item-small-rug", "item-window-frame",
            "item-flower-stand", "item-lamp", "item-wall-art",
            "item-chair", "item-cushion", "item-book-cart",
            "item-plant-rack", "item-round-mat", "item-cozy-rug"
        ]
        assertWarehouseVisibleItems(ownedItems, in: app)
        assertWarehouseGeometry(in: app)
        attachScreenshot(named: "storage-warehouse-402x874")
        assertWarehouseScrollableItems(ownedItems, in: app)
    }

    private func assertWarehouseRoomFilters(in app: XCUIApplication) {
        let filters = ["all", "wall", "floor", "furniture", "decoration"].map {
            app.buttons["storage.category.\($0)"]
        }
        XCTAssertEqual(filters.map(\.label), ["전체", "벽지", "바닥", "가구", "장식"])
        XCTAssertTrue(filters[0].isSelected)
        for filter in filters {
            XCTAssertEqual(filter.frame.width, filters[0].frame.width, accuracy: 1)
            XCTAssertGreaterThanOrEqual(filter.frame.minX, 16)
            XCTAssertLessThanOrEqual(filter.frame.maxX, app.frame.maxX - 16)
        }
        XCTAssertGreaterThanOrEqual(filters[0].frame.width, 56)
        for (leading, trailing) in zip(filters, filters.dropFirst()) {
            XCTAssertLessThanOrEqual(leading.frame.maxX, trailing.frame.minX)
        }

        let expectedItems = [
            ["item-mini-shelf", "item-small-rug", "item-window-frame",
             "item-flower-stand", "item-lamp", "item-wall-art", "item-chair",
             "item-cushion", "item-book-cart", "item-plant-rack",
             "item-round-mat", "item-cozy-rug"],
            ["item-window-frame", "item-wall-art"],
            ["item-small-rug", "item-round-mat", "item-cozy-rug"],
            ["item-mini-shelf", "item-flower-stand", "item-chair",
             "item-book-cart", "item-plant-rack"],
            ["item-lamp", "item-cushion"]
        ]
        for (filter, itemIDs) in zip(filters, expectedItems) {
            filter.tap()
            XCTAssertTrue(filter.isSelected)
            XCTAssertEqual(app.staticTexts["storage.count"].label, "보유 아이템 \(itemIDs.count)개")
            XCTAssertEqual(
                app.buttons.allElementsBoundByIndex
                    .map(\.identifier)
                    .filter { $0.hasPrefix("storage.row.") },
                itemIDs.prefix(8).map { "storage.row.\($0)" }
            )
        }

        filters[0].tap()
        XCTAssertTrue(filters[0].isSelected)
        XCTAssertEqual(app.staticTexts["storage.count"].label, "보유 아이템 12개")
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

    private func assertWarehouseHeader(in app: XCUIApplication) {
        XCTAssertEqual(app.staticTexts["storage.title"].label, "나의 창고")
        XCTAssertEqual(app.staticTexts["storage.count"].label, "보유 아이템 12개")
        XCTAssertFalse(app.buttons["storage.mode.warehouse"].exists)
    }

    private func assertWarehouseVisibleItems(
        _ ownedItems: [String],
        in app: XCUIApplication
    ) {
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
    }

    private func assertWarehouseGeometry(in app: XCUIApplication) {
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
        attachJSON(
            [
                "first": NSCoder.string(for: first),
                "eighth": NSCoder.string(for: eighth)
            ],
            named: "storage-warehouse-reference-frames"
        )
    }

    private func assertWarehouseScrollableItems(
        _ ownedItems: [String],
        in app: XCUIApplication
    ) {
        for itemID in ownedItems.dropFirst(8) {
            let row = app.buttons["storage.row.\(itemID)"]
            scrollToHittable(row, in: app.scrollViews["storage.screen"])
            XCTAssertTrue(
                row.isHittable,
                "all 12 owned items remain scroll-reachable"
            )
        }
    }
}
