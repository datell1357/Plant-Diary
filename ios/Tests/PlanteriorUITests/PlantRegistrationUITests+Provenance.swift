import XCTest

@MainActor
final class PlantRegistrationProvenanceUITests: XCTestCase {
    func testManualCareRequiresCuratedSelectionAndKeepsUnsupportedDetailSafe() {
        let accountID = "manual-care-unselected"
        let registration = manualRegistrationApp(
            accountID: accountID,
            resetCollection: true
        )
        registration.launch()

        let search = registration.textFields["registration.search"]
        XCTAssertTrue(search.waitForExistence(timeout: 5))
        search.tap()
        search.typeText("몬스테라")
        let option = registration.buttons[
            "registration.care-option.monstera-deliciosa"
        ]
        XCTAssertTrue(option.waitForExistence(timeout: 5))
        XCTAssertEqual(option.value as? String, "선택되지 않음")

        let name = registration.textFields["registration.name"]
        name.tap()
        name.typeText("우리 집 잎이")
        dismissKeyboard(in: registration)
        registration.buttons["registration.submit"].tap()
        XCTAssertTrue(
            registration.staticTexts["registration.saved"].waitForExistence(
                timeout: 5
            )
        )
        registration.terminate()

        let collection = collectionApp(accountID: accountID)
        collection.launch()
        XCTAssertTrue(collection.buttons["collection.row.0"].waitForExistence(timeout: 5))
        collection.buttons["collection.row.0"].tap()
        XCTAssertTrue(
            collection.staticTexts["plant.detail.guide-unavailable"].waitForExistence(
                timeout: 5
            )
        )
        XCTAssertFalse(collection.otherElements["plant.detail.guide-source"].exists)
        attachScreenshot(
            collection,
            named: "manual-unselected-unsupported-detail-normal"
        )
    }

    func testCuratedManualSelectionPersistsSourceLinkAfterRelaunch() {
        let accountID = "manual-care-selected"
        let registration = manualRegistrationApp(
            accountID: accountID,
            resetCollection: true
        )
        registration.launch()

        let search = registration.textFields["registration.search"]
        XCTAssertTrue(search.waitForExistence(timeout: 5))
        search.tap()
        search.typeText("몬스테라")
        let option = registration.buttons[
            "registration.care-option.monstera-deliciosa"
        ]
        XCTAssertTrue(option.waitForExistence(timeout: 5))
        option.tap()
        XCTAssertEqual(option.value as? String, "선택됨")
        attachScreenshot(
            registration,
            named: "manual-curated-option-selected-normal"
        )

        let name = registration.textFields["registration.name"]
        name.tap()
        name.typeText("우리 집 잎이")
        dismissKeyboard(in: registration)
        registration.buttons["registration.submit"].tap()
        XCTAssertTrue(
            registration.staticTexts["registration.saved"].waitForExistence(
                timeout: 5
            )
        )
        registration.terminate()

        let collection = collectionApp(accountID: accountID)
        collection.launch()
        XCTAssertTrue(collection.buttons["collection.row.0"].waitForExistence(timeout: 5))
        collection.buttons["collection.row.0"].tap()
        let source = guideSource(in: collection)
        XCTAssertTrue(source.waitForExistence(timeout: 5))
        XCTAssertTrue(source.isHittable)
        XCTAssertTrue(source.label.contains("15059042"))
        attachScreenshot(
            collection,
            named: "manual-selected-source-detail-normal"
        )
    }

    func testKoreanAX5SourceLinkRemainsReadable() {
        let app = XCUIApplication()
        app.launchEnvironment["QA_SKIP_ONBOARDING"] = "1"
        app.launchEnvironment["QA_AUTHENTICATED"] = "1"
        app.launchEnvironment["QA_INITIAL_TAB"] = "collection"
        app.launchEnvironment["QA_COLLECTION_FIXTURE"] = "1"
        app.launchEnvironment["QA_RESET_COLLECTION"] = "1"
        app.launchArguments += [
            "-AppleLanguages", "(ko)",
            "-AppleLocale", "ko_KR",
            "-UIPreferredContentSizeCategoryName",
            "UICTContentSizeCategoryAccessibilityXXXL"
        ]
        app.launch()

        XCTAssertTrue(app.buttons["collection.row.0"].waitForExistence(timeout: 5))
        app.buttons["collection.row.0"].tap()
        let source = guideSource(in: app)
        XCTAssertTrue(source.waitForExistence(timeout: 5))
        XCTAssertTrue(source.isHittable)
        XCTAssertFalse(source.label.contains("…"))
        attachScreenshot(app, named: "manual-source-detail-korean-ax5")
    }

    private func manualRegistrationApp(
        accountID: String,
        resetCollection: Bool
    ) -> XCUIApplication {
        let app = XCUIApplication()
        app.launchEnvironment["QA_SKIP_ONBOARDING"] = "1"
        app.launchEnvironment["QA_AUTHENTICATED"] = "1"
        app.launchEnvironment["QA_ACCOUNT_ID"] = accountID
        app.launchEnvironment["QA_MANUAL_REGISTRATION"] = "1"
        if resetCollection {
            app.launchEnvironment["QA_RESET_COLLECTION"] = "1"
        }
        return app
    }

    private func collectionApp(accountID: String) -> XCUIApplication {
        let app = XCUIApplication()
        app.launchEnvironment["QA_SKIP_ONBOARDING"] = "1"
        app.launchEnvironment["QA_AUTHENTICATED"] = "1"
        app.launchEnvironment["QA_ACCOUNT_ID"] = accountID
        app.launchEnvironment["QA_INITIAL_TAB"] = "collection"
        return app
    }

    private func dismissKeyboard(in app: XCUIApplication) {
        let returnKey = app.keyboards.buttons["Return"]
        guard returnKey.exists else { return }
        returnKey.tap()
        XCTAssertTrue(app.keyboards.firstMatch.waitForNonExistence(timeout: 5))
    }

    private func guideSource(in app: XCUIApplication) -> XCUIElement {
        let source = app.descendants(matching: .any)["plant.detail.guide-source"]
        XCTAssertTrue(source.waitForExistence(timeout: 5))
        for _ in 0..<3 {
            guard !source.isHittable else { break }
            app.scrollViews["plant.detail.screen"].swipeUp()
        }
        XCTAssertTrue(source.isHittable)
        return source
    }

    private func attachScreenshot(_ app: XCUIApplication, named name: String) {
        let attachment = XCTAttachment(screenshot: app.screenshot())
        attachment.name = name
        attachment.lifetime = .keepAlways
        add(attachment)
    }
}
