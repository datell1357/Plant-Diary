import XCTest

@MainActor
extension PlantCollectionFigmaUITests {
    /// The 도감 header and the plant rows share one padded content column. At
    /// AX5 an incompressible status pill made each row demand more width than
    /// the screen, so the whole column overflowed symmetrically: every element
    /// - the header included - rendered past BOTH screen edges, clipping the
    /// leading 나 and slicing the trailing search affordance.
    ///
    /// Anchoring the title against a row keeps this honest: they share the
    /// same container inset, so the row is the live definition of "inside the
    /// content column" without pinning a pixel or a font metric.
    func assertFirstRowNameUsesCompleteAX5Frame(
        in app: XCUIApplication,
        status: XCUIElement
    ) {
        let firstName = app.staticTexts["collection.name.0"]
        let species = app.staticTexts["collection.species.0"]
        XCTAssertTrue(firstName.exists)
        XCTAssertEqual(firstName.label, "몬몬이 (몬스테라)")
        XCTAssertTrue(
            species.exists,
            "the parenthetical species needs its own atomic rendered line"
        )
        XCTAssertEqual(species.label, "(몬스테라)")
        let completeNameFrame = firstName.frame.union(species.frame)
        XCTAssertGreaterThan(
            completeNameFrame.height,
            status.frame.height,
            "the complete AX5 plant name must use its multiline visual frame"
        )
        XCTAssertLessThanOrEqual(firstName.frame.maxY, species.frame.minY)
        XCTAssertGreaterThan(
            species.frame.width,
            species.frame.height,
            "몬스테라 must stay on one readable line at narrow AX widths"
        )
        attachAXHierarchy(
            named: "collection-ax5-first-name",
            elements: [
                ("nickname", firstName),
                ("species", species),
                ("status", status)
            ]
        )
    }

    func assertTitleStaysInsideContentColumn(in app: XCUIApplication) {
        let title = app.staticTexts["collection.title"]
        let row = app.buttons["collection.row.0"]
        let action = app.buttons["collection.search.action"]
        XCTAssertTrue(title.exists)
        XCTAssertTrue(row.exists)

        // Rows scroll vertically, so only HORIZONTAL containment is the
        // invariant here: nothing in the column may cross a screen edge.
        let screen = app.windows.element(boundBy: 0).frame
        let tolerance = 0.5
        for (name, element) in [("collection.title", title), ("collection.row.0", row)] {
            XCTAssertGreaterThanOrEqual(
                element.frame.minX,
                screen.minX - tolerance,
                "\(name) crosses the leading screen edge at AX5, "
                    + "was \(element.frame)"
            )
            XCTAssertLessThanOrEqual(
                element.frame.maxX,
                screen.maxX + tolerance,
                "\(name) crosses the trailing screen edge at AX5, "
                    + "was \(element.frame)"
            )
        }
        XCTAssertGreaterThanOrEqual(
            title.frame.minX,
            row.frame.minX - tolerance,
            "collection.title overflows the leading content inset at AX5"
        )
        XCTAssertLessThanOrEqual(
            title.frame.maxX,
            row.frame.maxX + tolerance,
            "collection.title overflows the trailing content inset at AX5"
        )
        XCTAssertFalse(
            title.frame.intersects(action.frame),
            "collection.title must not collide with the search affordance"
        )
    }

    func assertPlantDetailSpeciesAccessibility(in app: XCUIApplication) {
        let species = app.staticTexts["plant.detail.species"]
        XCTAssertTrue(species.exists)
        XCTAssertEqual(species.label, "등록한 반려식물")
        XCTAssertFalse(species.label.unicodeScalars.contains("\u{2060}"))
        scrollToHittable(species, in: app.scrollViews["plant.detail.screen"])
        attachScreenshot(named: "collection-detail-species-korean-ax5")
    }

    func assertPlantDetailChromeAndHeroMatchReference(in app: XCUIApplication) {
        let navigationBar = app.otherElements["plant.detail.top-bar"]
        let back = app.buttons["plant.detail.back"]
        let title = app.staticTexts["plant.detail.navigation-title"]
        let edit = app.buttons["plant.detail.edit"]
        let hero = app.images["plant.detail.hero"]
        let topOffset: CGFloat = app.frame.height == 874 ? 0 : -15
        XCTAssertTrue(back.exists)
        XCTAssertTrue(title.exists)
        XCTAssertTrue(edit.exists)
        XCTAssertEqual(navigationBar.frame.minY, 44 + topOffset, accuracy: 1)
        XCTAssertEqual(navigationBar.frame.height, 56, accuracy: 1)
        XCTAssertEqual(back.frame.minY, 50 + topOffset, accuracy: 2)
        XCTAssertGreaterThanOrEqual(back.frame.width, 44)
        XCTAssertGreaterThanOrEqual(back.frame.height, 44)
        XCTAssertEqual(title.frame.midX, navigationBar.frame.midX, accuracy: 1)
        XCTAssertEqual(edit.frame.minY, 50 + topOffset, accuracy: 2)
        XCTAssertGreaterThanOrEqual(edit.frame.width, 44)
        XCTAssertGreaterThanOrEqual(edit.frame.height, 44)
        let heroAccessibilityMinX: CGFloat = app.frame.width == 402 ? 16 : 10
        XCTAssertEqual(hero.frame.minX, heroAccessibilityMinX, accuracy: 1)
        XCTAssertEqual(hero.frame.minY, 108 + topOffset, accuracy: 2)
        XCTAssertEqual(hero.frame.width, 370, accuracy: 1)
        XCTAssertEqual(hero.frame.height, 220, accuracy: 1)
    }

    func assertRemedyChromeAndExpandedCardMatchReference(in app: XCUIApplication) {
        let navigationBar = app.otherElements["remedy.top-bar"]
        let back = app.buttons["remedy.back"]
        let title = app.staticTexts["remedy.navigation-title"]
        let card = app.otherElements["remedy.card.0"]
        let topOffset: CGFloat = app.frame.height == 874 ? 0 : -15
        XCTAssertTrue(back.exists)
        XCTAssertTrue(title.exists)
        XCTAssertTrue(card.exists)
        XCTAssertEqual(navigationBar.frame.minY, 44 + topOffset, accuracy: 1)
        XCTAssertEqual(navigationBar.frame.height, 56, accuracy: 1)
        XCTAssertEqual(back.frame.minY, 50 + topOffset, accuracy: 2)
        XCTAssertGreaterThanOrEqual(back.frame.width, 44)
        XCTAssertGreaterThanOrEqual(back.frame.height, 44)
        XCTAssertGreaterThanOrEqual(title.frame.minX, back.frame.maxX + 15)
        XCTAssertLessThan(title.frame.midX, navigationBar.frame.midX)
        XCTAssertEqual(card.frame.minX, 16, accuracy: 1)
        XCTAssertEqual(card.frame.minY, 148 + topOffset, accuracy: 4)
        XCTAssertEqual(card.frame.width, app.frame.width - 32, accuracy: 1)
        XCTAssertEqual(card.frame.height, 200, accuracy: 1)
    }
}
