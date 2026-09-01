import XCTest

@MainActor
final class SymptomHealthUITests: XCTestCase {
    func testUnsupportedSpeciesShowsUnavailableEducationInsteadOfGenericCards() {
        let app = remedyApp(scientificName: "Plantus unsupported")
        app.launch()

        openRemedy(in: app)

        XCTAssertTrue(app.staticTexts["remedy.disclaimer"].exists)
        XCTAssertTrue(app.staticTexts["remedy.unavailable"].exists)
        XCTAssertFalse(app.buttons["remedy.symptom.0"].exists)
        attachScreenshot(app, named: "symptom-unsupported-unavailable")
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
        XCTAssertTrue(disclaimer.label.contains("확정 진단이 아닙니다."))
        XCTAssertTrue(app.buttons["remedy.symptom.0"].exists)
        XCTAssertTrue(app.staticTexts["remedy.cause-heading.0"].exists)
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

        let restoredA = healthNoteApp(accountID: accountA, reset: false)
        restoredA.launch()
        openPlantDetail(in: restoredA)
        assertHealthNoteVisible(note, in: restoredA)
        restoredA.terminate()

        let accountBApp = healthNoteApp(accountID: accountB, reset: true)
        accountBApp.launch()
        openPlantDetail(in: accountBApp)
        XCTAssertFalse(accountBApp.staticTexts[note].exists)
        accountBApp.terminate()

        let returnedA = healthNoteApp(accountID: accountA, reset: false)
        returnedA.launch()
        openPlantDetail(in: returnedA)
        assertHealthNoteVisible(note, in: returnedA)
        attachScreenshot(returnedA, named: "health-note-account-a-restored")
    }

    private func remedyApp(scientificName: String) -> XCUIApplication {
        let app = XCUIApplication()
        app.launchEnvironment["QA_SKIP_ONBOARDING"] = "1"
        app.launchEnvironment["QA_AUTHENTICATED"] = "1"
        app.launchEnvironment["QA_INITIAL_TAB"] = "collection"
        app.launchEnvironment["QA_ACCOUNT_ID"] = "symptom-health-\(UUID().uuidString)"
        app.launchEnvironment["QA_COLLECTION_FIXTURE"] = "1"
        app.launchEnvironment["QA_COLLECTION_SCIENTIFIC_NAME"] = scientificName
        app.launchEnvironment["QA_RESET_COLLECTION"] = "1"
        return app
    }

    private func openRemedy(in app: XCUIApplication) {
        openPlantDetail(in: app)
        let detail = app.scrollViews["plant.detail.screen"]
        let remedy = app.buttons["plant.detail.remedy"]
        for _ in 0 ..< 3 where !remedy.isHittable {
            detail.swipeUp()
        }
        XCTAssertTrue(remedy.isHittable)
        remedy.tap()
        XCTAssertTrue(app.scrollViews["remedy.screen"].waitForExistence(timeout: 5))
    }

    private func openPlantDetail(in app: XCUIApplication) {
        let row = app.buttons["collection.row.0"]
        XCTAssertTrue(row.waitForExistence(timeout: 10))
        row.tap()
        XCTAssertTrue(app.scrollViews["plant.detail.screen"].waitForExistence(timeout: 5))
    }

    private func healthNoteApp(accountID: String, reset: Bool) -> XCUIApplication {
        let app = XCUIApplication()
        app.launchEnvironment["QA_SKIP_ONBOARDING"] = "1"
        app.launchEnvironment["QA_AUTHENTICATED"] = "1"
        app.launchEnvironment["QA_INITIAL_TAB"] = "collection"
        app.launchEnvironment["QA_ACCOUNT_ID"] = accountID
        if reset {
            app.launchEnvironment["QA_COLLECTION_FIXTURE"] = "1"
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
        for _ in 0 ..< 6 where !timeline.isHittable {
            detail.swipeUp()
        }
        XCTAssertTrue(timeline.isHittable)
    }

    private func attachScreenshot(_ app: XCUIApplication, named name: String) {
        let attachment = XCTAttachment(screenshot: app.screenshot())
        attachment.name = name
        attachment.lifetime = .keepAlways
        add(attachment)
    }
}
