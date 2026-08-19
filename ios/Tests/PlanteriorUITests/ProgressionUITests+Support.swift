import XCTest

struct ProgressionLaunchOptions {
    let scenario: String
    let ax5: Bool
    let showControls: Bool

    init(
        scenario: String = "current",
        ax5: Bool = false,
        showControls: Bool = true
    ) {
        self.scenario = scenario
        self.ax5 = ax5
        self.showControls = showControls
    }
}

@MainActor
class ProgressionUITestCase: XCTestCase {
    func progressionApp(
        options: ProgressionLaunchOptions = .init()
    ) -> XCUIApplication {
        let app = XCUIApplication()
        app.launchEnvironment["QA_SKIP_ONBOARDING"] = "1"
        app.launchEnvironment["QA_AUTHENTICATED"] = "1"
        app.launchEnvironment["QA_PROGRESS_ROUTE"] = "1"
        app.launchEnvironment["QA_PROGRESS_FIXTURE"] = "1"
        app.launchEnvironment["QA_PROGRESS_ACCOUNT_ID"] = "qa-account"
        app.launchEnvironment["QA_PROGRESS_SCENARIO"] = options.scenario
        app.launchEnvironment["QA_PROGRESS_SHOW_CONTROLS"] =
            options.showControls ? "1" : "0"
        app.launchEnvironment["QA_PROGRESS_RESET_TOKEN"] =
            UUID().uuidString
        app.launchEnvironment["QA_PROGRESS_NOW"] =
            "2026-08-11T02:00:00Z"
        if options.ax5 {
            app.launchEnvironment["QA_PROGRESS_SIZE_CATEGORY"] = "AX5"
        }
        return app
    }

    func openProgression(in app: XCUIApplication) {
        XCTAssertTrue(
            app.scrollViews["milestones.screen"]
                .waitForExistence(timeout: 10)
        )
        XCTAssertTrue(
            app.staticTexts["milestones.xp.server"]
                .waitForExistence(timeout: 5)
        )
        XCTAssertTrue(
            app.navigationBars["꾸미기 마일스톤"]
                .waitForExistence(timeout: 5)
        )
    }

    func performQA(_ identifier: String, in app: XCUIApplication) {
        app.buttons["milestones.qa.menu"].tap()
        let action = app.buttons[identifier]
        XCTAssertTrue(action.waitForExistence(timeout: 5))
        action.tap()
    }

    func waitForLabel(
        _ expected: String,
        identifier: String,
        in app: XCUIApplication
    ) {
        let element = app.staticTexts[identifier]
        let changed = XCTNSPredicateExpectation(
            predicate: NSPredicate(format: "label == %@", expected),
            object: element
        )
        XCTAssertEqual(
            XCTWaiter.wait(for: [changed], timeout: 5),
            .completed
        )
    }

    func waitForValue(
        _ expected: String,
        identifier: String,
        in app: XCUIApplication
    ) {
        let element = app.staticTexts[identifier]
        let changed = XCTNSPredicateExpectation(
            predicate: NSPredicate(format: "value == %@", expected),
            object: element
        )
        XCTAssertEqual(
            XCTWaiter.wait(for: [changed], timeout: 5),
            .completed
        )
    }
}
