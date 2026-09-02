import XCTest

@MainActor
final class SymptomHealthUITests: XCTestCase {
    func testUnsupportedSpeciesShowsGenericEducationWithWateringBaselineFirst() {
        let app = remedyApp(scientificName: "Plantus unsupported")
        app.launch()

        openRemedy(in: app)

        let disclaimer = app.staticTexts["remedy.disclaimer"]
        XCTAssertTrue(disclaimer.exists)
        XCTAssertEqual(
            disclaimer.label,
            "이 안내는 진단이 아니며, 관찰 가능한 가능성과 확인 순서만 제공합니다."
        )
        XCTAssertTrue(app.buttons["remedy.symptom.0"].exists)
        XCTAssertEqual(app.staticTexts["remedy.cause-heading.0"].label, "가능성")
        XCTAssertEqual(app.staticTexts["remedy.check-heading.0"].label, "확인 순서")
        XCTAssertTrue(
            app.staticTexts["remedy.action.0"].label.hasPrefix("최근 물 준 날짜가 기록되어 있으니")
        )
        XCTAssertFalse(app.staticTexts["remedy.unavailable"].exists)
        attachScreenshot(app, named: "symptom-unsupported-generic-baseline")
    }

    func testMissingScientificNameShowsGenericEducationWithoutWateringBaselineFirst() {
        let app = remedyApp()
        app.launch()

        openRemedy(in: app, row: 1)

        XCTAssertTrue(app.buttons["remedy.symptom.0"].exists)
        XCTAssertEqual(app.staticTexts["remedy.cause-heading.0"].label, "가능성")
        XCTAssertEqual(app.staticTexts["remedy.check-heading.0"].label, "확인 순서")
        XCTAssertTrue(
            app.staticTexts["remedy.action.0"].label.hasPrefix("최근 물 준 날짜가 기록되어 있지 않으니")
        )
        XCTAssertFalse(app.staticTexts["remedy.unavailable"].exists)
        attachScreenshot(app, named: "symptom-missing-identity-generic-no-baseline")
    }

    func testMonsteraShowsSpeciesEducationAndKoreanDisclaimerAtAX5() {
        let app = remedyApp(scientificName: "Monstera deliciosa")
        app.launchArguments += [
            "-AppleLanguages", "(ko)",
            "-AppleLocale", "ko_KR",
            "-UIPreferredContentSizeCategoryName",
            "UICTContentSizeCategoryAccessibilityXXXL"
        ]
        app.launch()

        openRemedy(in: app)

        let disclaimer = app.staticTexts["remedy.disclaimer"]
        XCTAssertTrue(disclaimer.exists)
        XCTAssertEqual(
            disclaimer.label,
            "이 안내는 진단이 아니며, 관찰 가능한 가능성과 확인 순서만 제공합니다."
        )
        XCTAssertTrue(app.buttons["remedy.symptom.0"].exists)
        XCTAssertEqual(app.staticTexts["remedy.cause-heading.0"].label, "가능성")
        XCTAssertEqual(app.staticTexts["remedy.check-heading.0"].label, "확인 순서")
        XCTAssertTrue(app.staticTexts["remedy.action.0"].exists)
        XCTAssertFalse(app.staticTexts["remedy.unavailable"].exists)
        XCTAssertFalse(disclaimer.frame.isEmpty)
        XCTAssertLessThanOrEqual(disclaimer.frame.maxX, app.frame.maxX)
        attachScreenshot(app, named: "symptom-monstera-ax5-disclaimer")
        let action = app.staticTexts["remedy.action.0"]
        let screen = app.scrollViews["remedy.screen"]
        for _ in 0 ..< 3 where !action.isHittable {
            screen.swipeUp()
        }
        XCTAssertTrue(action.isHittable)
        attachScreenshot(app, named: "symptom-monstera-ax5-card")
    }

