import XCTest

protocol MiniHomeUITestSupport {}

@MainActor
extension MiniHomeUITestSupport where Self: XCTestCase {
    func miniHomeApp() -> XCUIApplication {
        let app = XCUIApplication()
        app.launchEnvironment["QA_SKIP_ONBOARDING"] = "1"
        app.launchEnvironment["QA_AUTHENTICATED"] = "1"
        app.launchEnvironment["QA_HOME_FIXTURE"] = "1"
        app.launchEnvironment["QA_COLLECTION_FIXTURE"] = "1"
        app.launchEnvironment["QA_MINIHOME_ROUTE"] = "1"
        app.launchEnvironment["QA_MINIHOME_NOW"] = "2026-08-11T02:00:00Z"
        app.launchEnvironment["QA_WEATHER_AUTHORIZATION"] = "denied"
        app.launchEnvironment["QA_NOTIFICATION_AUTHORIZATION"] = "authorized"
        app.launchEnvironment["QA_NOTIFICATION_ENDPOINT"] = "registered"
        app.launchEnvironment["QA_WATERING_TODAY"] = "2026-08-11"
        return app
    }

    func openEditor(in app: XCUIApplication) {
        XCTAssertTrue(
            app.scrollViews["minihome.screen"]
                .waitForExistence(timeout: 10)
        )
        let edit = app.buttons["minihome.edit"]
        XCTAssertTrue(edit.waitForExistence(timeout: 5))
        edit.tap()
        XCTAssertTrue(
            app.scrollViews["minihome.editor"]
                .waitForExistence(timeout: 5)
        )
    }

    func waitForCommittedRoom(
        named name: String,
        in app: XCUIApplication
    ) {
        let label = app.staticTexts["home.minhome.label"]
        XCTAssertTrue(label.waitForExistence(timeout: 5))
        let committed = XCTNSPredicateExpectation(
            predicate: NSPredicate(
                format: "label == %@",
                "\(name) · 저장됨"
            ),
            object: label
        )
        XCTAssertEqual(
            XCTWaiter.wait(for: [committed], timeout: 10),
            .completed
        )
    }

    func replaceRoomName(
        with value: String,
        in app: XCUIApplication
    ) {
        let field = app.textFields["minihome.room-name"]
        field.tap()
        field.typeText(
            String(
                repeating: XCUIKeyboardKey.delete.rawValue,
                count: 20
            )
        )
        field.typeText(value + "\n")
    }

    func dragPlantToEdgeAndAttachGeometry(
        in app: XCUIApplication
    ) {
        app.buttons["minihome.add-plant"].tap()
        let firstPlant = app.buttons.matching(
            NSPredicate(
                format: "identifier BEGINSWITH 'minihome.plant.'"
            )
        ).firstMatch
        XCTAssertTrue(firstPlant.waitForExistence(timeout: 5))
        firstPlant.tap()
        let placement = app.images["minihome.placement.placement-1"]
        XCTAssertTrue(placement.waitForExistence(timeout: 5))
        let canvas = app.otherElements["minihome.editor.canvas"]
        placement.coordinate(
            withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5)
        ).press(
            forDuration: 0.1,
            thenDragTo: canvas.coordinate(
                withNormalizedOffset: CGVector(dx: 0.98, dy: 0.98)
            )
        )
        attachJSON(
            [
                "placementID": "placement-1",
                "observedAccessibilityLabel": placement.label,
                "dragTargetNormalized": [0.98, 0.98],
                "boundsAssertion": "MiniHomeGeometryTests",
                "stableOrder": ["placement-1"]
            ],
            named: "task-14-mini-home-geometry"
        )
    }

    func saveAndAttachRoom(in app: XCUIApplication) {
        let state = app.staticTexts["minihome.state"]
        XCTAssertTrue(state.waitForExistence(timeout: 5))
        let saved = XCTNSPredicateExpectation(
            predicate: NSPredicate(format: "label == '저장 완료'"),
            object: state
        )
        app.buttons["minihome.save"].tap()
        XCTAssertEqual(
            XCTWaiter.wait(for: [saved], timeout: 5),
            .completed,
            "MiniHome state: \(state.label)"
        )
        attachScreenshot(named: "task-14-room")
    }

    func waitForMiniHomeState(
        _ expected: String,
        in app: XCUIApplication
    ) {
        let state = app.staticTexts["minihome.state"]
        XCTAssertTrue(state.waitForExistence(timeout: 5))
        let changed = XCTNSPredicateExpectation(
            predicate: NSPredicate(format: "label == %@", expected),
            object: state
        )
        XCTAssertEqual(
            XCTWaiter.wait(for: [changed], timeout: 5),
            .completed
        )
    }

    func dismissConfirmationPopover(in app: XCUIApplication) {
        let dismissRegion = app.otherElements["PopoverDismissRegion"]
        XCTAssertTrue(dismissRegion.waitForExistence(timeout: 5))
        dismissRegion.tap()
        XCTAssertTrue(dismissRegion.waitForNonExistence(timeout: 5))
    }

    func attachScreenshot(named name: String) {
        let attachment = XCTAttachment(
            screenshot: XCUIScreen.main.screenshot()
        )
        attachment.name = name
        attachment.lifetime = .keepAlways
        add(attachment)
    }

    func attachJSON(_ value: Any, named name: String) {
        guard let data = try? JSONSerialization.data(
            withJSONObject: value,
            options: [.prettyPrinted, .sortedKeys]
        ) else {
            XCTFail("JSON evidence could not be encoded")
            return
        }
        let attachment = XCTAttachment(
            data: data,
            uniformTypeIdentifier: "public.json"
        )
        attachment.name = name
        attachment.lifetime = .keepAlways
        add(attachment)
    }
}
