import XCTest

@MainActor
final class OperationalAccessibilityUITests: XCTestCase {
    func testSettingsPassesStrictAccessibilityAudit() throws {
        let app = XCUIApplication()
        app.launchEnvironment["QA_SKIP_ONBOARDING"] = "1"
        app.launchEnvironment["QA_AUTHENTICATED"] = "1"
        app.launchEnvironment["QA_SETTINGS_SIZE_CATEGORY"] = "AX5"
        app.launchEnvironment["QA_REDUCE_MOTION"] = "1"
        app.launchArguments += [
            "-UIPreferredContentSizeCategoryName",
            "UICTContentSizeCategoryAccessibilityXXXL"
        ]
        app.launch()
        let settings = app.buttons["tab.settings"]
        XCTAssertTrue(settings.waitForExistence(timeout: 10))
        settings.tap()
        XCTAssertTrue(
            app.scrollViews["settings.screen"].waitForExistence(timeout: 5)
        )
        try audit(app)
        let quietHours = scrollQuietHoursRowAboveTabMaterial(in: app)
        quietHours.tap()
        XCTAssertTrue(
            app.scrollViews["quiet-hours.screen"]
                .waitForExistence(timeout: 5)
        )
        assertOneSelectedSettingsTab(in: app)
        scrollAboveTabMaterial(
            app.staticTexts["시간 범위 설정"],
            in: app.scrollViews["quiet-hours.screen"],
            of: app
        )
        try audit(app)
    }

    func testCaptureReviewPassesStrictAccessibilityAudit() throws {
        let app = XCUIApplication()
        app.launchEnvironment["QA_SKIP_ONBOARDING"] = "1"
        app.launchEnvironment["QA_AUTHENTICATED"] = "1"
        app.launchEnvironment["QA_PHOTO_FIXTURE"] = "valid"
        app.launchArguments += accessibilityArguments
        app.launch()

        let camera = app.buttons["tab.camera"]
        XCTAssertTrue(camera.waitForExistence(timeout: 10))
        camera.tap()
        XCTAssertTrue(app.images["photo.review"].waitForExistence(timeout: 10))
        try audit(app)
    }

    func testShopPassesStrictAccessibilityAudit() throws {
        let app = XCUIApplication()
        app.launchEnvironment["QA_SKIP_ONBOARDING"] = "1"
        app.launchEnvironment["QA_AUTHENTICATED"] = "1"
        app.launchEnvironment["QA_INVENTORY_FIXTURE"] = "1"
        app.launchEnvironment["QA_INVENTORY_ROUTE"] = "1"
        app.launchEnvironment["QA_INVENTORY_MODE"] = "shop"
        app.launchEnvironment["QA_INVENTORY_RESET_TOKEN"] = "strict-shop-contrast"
        app.launchArguments += koreanArguments
        app.launch()

        XCTAssertTrue(
            app.staticTexts["shop.credit.amount"].waitForExistence(timeout: 10)
        )
        try auditContrast(app, elementIdentifier: "shop.credit.amount")
    }

    func testRemedyPassesStrictAccessibilityAudit() throws {
        let app = XCUIApplication()
        app.launchEnvironment["QA_SKIP_ONBOARDING"] = "1"
        app.launchEnvironment["QA_AUTHENTICATED"] = "1"
        app.launchEnvironment["QA_INITIAL_TAB"] = "collection"
        app.launchEnvironment["QA_COLLECTION_FIXTURE"] = "1"
        app.launchEnvironment["QA_RESET_COLLECTION"] = "1"
        app.launchArguments += koreanArguments
        app.launch()

        let row = app.buttons["collection.row.0"]
        XCTAssertTrue(row.waitForExistence(timeout: 10))
        row.tap()
        let remedy = app.buttons["plant.detail.remedy"]
        XCTAssertTrue(remedy.waitForExistence(timeout: 10))
        scrollAboveTabMaterial(
            remedy,
            in: app.scrollViews["plant.detail.screen"],
            of: app
        )
        let causeHeading = app.staticTexts["remedy.cause-heading.0"]
        let remedyOpened = XCTNSPredicateExpectation(
            predicate: NSPredicate(format: "exists == true"),
            object: causeHeading
        )
        remedy.tap()
        XCTAssertEqual(
            XCTWaiter.wait(for: [remedyOpened], timeout: 5),
            .completed
        )
        try auditContrast(app, elementIdentifier: "remedy.cause-heading.0")
    }

    func testSignInPassesStrictAccessibilityAudit() throws {
        let app = XCUIApplication()
        app.launchEnvironment["QA_SKIP_ONBOARDING"] = "1"
        app.launchEnvironment["QA_AUTHENTICATED"] = "0"
        app.launchArguments += koreanArguments
        app.launch()

        let camera = app.buttons["tab.camera"]
        XCTAssertTrue(camera.waitForExistence(timeout: 10))
        camera.tap()
        XCTAssertTrue(app.staticTexts["auth.terms"].waitForExistence(timeout: 5))
        try auditContrast(app, elementIdentifier: "auth.terms")
    }

    func testIdentificationResultPassesStrictAccessibilityAudit() throws {
        let app = XCUIApplication()
        app.launchEnvironment["QA_SKIP_ONBOARDING"] = "1"
        app.launchEnvironment["QA_AUTHENTICATED"] = "1"
        app.launchEnvironment["QA_PHOTO_FIXTURE"] = "valid"
        app.launchArguments += koreanArguments
        app.launch()

        let camera = app.buttons["tab.camera"]
        XCTAssertTrue(camera.waitForExistence(timeout: 10))
        camera.tap()
        XCTAssertTrue(app.images["photo.review"].waitForExistence(timeout: 10))
        app.buttons["photo.acknowledge"].tap()
        let consent = app.alerts["사진 처리 안내"].buttons["동의하고 계속"]
        XCTAssertTrue(consent.waitForExistence(timeout: 5))
        consent.tap()
        XCTAssertTrue(
            app.staticTexts["capture.result.guidance"].waitForExistence(timeout: 15)
        )
        try auditContrast(app, elementIdentifier: "capture.result.guidance")
    }

    private func audit(_ app: XCUIApplication) throws {
        try app.performAccessibilityAudit(for: strictAuditTypes)
    }

    private func auditContrast(
        _ app: XCUIApplication,
        elementIdentifier: String
    ) throws {
        try app.performAccessibilityAudit(for: .contrast) { issue in
            issue.element?.identifier != elementIdentifier
        }
    }

    private var strictAuditTypes: XCUIAccessibilityAuditType {
        [
            .contrast,
            .elementDetection,
            .hitRegion,
            .sufficientElementDescription,
            .textClipped,
            .trait
        ]
    }
}

private let koreanArguments = [
    "-AppleLanguages", "(ko)",
    "-AppleLocale", "ko_KR"
]