    func testHealthNotePersistsAcrossRelaunchAndStaysIsolatedBetweenAccounts() {
        let scope = UUID().uuidString
        let accountA = "symptom-health-a-\(scope)"
        let accountB = "symptom-health-b-\(scope)"
        let note = "A 전용 건강 기록"

        let firstA = healthNoteApp(accountID: accountA, reset: true)
        firstA.launch()
        addHealthNote(note, in: firstA)
        firstA.terminate()

        let accountBApp = healthNoteApp(accountID: accountB, seedFixture: true)
        accountBApp.launch()
        openPlantDetail(in: accountBApp)
        XCTAssertFalse(accountBApp.staticTexts[note].exists)
        accountBApp.terminate()

        let returnedA = healthNoteApp(accountID: accountA, reset: false)
        returnedA.launch()
        openPlantDetail(in: returnedA)
        assertHealthNoteVisible(note, in: returnedA)
        attachScreenshot(returnedA, named: "health-note-account-a-restored")
        returnedA.terminate()
    }

    private func remedyApp(scientificName: String? = nil) -> XCUIApplication {
        let app = XCUIApplication()
        app.launchEnvironment["QA_SKIP_ONBOARDING"] = "1"
        app.launchEnvironment["QA_AUTHENTICATED"] = "1"
        app.launchEnvironment["QA_INITIAL_TAB"] = "collection"
        app.launchEnvironment["QA_ACCOUNT_ID"] = "symptom-health-\(UUID().uuidString)"
        app.launchEnvironment["QA_COLLECTION_FIXTURE"] = "1"
        if let scientificName {
            app.launchEnvironment["QA_COLLECTION_SCIENTIFIC_NAME"] = scientificName
        }
        app.launchEnvironment["QA_RESET_COLLECTION"] = "1"
        return app
    }

    private func openRemedy(in app: XCUIApplication, row: Int = 0) {
        openPlantDetail(in: app, row: row)
        let detail = app.scrollViews["plant.detail.screen"]
        let remedy = app.buttons["plant.detail.remedy"]
        for _ in 0 ..< 6 where !remedy.isHittable {
            detail.swipeUp()
        }
        XCTAssertTrue(remedy.waitForExistence(timeout: 5))
        remedy.tap()
        XCTAssertTrue(app.scrollViews["remedy.screen"].waitForExistence(timeout: 5))
    }

    private func openPlantDetail(in app: XCUIApplication, row: Int = 0) {
        let plantRow = app.buttons["collection.row.\(row)"]
        XCTAssertTrue(plantRow.waitForExistence(timeout: 10))
        plantRow.tap()
        XCTAssertTrue(app.scrollViews["plant.detail.screen"].waitForExistence(timeout: 5))
    }

    private func healthNoteApp(
        accountID: String,
        reset: Bool = false,
        seedFixture: Bool = false
    ) -> XCUIApplication {
        let app = XCUIApplication()
        app.launchEnvironment["QA_SKIP_ONBOARDING"] = "1"
        app.launchEnvironment["QA_AUTHENTICATED"] = "1"
        app.launchEnvironment["QA_INITIAL_TAB"] = "collection"
        app.launchEnvironment["QA_ACCOUNT_ID"] = accountID
        if seedFixture || reset {
            app.launchEnvironment["QA_COLLECTION_FIXTURE"] = "1"
        }
        if reset {
            app.launchEnvironment["QA_RESET_COLLECTION"] = "1"
        }
        return app
    }

    private func addHealthNote(_ note: String, in app: XCUIApplication) {
        openPlantDetail(in: app)
        let detail = app.scrollViews["plant.detail.screen"]
        let field = app.textFields["plant.detail.note"]
        for _ in 0 ..< 6 where !field.isHittable {
            detail.swipeUp()
        }
        XCTAssertTrue(field.isHittable)
        field.tap()
        field.typeText(note)
        app.keyboards.buttons["Return"].tap()
        let add = app.buttons["plant.detail.add-note"]
        for _ in 0 ..< 3 where !add.isHittable {
            detail.swipeUp()
        }
        XCTAssertTrue(add.isHittable)
        add.tap()
        assertHealthNoteVisible(note, in: app)
    }

    private func assertHealthNoteVisible(_ note: String, in app: XCUIApplication) {
        let detail = app.scrollViews["plant.detail.screen"]
        let timeline = app.staticTexts[note]
        detail.swipeUp()
        XCTAssertTrue(timeline.waitForExistence(timeout: 5))
    }

    private func attachScreenshot(_ app: XCUIApplication, named name: String) {
        let attachment = XCTAttachment(screenshot: app.screenshot())
        attachment.name = name
        attachment.lifetime = .keepAlways
        add(attachment)
    }
}
