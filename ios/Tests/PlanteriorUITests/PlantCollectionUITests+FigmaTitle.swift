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

    func attachListFrameReceipt(in app: XCUIApplication) {
        attachFrameReceipt(named: "collection-list-frames", elements: [
            ("title", app.staticTexts["collection.title"]),
            ("summary", app.buttons["collection.open-detail"]),
            ("row.0", app.buttons["collection.row.0"]),
            ("row.1", app.buttons["collection.row.1"]),
            ("row.2", app.buttons["collection.row.2"]),
            ("row.3", app.buttons["collection.row.3"]),
            ("row.4", app.buttons["collection.row.4"]),
            ("search", app.buttons["collection.search.action"]),
            ("add", app.buttons["collection.add"])
        ])
    }

    func attachEmptyFrameReceipt(in app: XCUIApplication) {
        attachFrameReceipt(named: "collection-empty-frames", elements: [
            ("title", app.staticTexts["collection.title"]),
            ("illustration", app.images["collection.empty.illustration"]),
            ("empty.title", app.staticTexts["collection.empty.title"]),
            ("empty.body", app.staticTexts["collection.empty.body"]),
            ("empty.camera", app.buttons["collection.empty.camera"]),
            ("empty.manual", app.buttons["collection.empty.manual"])
        ])
    }

    func attachDetailFrameReceipt(in app: XCUIApplication) {
        attachFrameReceipt(named: "collection-detail-frames", elements: [
            ("top-bar", app.otherElements["plant.detail.top-bar"]),
            ("back", app.buttons["plant.detail.back"]),
            ("edit", app.buttons["plant.detail.edit"]),
            ("hero", app.images["plant.detail.hero"]),
            ("guide", app.otherElements["plant.detail.guide"]),
            ("watering", app.otherElements["plant.detail.watering-card"]),
            ("memo", app.otherElements["plant.detail.memo"]),
            ("memo.card", app.otherElements["plant.detail.memo.card"]),
            ("memo.body", app.staticTexts["plant.detail.memo.body"]),
            ("memo.updated", app.staticTexts["plant.detail.memo-updated"])
        ])
    }

    func attachRemedyFrameReceipt(in app: XCUIApplication) {
        attachFrameReceipt(named: "collection-remedy-frames", elements: [
            ("top-bar", app.otherElements["remedy.top-bar"]),
            ("back", app.buttons["remedy.back"]),
            ("context", app.staticTexts["remedy.context"]),
            ("card.0", app.otherElements["remedy.card.0"]),
            ("card.1", app.otherElements["remedy.card.1"]),
            ("card.2", app.otherElements["remedy.card.2"]),
            ("card.3", app.otherElements["remedy.card.3"])
        ])
    }

    private func attachFrameReceipt(
        named name: String,
        elements: [(String, XCUIElement)]
    ) {
        let receipt = elements.map { elementName, element in
            "\(elementName)=\(element.frame)"
        }.joined(separator: "\n")
        print("FRAME_RECEIPT \(name)\n\(receipt)")
        let attachment = XCTAttachment(string: receipt)
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

    func assertCollectionListGeometryMatchesReference(in app: XCUIApplication) {
        let summary = app.buttons["collection.open-detail"]
        XCTAssertEqual(summary.frame.minX, 16, accuracy: 0.5)
        XCTAssertEqual(summary.frame.minY, 116, accuracy: 0.5)
        XCTAssertEqual(summary.frame.width, 370, accuracy: 0.5)
        XCTAssertEqual(summary.frame.height, 80, accuracy: 0.5)
    }

    func assertCollectionEmptyGeometryMatchesReference(in app: XCUIApplication) {
        let illustration = app.images["collection.empty.illustration"]
        let title = app.staticTexts["collection.empty.title"]
        let body = app.staticTexts["collection.empty.body"]
        let camera = app.buttons["collection.empty.camera"]
        let manual = app.buttons["collection.empty.manual"]
        XCTAssertEqual(illustration.frame.minX, 111, accuracy: 0.5)
        XCTAssertEqual(illustration.frame.minY, 156, accuracy: 0.5)
        XCTAssertEqual(illustration.frame.width, 180, accuracy: 0.5)
        XCTAssertEqual(illustration.frame.height, 180, accuracy: 0.5)
        XCTAssertEqual(title.frame.minY, 364, accuracy: 0.5)
        XCTAssertEqual(body.frame.minY, 392.333, accuracy: 0.5)
        XCTAssertEqual(camera.frame.minX, 24, accuracy: 0.5)
        XCTAssertEqual(camera.frame.minY, 438.333, accuracy: 0.5)
        XCTAssertEqual(camera.frame.width, 354, accuracy: 0.5)
        XCTAssertEqual(camera.frame.height, 46, accuracy: 0.5)
        XCTAssertEqual(manual.frame.minX, 24, accuracy: 0.5)
        XCTAssertEqual(manual.frame.minY, 496.333, accuracy: 0.5)
        XCTAssertEqual(manual.frame.width, 354, accuracy: 0.5)
        XCTAssertEqual(manual.frame.height, 44, accuracy: 0.5)
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
