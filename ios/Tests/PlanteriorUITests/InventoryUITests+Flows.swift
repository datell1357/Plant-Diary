import XCTest

struct InventoryPlacementEvidence {
    let applied: String
    let removed: String
    let ownedRow: String
    let applyControl: String
}

extension InventoryUITests {
    func acquireWall(
        in app: XCUIApplication
    ) -> (failure: String, success: String) {
        waitForShopRows(["shop.row.item-green-wall"], in: app) {
            app.buttons["storage.category.background"].tap()
        }
        let acquire = app.buttons["shop.acquire.item-green-wall"]
        waitForHittable(acquire)
        let failure = triggerAndReadMessage(in: app, previous: nil) {
            acquire.tap()
        }
        let retry = app.buttons["shop.acquire.item-green-wall"]
        XCTAssertTrue(retry.isEnabled)
        let success = triggerAndReadMessage(
            in: app,
            previous: failure
        ) {
            retry.tap()
        }
        XCTAssertFalse(
            app.buttons["shop.acquire.item-green-wall"].isEnabled
        )
        return (failure, success)
    }

    func applyAndRemoveWall(
        in app: XCUIApplication,
        previousFeedback: String
    ) -> InventoryPlacementEvidence {
        let ownedRow = app.buttons["storage.row.item-green-wall"]
        waitForElement(ownedRow) {
            app.buttons["storage.mode.warehouse"].tap()
        }
        let detail = app.scrollViews["storage.detail.item-green-wall"]
        waitForElement(detail) { ownedRow.tap() }

        let apply = app.buttons["storage.detail.apply.item-green-wall"]
        waitForHittable(apply)
        let applied = triggerAndReadMessage(
            in: app,
            previous: previousFeedback
        ) {
            apply.tap()
        }
        let remove = app.buttons["storage.detail.remove.item-green-wall"]
        waitForElement(remove) {}
        let removed = triggerAndReadMessage(
            in: app,
            previous: applied
        ) {
            remove.tap()
        }
        waitForElement(apply) {}

        let back = app.navigationBars["아이템 상세"].buttons.firstMatch
        waitForHittable(back)
        back.tap()
        XCTAssertTrue(ownedRow.waitForExistence(timeout: 5))
        return InventoryPlacementEvidence(
            applied: applied,
            removed: removed,
            ownedRow: ownedRow.identifier,
            applyControl: "storage.detail.apply.item-green-wall"
        )
    }

    func attachCatalogEvidence(
        firstPage: [String],
        ascending: [String],
        descending: [String],
        condition: XCUIElement,
        conditionToken: String
    ) {
        attachJSON(
            [
                "pageOne": firstPage,
                "pageTwo": ascending,
                "sortAscending": ascending,
                "sortDescending": descending,
                "categoryFilter": "DECORATION",
                "filteredRows": ["shop.row.item-lamp"],
                "detail": "storage.detail.item-lamp",
                "condition": condition.label,
                "conditionToken": conditionToken
            ],
            named: "task-15-shop-pagination-filter-sort"
        )
    }
}
