import XCTest

@MainActor
extension PlantCollectionFigmaUITests {
    func figmaCollectionApp(empty: Bool = false) -> XCUIApplication {
        let app = XCUIApplication()
        app.launchEnvironment["QA_SKIP_ONBOARDING"] = "1"
        app.launchEnvironment["QA_AUTHENTICATED"] = "1"
        app.launchEnvironment["QA_INITIAL_TAB"] = "collection"
        app.launchEnvironment["QA_COLLECTION_FIXTURE"] = "1"
        app.launchEnvironment["QA_COLLECTION_FIGMA_FIXTURE"] = "1"
        app.launchEnvironment["QA_RESET_COLLECTION"] = "1"
        app.launchEnvironment["QA_WATERING_TODAY"] = "2026-05-19"
        app.launchEnvironment["QA_PLANT_DETAIL_UPDATED_ON"] = "2026-05-17"
        app.launchArguments += [
            "-UIPreferredContentSizeCategoryName",
            "UICTContentSizeCategoryM"
        ]
        if empty {
            app.launchEnvironment["QA_COLLECTION_EMPTY"] = "1"
        }
        return app
    }

    func attachScreenshot(named name: String) {
        let attachment = XCTAttachment(screenshot: XCUIScreen.main.screenshot())
        attachment.name = name
        attachment.lifetime = .keepAlways
        add(attachment)
    }

    func assertCollectionHeadingMatchesReference(in app: XCUIApplication) {
        let heading = app.staticTexts["collection.title"]
        XCTAssertTrue(heading.exists)
        XCTAssertEqual(heading.frame.minX, 24, accuracy: 1)
        XCTAssertEqual(heading.frame.minY, 64, accuracy: 1)
    }

    func assertPlantDetailChromeAndHeroMatchReference(in app: XCUIApplication) {
        let navigationBar = app.otherElements["plant.detail.top-bar"]
        let back = app.buttons["plant.detail.back"]
        let edit = app.buttons["plant.detail.edit"]
        let hero = app.images["plant.detail.hero"]
        XCTAssertTrue(back.exists)
        XCTAssertTrue(edit.exists)
        XCTAssertEqual(navigationBar.frame.minY, 44, accuracy: 1)
        XCTAssertEqual(navigationBar.frame.height, 56, accuracy: 1)
        XCTAssertEqual(back.frame.minY, 50, accuracy: 2)
        XCTAssertGreaterThanOrEqual(back.frame.width, 44)
        XCTAssertGreaterThanOrEqual(back.frame.height, 44)
        XCTAssertEqual(edit.frame.minY, 50, accuracy: 2)
        XCTAssertGreaterThanOrEqual(edit.frame.width, 44)
        XCTAssertGreaterThanOrEqual(edit.frame.height, 44)
        XCTAssertEqual(hero.frame.minX, 16, accuracy: 1)
        XCTAssertEqual(hero.frame.minY, 108, accuracy: 2)
        XCTAssertEqual(hero.frame.width, 370, accuracy: 1)
        XCTAssertEqual(hero.frame.height, 220, accuracy: 1)
    }

    func assertRemedyChromeAndExpandedCardMatchReference(in app: XCUIApplication) {
        let navigationBar = app.otherElements["remedy.top-bar"]
        let back = app.buttons["remedy.back"]
        let card = app.otherElements["remedy.card.0"]
        XCTAssertTrue(back.exists)
        XCTAssertTrue(card.exists)
        XCTAssertEqual(navigationBar.frame.minY, 44, accuracy: 1)
        XCTAssertEqual(navigationBar.frame.height, 56, accuracy: 1)
        XCTAssertEqual(back.frame.minY, 50, accuracy: 2)
        XCTAssertGreaterThanOrEqual(back.frame.width, 44)
        XCTAssertGreaterThanOrEqual(back.frame.height, 44)
        XCTAssertEqual(card.frame.minX, 16, accuracy: 1)
        XCTAssertEqual(card.frame.minY, 148, accuracy: 4)
        XCTAssertEqual(card.frame.width, 370, accuracy: 1)
        XCTAssertEqual(card.frame.height, 200, accuracy: 1)
    }

    /// The 도감 header and the plant rows share one padded content column. At
    /// AX5 an incompressible status pill made each row demand more width than
    /// the screen, so the whole column overflowed symmetrically: every element
    /// - the header included - rendered past BOTH screen edges, clipping the
    /// leading 나 and slicing the trailing search affordance.
    ///
    /// Anchoring the title against a row keeps this honest: they share the
    /// same container inset, so the row is the live definition of "inside the
    /// content column" without pinning a pixel or a font metric.
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
}
