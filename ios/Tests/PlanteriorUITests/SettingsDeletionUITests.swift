import XCTest

@MainActor
final class SettingsDeletionUITests: XCTestCase, MiniHomeUITestSupport {
    func testPrivacyScreenDisclosesPhotoHandlingBoundaries() {
        let app = settingsApp()
        app.launch()
        openSettings(in: app)
        app.buttons["settings.privacy"].tap()
        let disclosures = [
            "privacy.disclosure.photo":
                "사진은 식물 식별을 확인한 뒤에만 전송됩니다.",
            "privacy.disclosure.photo-access":
                "사진 보관함은 사용자가 PhotosPicker에서 선택한 항목에만 접근합니다.",
            "privacy.disclosure.metadata":
                "선택한 사진은 방향을 보정해 JPEG로 다시 만들며 위치·EXIF 등 원본 메타데이터를 제거합니다.",
            "privacy.disclosure.draft-cache":
                "확인한 사진 초안은 기기 내부의 파일 보호가 적용된 캐시에 보관합니다.",
            "privacy.disclosure.retention":
                "대표 사진으로 저장하지 않은 초안은 생성 후 24시간이 지나면 삭제 대상이 됩니다."
        ]

        for (identifier, label) in disclosures {
            let disclosure = app.staticTexts[identifier]
            XCTAssertTrue(disclosure.waitForExistence(timeout: 5))
            XCTAssertEqual(disclosure.label, label)
        }
    }

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
        let partialFailure = statusExpectation(
            "일부 삭제 실패 · 계정 유지",
            in: app
        )
        app.buttons["account-deletion.qa.partial"].tap()
        waitForStatus(partialFailure)
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
        let completion = statusExpectation(
            "삭제 완료 · 로컬 정리 승인됨",
            in: app
        )
        app.buttons["account-deletion.qa.complete"].tap()
        waitForStatus(completion)
        let status = app.staticTexts["account-deletion.status"].label
        let cleanup = app.staticTexts["account-deletion.cleanup-count"].label
        let receipts = app.staticTexts[
            "account-deletion.cleanup-receipts"
        ].label
        XCTAssertEqual(cleanup, "로컬 정리 1회")
        XCTAssertEqual(receipts, "정리 영수증 8개")
        attachJSON(
            [
                "observedStatus": status,
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
        let requestReceived = statusExpectation(
            "삭제 요청 접수됨 · 7일 유예",
            in: app
        )
        app.buttons["account-deletion.confirm"].tap()
        waitForStatus(requestReceived)
    }

    private func statusExpectation(
        _ label: String,
        in app: XCUIApplication
    ) -> XCTNSPredicateExpectation {
        XCTNSPredicateExpectation(
            predicate: NSPredicate(format: "label == %@", label),
            object: app.staticTexts["account-deletion.status"]
        )
    }

    private func waitForStatus(_ expectation: XCTestExpectation) {
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
