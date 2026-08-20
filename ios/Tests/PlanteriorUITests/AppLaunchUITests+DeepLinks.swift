import XCTest

extension AppLaunchUITests {
    private static let unavailableTitle = "항목을 찾을 수 없어요"

    private func assertUnavailableFallback(
        deepLink: String,
        extraEnvironment: [String: String] = [:],
        forbiddenText: String
    ) {
        let app = XCUIApplication()
        app.launchEnvironment["QA_DEEP_LINK"] = deepLink
        app.launchEnvironment["QA_SKIP_ONBOARDING"] = "1"
        for (key, value) in extraEnvironment {
            app.launchEnvironment[key] = value
        }
        app.launch()
        XCTAssertTrue(
            app.otherElements["route.unavailable"].waitForExistence(timeout: 5)
        )
        XCTAssertFalse(app.staticTexts[forbiddenText].exists)
        XCTAssertEqual(
            app.staticTexts.matching(
                NSPredicate(format: "label == %@", Self.unavailableTitle)
            ).count,
            1
        )
        app.terminate()
    }

    func testAvailablePlantURLUsesRealCareDetail() {
        let app = XCUIApplication()
        app.launchEnvironment["QA_DEEP_LINK"] = "planterior://plant/local-0"
        app.launchEnvironment["QA_COLLECTION_FIXTURE"] = "1"
        app.launchEnvironment["QA_RESET_COLLECTION"] = "1"
        app.launchEnvironment["QA_AUTHENTICATED"] = "1"
        app.launchEnvironment["QA_SKIP_ONBOARDING"] = "1"
        app.launch()

        let nickname = app.textFields["plant.detail.nickname"]
        XCTAssertTrue(nickname.waitForExistence(timeout: 5))
        XCTAssertEqual(nickname.value as? String, "몬스테라")
        XCTAssertFalse(app.otherElements["plant.detail"].exists)
        XCTAssertFalse(
            app.staticTexts["notification.scheduled-count"].exists
        )
    }

    func testUnavailableURLsFallBackWithoutMetadata() {
        let collectionEnvironment = [
            "QA_COLLECTION_FIXTURE": "1",
            "QA_RESET_COLLECTION": "1",
            "QA_AUTHENTICATED": "1"
        ]

        assertUnavailableFallback(
            deepLink: "https://evil.test/plant/private-plant",
            forbiddenText: "private-plant"
        )
        assertUnavailableFallback(
            deepLink: "planterior://plant/private-plant",
            extraEnvironment: collectionEnvironment,
            forbiddenText: "private-plant"
        )
        assertUnavailableFallback(
            deepLink: "planterior://plant/%2E%2E",
            forbiddenText: ".."
        )
        assertUnavailableFallback(
            deepLink: "planterior://plant/local-0",
            extraEnvironment: collectionEnvironment
                .merging(["QA_TARGET_DELETED": "1"]) { _, new in new },
            forbiddenText: "local-0"
        )
    }
}
