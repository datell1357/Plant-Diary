import XCTest

@MainActor
extension PlantCollectionFigmaUITests {
    func figmaCollectionApp(
        empty: Bool = false,
        testID: String = #function
    ) -> XCUIApplication {
        let app = XCUIApplication()
        let identity = figmaCollectionIdentity(testID: testID, empty: empty)
        configureCollectionQAFixture(app, identity: identity, mode: .figma)
        app.launchEnvironment["QA_WATERING_TODAY"] = "2026-05-19"
        app.launchEnvironment["QA_PLANT_DETAIL_UPDATED_ON"] = "2026-05-17"
        if empty {
            app.launchEnvironment["QA_COLLECTION_EMPTY"] = "1"
        }
        return app
    }

    func waitForFigmaCollectionFixture(
        in app: XCUIApplication,
        empty: Bool = false,
        testID: String = #function
    ) {
        waitForCollectionQAFixture(
            in: app,
            identity: figmaCollectionIdentity(testID: testID, empty: empty)
        )
    }

    private func figmaCollectionIdentity(
        testID: String,
        empty: Bool
    ) -> CollectionQAFixtureIdentity {
        CollectionQAFixtureIdentity(
            testID: testID,
            variant: empty ? "empty" : "content",
            mode: .figma,
            empty: empty
        )
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
            ("disclaimer", app.staticTexts["remedy.disclaimer"]),
            ("card.0", app.otherElements["remedy.card.0"]),
            ("card.1", app.otherElements["remedy.card.1"]),
            ("card.2", app.otherElements["remedy.card.2"])
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
        let topOffset: CGFloat = app.frame.height == 874 ? 0 : -15
        XCTAssertTrue(heading.exists)
        XCTAssertEqual(heading.frame.minX, 24, accuracy: 1)
        XCTAssertEqual(heading.frame.minY, 64 + topOffset, accuracy: 1)
    }

    func assertCollectionAddActionMatchesReference(in app: XCUIApplication) {
        let add = app.buttons["collection.add"]
        let collectionTab = app.buttons["tab.collection"]
        let verticalOffset = app.frame.height - 874
        XCTAssertTrue(add.exists)
        assertCollectionTabBarContract(in: app, add: add)
        XCTAssertEqual(add.frame.minX, app.frame.width - 76, accuracy: 0.5)
        XCTAssertEqual(add.frame.minY, 644 + verticalOffset, accuracy: 0.5)
        XCTAssertEqual(add.frame.width, 56, accuracy: 0.5)
        XCTAssertEqual(add.frame.height, 56, accuracy: 0.5)
        XCTAssertEqual(add.frame.midY, 672 + verticalOffset, accuracy: 0.5)
        XCTAssertFalse(add.frame.intersects(collectionTab.frame))
        XCTAssertGreaterThan(add.frame.midY, app.buttons["collection.row.3"].frame.midY)
    }

    func assertCollectionListGeometryMatchesReference(in app: XCUIApplication) {
        let summary = app.buttons["collection.open-detail"]
        let topOffset: CGFloat = app.frame.height == 874 ? 0 : -15
        let contentWidth = app.frame.width - 32
        XCTAssertEqual(summary.frame.minX, 16, accuracy: 0.5)
        XCTAssertEqual(summary.frame.minY, 116 + topOffset, accuracy: 0.5)
        XCTAssertEqual(summary.frame.width, contentWidth, accuracy: 0.5)
        XCTAssertEqual(summary.frame.height, 80, accuracy: 0.5)

        let referenceRowMinY: [CGFloat] = [212, 310, 408, 506, 604]
        for (index, minY) in referenceRowMinY.enumerated() {
            let row = app.buttons["collection.row.\(index)"]
            XCTAssertEqual(row.frame.minX, 16, accuracy: 0.5)
            XCTAssertEqual(row.frame.minY, minY + topOffset, accuracy: 0.5)
            XCTAssertEqual(row.frame.width, contentWidth, accuracy: 0.5)
            XCTAssertEqual(row.frame.height, 88, accuracy: 0.5)
        }
    }

    func assertCollectionEmptyGeometryMatchesReference(in app: XCUIApplication) {
        let illustration = app.images["collection.empty.illustration"]
        let title = app.staticTexts["collection.empty.title"]
        let body = app.staticTexts["collection.empty.body"]
        let camera = app.buttons["collection.empty.camera"]
        let manual = app.buttons["collection.empty.manual"]
        let topOffset: CGFloat = app.frame.height == 874 ? 0 : -15
        XCTAssertEqual(
            illustration.frame.minX,
            (app.frame.width - 180) / 2,
            accuracy: 0.5
        )
        XCTAssertEqual(illustration.frame.minY, 156 + topOffset, accuracy: 0.5)
        XCTAssertEqual(illustration.frame.width, 180, accuracy: 0.5)
        XCTAssertEqual(illustration.frame.height, 180, accuracy: 0.5)
        XCTAssertEqual(title.frame.minY, 364 + topOffset, accuracy: 0.5)
        XCTAssertEqual(body.frame.minY, 392.333 + topOffset, accuracy: 0.5)
        XCTAssertEqual(camera.frame.minX, 24, accuracy: 0.5)
        XCTAssertEqual(camera.frame.minY, 438.333 + topOffset, accuracy: 0.5)
        XCTAssertEqual(camera.frame.width, app.frame.width - 48, accuracy: 0.5)
        XCTAssertEqual(camera.frame.height, 46, accuracy: 0.5)
        XCTAssertEqual(manual.frame.minX, 24, accuracy: 0.5)
        XCTAssertEqual(manual.frame.minY, 496.333 + topOffset, accuracy: 0.5)
        XCTAssertEqual(manual.frame.width, app.frame.width - 48, accuracy: 0.5)
        XCTAssertEqual(manual.frame.height, 44, accuracy: 0.5)
    }
}
