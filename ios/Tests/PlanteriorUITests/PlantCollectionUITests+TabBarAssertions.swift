import XCTest

@MainActor
extension PlantCollectionFigmaUITests {
    func assertCollectionTabBarContract(
        in app: XCUIApplication,
        add: XCUIElement
    ) {
        assertCollectionPersistentTabBar(in: app, controls: [add])
        XCTAssertGreaterThanOrEqual(add.frame.width, 44)
        XCTAssertGreaterThanOrEqual(add.frame.height, 44)
    }

    @discardableResult
    func assertCollectionPersistentTabBar(
        in app: XCUIApplication,
        controls: [XCUIElement]
    ) -> XCUIElement {
        assertSinglePersistentTabBar(in: app, selected: "tab.collection")
        let collectionTab = app.buttons["tab.collection"]

        // Machine-consumed mirrors of the 62pt bar and 34pt native safe area.
        let materialMinY = app.frame.maxY - 62 - 34
        if app.frame.height == 874 {
            XCTAssertEqual(materialMinY, 778, accuracy: 0.5)
        }
        for control in controls {
            XCTAssertTrue(control.exists)
            XCTAssertLessThanOrEqual(control.frame.maxY, materialMinY)
        }
        return collectionTab
    }

    func returnFromCollectionDetail(
        in app: XCUIApplication,
        add: XCUIElement
    ) {
        app.buttons["plant.detail.back"].tap()
        XCTAssertTrue(
            app.scrollViews["collection.screen"].waitForExistence(timeout: 5)
        )
        XCTAssertTrue(app.buttons["tab.collection"].isSelected)
        XCTAssertTrue(add.exists)
    }
}
