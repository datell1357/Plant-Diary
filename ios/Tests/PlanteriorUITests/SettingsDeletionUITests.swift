import XCTest

@MainActor
final class SettingsDeletionUITests: XCTestCase, MiniHomeUITestSupport {
    func testSettingsPolicyAndPartialDeletionPreserveAccount() {
        let app = settingsApp()
        app.launch()
        openSettings(in: app)
        XCTAssertTrue(
            app.staticTexts["settings.permission.camera"]
                .waitForExistence(timeout: 5)
        )
        attachScreenshot(named: "task-18-settings")
        app.buttons["settings.privacy"].tap()
        XCTAssertTrue(
            app.scrollViews["privacy.screen"]
                .waitForExistence(timeout: 5)
        )
        app.buttons["닫기"].tap()
        openDeletion(in: app)
        completeRequest(in: app)
        app.buttons["account-deletion.qa.partial"].tap()
        waitForStatus("일부 삭제 실패 · 계정 유지", in: app)
        let cleanup = app.staticTexts["account-deletion.cleanup-count"].label
        XCTAssertEqual(cleanup, "로컬 정리 0회")
        attachSettingsEvidence(
            deletionStatus: "일부 삭제 실패 · 계정 유지",
            cleanupLabel: cleanup
        )
        attachScreenshot(named: "task-18-deletion")
    }

    func testCompletedReceiptAloneAuthorizesCleanupAtAX5() {
        let app = settingsApp(ax5: true)
        app.launch()
        openSettings(in: app)
        openDeletion(in: app)
        completeRequest(in: app)
        attachScreenshot(named: "task-18-deletion-ax5")
        app.swipeUp()
        app.buttons["account-deletion.qa.complete"].tap()
        waitForStatus("삭제 완료 · 로컬 정리 승인됨", in: app)
        let cleanup = app.staticTexts["account-deletion.cleanup-count"].label
        let receipts = app.staticTexts[
            "account-deletion.cleanup-receipts"
        ].label
        XCTAssertEqual(cleanup, "로컬 정리 1회")
        XCTAssertEqual(receipts, "정리 영수증 8개")
        attachJSON(
            [
                "observedStatus": "삭제 완료 · 로컬 정리 승인됨",
                "observedCleanup": cleanup,
                "observedReceipts": receipts
            ],
            named: "task-18-deletion-completed-data"
        )
    }

    private func settingsApp(ax5: Bool = false) -> XCUIApplication {
        let app = XCUIApplication()
        app.launchEnvironment["QA_SKIP_ONBOARDING"] = "1"
        app.launchEnvironment["QA_AUTHENTICATED"] = "1"
        app.launchEnvironment["QA_DELETION_FIXTURE"] = "1"
        app.launchEnvironment["QA_ACCOUNT_ID"] = "qa-account"
        app.launchEnvironment["QA_WEATHER_AUTHORIZATION"] = "denied"
        if ax5 {
            app.launchEnvironment["QA_SETTINGS_SIZE_CATEGORY"] = "AX5"
        }
        return app
    }

    private func openSettings(in app: XCUIApplication) {
        let tab = app.buttons["tab.settings"]
        XCTAssertTrue(tab.waitForExistence(timeout: 10))
        tab.tap()
        XCTAssertTrue(
            app.scrollViews["settings.screen"].waitForExistence(timeout: 5)
        )
    }

    private func openDeletion(in app: XCUIApplication) {
        let button = app.buttons["settings.delete-account"]
        if !button.isHittable {
            app.swipeUp()
        }
        button.tap()
        XCTAssertTrue(
            app.scrollViews["account-deletion.screen"]
                .waitForExistence(timeout: 5)
        )
    }

    private func completeRequest(in app: XCUIApplication) {
        app.buttons["account-deletion.reauthenticate"].tap()
        app.buttons["account-deletion.confirm"].tap()
        waitForStatus("삭제 요청 접수됨 · 7일 유예", in: app)
    }

    private func waitForStatus(
        _ label: String,
        in app: XCUIApplication
    ) {
        let state = app.staticTexts["account-deletion.status"]
        let expectation = XCTNSPredicateExpectation(
            predicate: NSPredicate(format: "label == %@", label),
            object: state
        )
        XCTAssertEqual(
            XCTWaiter.wait(for: [expectation], timeout: 5),
            .completed
        )
    }

    private func attachSettingsEvidence(
        deletionStatus: String,
        cleanupLabel: String
    ) {
        attachJSON(
            [
                "camera": "reported",
                "notifications": "reported",
                "location": "reported",
                "policy": "visible",
                "productionIntegration": "authenticated-callable"
            ],
            named: "task-18-settings-data"
        )
        attachJSON(
            [
                "observedStatus": deletionStatus,
                "observedCleanup": cleanupLabel,
                "failedScopeVisible": true
            ],
            named: "task-18-deletion-data"
        )
    }
}
