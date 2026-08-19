import XCTest

extension ProgressionUITests {
    func verifyInitialAndRegistration(in app: XCUIApplication) {
        waitForLabel(
            "서버 경험치 50",
            identifier: "milestones.xp.server",
            in: app
        )
        XCTAssertEqual(
            app.staticTexts["milestones.unpublished-count"].label,
            "숨김 보상 0개"
        )
        XCTAssertFalse(app.otherElements["milestone.row.hidden-1"].exists)
        performQA("milestones.qa.registration", in: app)
        waitForLabel(
            "서버 경험치 100",
            identifier: "milestones.xp.server",
            in: app
        )
        waitForValue(
            "earned",
            identifier: "milestone.state.registration-1",
            in: app
        )
        performQA("milestones.qa.duplicate", in: app)
        waitForLabel(
            "중복 영수증 1건",
            identifier: "milestones.duplicate-count",
            in: app
        )
    }

    func claimRegistration(in app: XCUIApplication) {
        let claim = app.buttons["milestone.claim.registration-1"]
        app.swipeUp()
        XCTAssertTrue(claim.waitForExistence(timeout: 5))
        XCTAssertTrue(claim.isHittable)
        claim.tap()
        waitForValue(
            "claimed",
            identifier: "milestone.state.registration-1",
            in: app
        )
    }

    func queueAndReconnect(in app: XCUIApplication) {
        performQA("milestones.qa.queue", in: app)
        waitForLabel(
            "동기화 대기 1건",
            identifier: "milestones.xp.queued",
            in: app
        )
        XCTAssertEqual(
            app.staticTexts["milestones.xp.server"].label,
            "서버 경험치 100"
        )
        performQA("milestones.qa.reconnect", in: app)
        waitForLabel(
            "동기화 대기 0건",
            identifier: "milestones.xp.queued",
            in: app
        )
        waitForLabel(
            "서버 경험치 200",
            identifier: "milestones.xp.server",
            in: app
        )
    }
}
