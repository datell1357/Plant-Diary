import XCTest

@MainActor
extension SettingsDeletionUITests {
    func testWateringPreferenceDoesNotCrossAccounts() {
        let accountA = "qa-watering-a-\(UUID().uuidString)"
        let accountB = "qa-watering-b-\(UUID().uuidString)"
        let first = figmaSettingsApp()
        first.launchEnvironment["QA_ACCOUNT_ID"] = accountA
        first.launch()
        openFigmaSettings(in: first)
        let firstToggle = first.switches["settings.alerts.watering-enabled"]
        if firstToggle.value as? String != "0" {
            firstToggle.tap()
            let disabled = XCTNSPredicateExpectation(
                predicate: NSPredicate(format: "value == '0'"),
                object: firstToggle
            )
            XCTAssertEqual(XCTWaiter.wait(for: [disabled], timeout: 5), .completed)
        }
        first.terminate()

        let second = figmaSettingsApp()
        second.launchEnvironment["QA_ACCOUNT_ID"] = accountB
        second.launch()
        openFigmaSettings(in: second)
        XCTAssertEqual(
            second.switches["settings.alerts.watering-enabled"].value as? String,
            "1",
            "a new account must receive its own default watering preference"
        )
        second.terminate()

        let restored = figmaSettingsApp()
        restored.launchEnvironment["QA_ACCOUNT_ID"] = accountA
        restored.launch()
        openFigmaSettings(in: restored)
        XCTAssertEqual(
            restored.switches[
                "settings.alerts.watering-enabled"
            ].value as? String,
            "0",
            "returning to an account must restore only that account's preference"
        )
        attachScreenshot(named: "track-3-watering-account-a-restored")
    }

    func testSettingsProfileUsesAuthenticatedSessionIdentity() {
        let app = figmaSettingsApp()
        app.launchEnvironment["QA_AUTH_PROFILE_NAME"] = "서연"
        app.launchEnvironment["QA_AUTH_PROFILE_EMAIL"] = "owner+garden@example.org"
        app.launch()
        openFigmaSettings(in: app)

        XCTAssertEqual(app.staticTexts["settings.profile.name"].label, "서연")
        XCTAssertEqual(
            app.staticTexts["settings.profile.email"].label,
            "owner+garden@example.org"
        )
        XCTAssertFalse(app.staticTexts["민지"].exists)
        XCTAssertFalse(app.staticTexts["minji@email.com"].exists)
    }
}
