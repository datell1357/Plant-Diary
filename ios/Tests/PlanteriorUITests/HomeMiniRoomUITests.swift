import XCTest

@MainActor
final class HomeMiniRoomUITests: XCTestCase {
    func testAuthenticatedHomeUsesReferenceRoomControls() {
        let app = authenticatedApp(token: "room-controls-auth")
        app.launch()
        assertRoomControls(in: app)
        attachScreenshot(app, named: "room-home-authenticated")
    }

    func testLoggedOutHomeUsesReferenceRoomControls() {
        let app = XCUIApplication()
        app.launchEnvironment["QA_SKIP_ONBOARDING"] = "1"
        app.launchEnvironment["QA_AUTHENTICATED"] = "0"
        app.launch()
        assertRoomControls(in: app)
        attachScreenshot(app, named: "room-home-logged-out")
    }

    func testSignInSheetKeepsReferenceRoomControlsBehindModal() {
        let app = XCUIApplication()
        app.launchEnvironment["QA_SKIP_ONBOARDING"] = "1"
        app.launchEnvironment["QA_AUTHENTICATED"] = "0"
        app.launch()
        XCTAssertTrue(app.buttons["home.login.link"].waitForExistence(timeout: 10))
        app.buttons["home.login.link"].tap()
        XCTAssertTrue(app.buttons["auth.google"].waitForExistence(timeout: 5))
        assertRoomControls(in: app, hittable: false)
        attachScreenshot(app, named: "room-home-sign-in-sheet")
    }

    func testFreeRenameKeepsReferenceRoomControlsBehindModal() {
        let app = authenticatedApp(token: "room-controls-free")
        app.launchEnvironment["QA_HOME_RENAME_MODE"] = "free"
        app.launch()
        openRename(in: app)
        assertRoomControls(in: app, hittable: false)
        attachScreenshot(app, named: "room-home-rename-free")
    }

    func testPaidRenameKeepsReferenceRoomControlsBehindModal() {
        let app = authenticatedApp(token: "room-controls-paid")
        app.launchEnvironment["QA_HOME_RENAME_MODE"] = "paid"
        app.launch()
        openRename(in: app)
        assertRoomControls(in: app, hittable: false)
        attachScreenshot(app, named: "room-home-rename-paid")
    }

    private func authenticatedApp(token: String) -> XCUIApplication {
        let app = XCUIApplication()
        app.launchEnvironment["QA_SKIP_ONBOARDING"] = "1"
        app.launchEnvironment["QA_AUTHENTICATED"] = "1"
        app.launchEnvironment["QA_COLLECTION_FIXTURE"] = "1"
        app.launchEnvironment["QA_HOME_FIXTURE"] = "1"
        app.launchEnvironment["QA_AUTH_PROFILE_NAME"] = "민지"
        app.launchEnvironment["QA_HOME_WEATHER_STATE"] = "high-dry"
        app.launchEnvironment["QA_WATERING_TODAY"] = "2026-08-11"
        app.launchEnvironment["QA_MINIHOME_NOW"] = "2026-08-11T00:00:00Z"
        app.launchEnvironment["QA_MINIHOME_RESET_TOKEN"] = token
        return app
    }

    private func openRename(in app: XCUIApplication) {
        let title = app.buttons["home.room.title"]
        XCTAssertTrue(title.waitForExistence(timeout: 10))
        title.tap()
        XCTAssertTrue(
            app.descendants(matching: .any)["home.rename.dialog"]
                .waitForExistence(timeout: 5)
        )
    }

    private func assertRoomControls(
        in app: XCUIApplication,
        hittable: Bool = true
    ) {
        XCTAssertFalse(
            app.images["home.room.hero"].exists,
            "the decorative Home room base must not be a VoiceOver stop"
        )
        let decorate = app.buttons["home.room.decorate"]
        let share = app.buttons["home.room.share"]
        XCTAssertTrue(decorate.exists)
        XCTAssertTrue(share.exists)
        XCTAssertEqual(decorate.label, "미니홈 꾸미기")
        XCTAssertEqual(share.label, "미니홈 공유")
        XCTAssertEqual(decorate.isHittable, hittable)
        XCTAssertEqual(share.isHittable, hittable)
        XCTAssertGreaterThanOrEqual(decorate.frame.width, 44)
        XCTAssertGreaterThanOrEqual(share.frame.width, 44)
    }

    private func attachScreenshot(_ app: XCUIApplication, named name: String) {
        let attachment = XCTAttachment(screenshot: app.screenshot())
        attachment.name = name
        attachment.lifetime = .keepAlways
        add(attachment)
    }
}
