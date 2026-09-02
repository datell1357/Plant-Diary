import Foundation
import XCTest

extension HomeDashboardUITests {
    func testWateringCompletionCancelsPendingNotifications() throws {
        let app = XCUIApplication()
        app.launchEnvironment["QA_SKIP_ONBOARDING"] = "1"
        app.launchEnvironment["QA_AUTHENTICATED"] = "1"
        app.launchEnvironment["QA_COLLECTION_FIXTURE"] = "1"
        app.launchEnvironment["QA_ACCOUNT_ID"] = "qa-notification-" + UUID().uuidString
        app.launchEnvironment["QA_WATERING_TODAY"] = try qaFutureWateringDate()
        app.launch()

        enableWateringNotifications(in: app)
        app.terminate()
        app.launch()
        XCTAssertTrue(
            scheduledNotificationCount("예정 알림 2건", in: app)
                .waitForExistence(timeout: 10)
        )
        app.buttons["tab.collection"].tap()
        XCTAssertTrue(app.buttons["collection.row.0"].waitForExistence(timeout: 5))
        app.buttons["collection.row.0"].tap()
        let complete = app.buttons["watering.complete"]
        XCTAssertTrue(complete.waitForExistence(timeout: 5))
        complete.tap()
        app.buttons["tab.home"].tap()
        app.swipeUp()
        XCTAssertTrue(
            scheduledNotificationCount("예정 알림 0건", in: app)
                .waitForExistence(timeout: 10)
        )
    }

    private func enableWateringNotifications(in app: XCUIApplication) {
        XCTAssertTrue(app.scrollViews["home.screen"].waitForExistence(timeout: 5))
        app.buttons["tab.settings"].tap()
        XCTAssertTrue(app.scrollViews["settings.screen"].waitForExistence(timeout: 5))
        let watering = app.switches["settings.alerts.watering-enabled"]
        XCTAssertTrue(watering.waitForExistence(timeout: 5))
        if watering.value as? String == "1" {
            watering.tap()
        }
        let permissionMonitor = addUIInterruptionMonitor(
            withDescription: "Notification authorization"
        ) { alert in
            let allow = alert.buttons["허용"].exists
                ? alert.buttons["허용"]
                : alert.buttons["Allow"]
            guard allow.exists else { return false }
            allow.tap()
            return true
        }
        watering.tap()
        app.tap()
        removeUIInterruptionMonitor(permissionMonitor)
        XCTAssertTrue(
            app.staticTexts
                .matching(identifier: "settings.permission.notifications")
                .matching(NSPredicate(format: "label == %@", "허용됨"))
                .firstMatch
                .waitForExistence(timeout: 5)
        )
    }

    private func qaFutureWateringDate() throws -> String {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = .current
        let futureDate = try XCTUnwrap(
            calendar.date(byAdding: .day, value: 1, to: Date())
        )
        let formatter = DateFormatter()
        formatter.calendar = calendar
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.dateFormat = "yyyy-MM-dd"
        return formatter.string(from: futureDate)
    }

    private func scheduledNotificationCount(
        _ label: String,
        in app: XCUIApplication
    ) -> XCUIElement {
        app.staticTexts
            .matching(identifier: "home.notification.scheduled")
            .matching(NSPredicate(format: "label == %@", label))
            .firstMatch
    }
}
