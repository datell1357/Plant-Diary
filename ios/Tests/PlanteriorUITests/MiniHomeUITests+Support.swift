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

    func figmaEditorApp(token: String) -> XCUIApplication {
        let app = miniHomeApp()
        app.launchEnvironment["QA_MINIHOME_RESET_TOKEN"] =
            "\(token)-\(UUID())"
        return app
    }

    func openFigmaEditor(in app: XCUIApplication) {
        openEditor(in: app)
        XCTAssertTrue(
            app.staticTexts["minihome.editor.title"]
                .waitForExistence(timeout: 5)
        )
    }

    func openEditor(in app: XCUIApplication) {
        XCTAssertTrue(
            app.scrollViews["minihome.screen"]
                .waitForExistence(timeout: 10)
        )
        let edit = app.buttons["minihome.edit"]
        XCTAssertTrue(edit.waitForExistence(timeout: 5))
        assertSinglePersistentTabBar(in: app, selected: "tab.home")
        edit.tap()
        XCTAssertTrue(
            app.descendants(matching: .any)["minihome.editor"]
                .waitForExistence(timeout: 5)
        )
    }

    /// Committed-only projection contract on the current live surfaces.
    /// Home and MiniHome must both render the same committed `MiniHome.name`;
    /// a rename sidecar or owner-derived fallback cannot mask the saved model.
    func waitForCommittedRoom(
        named name: String,
        in app: XCUIApplication
    ) {
        XCTAssertTrue(
            app.buttons["home.room.decorate"].waitForExistence(timeout: 10),
            "Home must keep projecting the committed room controls"
        )
        let homeTitle = app.buttons["home.room.title"]
        XCTAssertTrue(homeTitle.waitForExistence(timeout: 10))
        let projected = XCTNSPredicateExpectation(
            predicate: NSPredicate(format: "label == %@", "\(name) 🏡"),
            object: homeTitle
        )
        XCTAssertEqual(
            XCTWaiter.wait(for: [projected], timeout: 10),
            .completed,
            "Home title must project committed MiniHome.name: \(homeTitle.label)"
        )
        app.buttons["home.room.decorate"].tap()
        let committedName = app.staticTexts["minihome.committed.name"]
        XCTAssertTrue(committedName.waitForExistence(timeout: 10))
        let committed = XCTNSPredicateExpectation(
            predicate: NSPredicate(format: "label == %@", name),
            object: committedName
        )
        XCTAssertEqual(
            XCTWaiter.wait(for: [committed], timeout: 10),
            .completed,
            "committed room name on MiniHome: \(committedName.label)"
        )
    }

    func replaceRoomName(
        with value: String,
        in app: XCUIApplication
    ) {
        let field = app.textFields["minihome.room-name"]
        if !field.exists {
            app.staticTexts["minihome.editor.title"].tap()
        }
        XCTAssertTrue(field.waitForExistence(timeout: 5))
        field.tap()
        field.typeText(
            String(
                repeating: XCUIKeyboardKey.delete.rawValue,
                count: 20
            )
        )
        field.typeText(value + "\n")
        let done = app.navigationBars.buttons["완료"]
        XCTAssertTrue(done.waitForExistence(timeout: 5))
        done.tap()
        XCTAssertTrue(field.waitForNonExistence(timeout: 5))
    }

    func dragPlantToEdgeAndAttachGeometry(
        in app: XCUIApplication
    ) {
        let addPlant = app.buttons["minihome.add-plant"]
        if !addPlant.exists {
            app.staticTexts["minihome.editor.title"].tap()
        }
        XCTAssertTrue(addPlant.waitForExistence(timeout: 5))
        addPlant.tap()
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
        app.buttons["minihome.save"].tap()
        app.staticTexts["minihome.editor.title"].tap()
        let state = app.staticTexts["minihome.state"]
        XCTAssertTrue(state.waitForExistence(timeout: 5))
        let saved = XCTNSPredicateExpectation(
            predicate: NSPredicate(format: "label == '저장 완료'"),
            object: state
        )
        XCTAssertEqual(
            XCTWaiter.wait(for: [saved], timeout: 5),
            .completed,
            "MiniHome state: \(state.label)"
        )
        attachScreenshot(named: "task-14-room")
        let done = app.navigationBars.buttons["완료"]
        XCTAssertTrue(done.waitForExistence(timeout: 5))
        done.tap()
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
}
