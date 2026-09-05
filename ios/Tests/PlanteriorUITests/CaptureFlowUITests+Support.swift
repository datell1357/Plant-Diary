import XCTest

extension CaptureFlowUITests {
    func launchCapture(
        _ app: XCUIApplication,
        environment: [String: String] = [:]
    ) {
        app.launchEnvironment["QA_SKIP_ONBOARDING"] = "1"
        app.launchEnvironment["QA_AUTHENTICATED"] = "1"
        app.launchEnvironment["QA_IDENTIFICATION_PROVIDER"] = "local"
        for (key, value) in environment {
            app.launchEnvironment[key] = value
        }
        app.launch()
    }

    func openCamera(_ app: XCUIApplication) {
        let fab = app.buttons["tab.camera"]
        XCTAssertTrue(fab.waitForExistence(timeout: 10))
        fab.tap()
    }

    func submitReviewedPhoto(_ app: XCUIApplication) {
        openCamera(app)
        XCTAssertTrue(app.images["photo.review"].waitForExistence(timeout: 10))
        app.buttons["photo.acknowledge"].tap()
        app.alerts["사진 처리 안내"].buttons["동의하고 계속"].tap()
    }

    func assertResultSpeciesAccessibility(in app: XCUIApplication) {
        let species = app.staticTexts["capture.result.species"]
        XCTAssertTrue(species.exists)
        XCTAssertEqual(species.label, "몬스테라 델리시오사")
        XCTAssertFalse(species.label.unicodeScalars.contains("\u{2060}"))
        let attachment = XCTAttachment(screenshot: app.screenshot())
        attachment.name = "capture-result-korean-ax5"
        attachment.lifetime = .keepAlways
        add(attachment)
    }

    func assertMinimumTargets(_ app: XCUIApplication, identifiers: [String]) {
        for identifier in identifiers {
            let control = app.buttons[identifier]
            XCTAssertTrue(control.exists, "\(identifier) should exist")
            XCTAssertGreaterThanOrEqual(
                control.frame.height.rounded(),
                44,
                "\(identifier) must keep a 44pt target"
            )
        }
    }
}
