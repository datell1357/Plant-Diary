import XCTest

@MainActor
final class CollectionQAFixtureBootstrapUITests: XCTestCase {
    func testFigmaFixtureSurvivesAuthenticatedAccountBootstrap() {
        let app = XCUIApplication()
        let identity = CollectionQAFixtureIdentity(
            testID: #function,
            variant: "bootstrap",
            mode: .figma,
            empty: false
        )
        configureCollectionQAFixture(app, identity: identity, mode: .figma)
        app.launchEnvironment["QA_WATERING_TODAY"] = "2026-05-19"
        app.launch()

        waitForCollectionQAFixture(in: app, identity: identity)
        XCTAssertTrue(
            app.buttons["collection.row.0"].waitForExistence(timeout: 5),
            "the mounted QA account must retain the Figma fixture"
        )
        XCTAssertTrue(app.buttons["collection.add"].exists)
        XCTAssertFalse(app.buttons["collection.empty.camera"].exists)
    }
}
