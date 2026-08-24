import XCTest

@MainActor
final class WeatherFlowUITests: XCTestCase {
    func testManualRegionOverridesLocationAndAggregatesRisks() {
        let app = weatherApp()
        app.launchEnvironment["QA_WEATHER_AUTHORIZATION"] = "reduced"
        app.launchEnvironment["QA_WEATHER_MANUAL_REGION"] = "manual-seoul"
        app.launchEnvironment["QA_WEATHER_LOCATION_REGION"] = "location-busan"
        app.launchEnvironment["QA_WEATHER_FIXTURE"] = "high-dry"
        app.launchEnvironment["QA_WEATHER_NOW"] = "2026-08-11T03:00:00Z"
        app.launch()

        XCTAssertTrue(app.staticTexts["weather.region"].waitForExistence(timeout: 5))
        XCTAssertTrue(app.staticTexts["weather.region"].label.contains("서울"))
        XCTAssertTrue(app.staticTexts["weather.risk.high_temperature"].exists)
        XCTAssertTrue(app.staticTexts["weather.risk.dry"].exists)
        XCTAssertEqual(app.staticTexts["weather.location.calls"].label, "위치 요청 0회")
        XCTAssertEqual(app.staticTexts["weather.alert-count"].label, "예정 위험 알림 4건")
        attachScreenshot(named: "task-13-weather-risks")
    }

    func testRevokedLocationMakesZeroCallsAndKeepsCollectionAvailable() {
        let app = weatherApp()
        app.launchEnvironment["QA_WEATHER_AUTHORIZATION"] = "full"
        app.launchEnvironment["QA_WEATHER_LOCATION_REGION"] = "location-busan"
        app.launchEnvironment["QA_WEATHER_FIXTURE"] = "high-dry"
        app.launchEnvironment["QA_WEATHER_NOW"] = "2026-08-11T03:00:00Z"
        app.launchEnvironment["QA_WEATHER_SHOW_REVOKE"] = "1"
        app.launch()

        XCTAssertTrue(app.staticTexts["weather.region"].waitForExistence(timeout: 5))
        XCTAssertEqual(app.staticTexts["weather.location.calls"].label, "위치 요청 0회")
        let openRegion = app.buttons["weather.open-region"]
        scrollToWeatherControl(openRegion, in: app)
        openRegion.tap()
        let revoke = app.buttons["weather.qa-revoke"]
        XCTAssertTrue(revoke.waitForExistence(timeout: 5))
        revoke.tap()
        XCTAssertTrue(
            app.staticTexts["home.weather.unavailable"]
                .waitForExistence(timeout: 5)
        )
        XCTAssertEqual(app.staticTexts["weather.location.calls"].label, "위치 요청 0회")
        app.buttons["tab.collection"].tap()
        XCTAssertTrue(
            app.buttons["collection.search.action"].waitForExistence(timeout: 5)
        )
    }

    func testLocationTimeoutFailsWithoutBlockingCollection() {
        let app = weatherApp()
        app.launchEnvironment["QA_WEATHER_AUTHORIZATION"] = "full"
        app.launchEnvironment["QA_WEATHER_LOCATION_MODE"] = "timeout"
        app.launch()

        XCTAssertTrue(
            app.staticTexts["home.weather.failed"]
                .waitForExistence(timeout: 5)
        )
        XCTAssertEqual(
            app.staticTexts["weather.location.calls"].label,
            "위치 요청 1회"
        )
        app.buttons["tab.collection"].tap()
        XCTAssertTrue(
            app.buttons["collection.search.action"]
                .waitForExistence(timeout: 5)
        )
    }

    func testStaleWeatherDisplaysRisksWithoutAlert() {
        let app = weatherApp()
        app.launchEnvironment["QA_WEATHER_AUTHORIZATION"] = "denied"
        app.launchEnvironment["QA_WEATHER_MANUAL_REGION"] = "manual-seoul"
        app.launchEnvironment["QA_WEATHER_FIXTURE"] = "high-dry"
        app.launchEnvironment["QA_WEATHER_NOW"] = "2026-08-11T03:00:01Z"
        app.launch()

        XCTAssertTrue(app.staticTexts["weather.stale"].waitForExistence(timeout: 5))
        XCTAssertFalse(app.staticTexts["weather.alert-count"].exists)
        app.swipeUp()
        attachScreenshot(named: "task-13-weather-stale")
    }

    func testWeatherRisksRemainReadableAtAX5() {
        let app = weatherApp()
        app.launchEnvironment["QA_HOME_SIZE_CATEGORY"] = "AX5"
        app.launchEnvironment["QA_WEATHER_AUTHORIZATION"] = "denied"
        app.launchEnvironment["QA_WEATHER_MANUAL_REGION"] = "manual-seoul"
        app.launchEnvironment["QA_WEATHER_FIXTURE"] = "high-dry"
        app.launchEnvironment["QA_WEATHER_NOW"] = "2026-08-11T03:00:00Z"
        app.launch()

        app.swipeUp()
        app.swipeUp()
        app.swipeUp()
        XCTAssertTrue(
            app.staticTexts["weather.risk.high_temperature"]
                .waitForExistence(timeout: 5)
        )
        app.swipeUp()
        attachScreenshot(named: "task-13-weather-ax5")
        app.swipeUp()
        XCTAssertTrue(
            app.staticTexts["weather.alert-count"]
                .waitForExistence(timeout: 5)
        )
        scrollToWeatherControl(app.buttons["weather.open-region"], in: app)
        attachScreenshot(named: "task-13-weather-ax5-actions")
    }

    func testRegionSettingsAutoSavesManualRegionAndPersistsSelection() {
        let app = weatherApp()
        app.launchEnvironment["QA_WEATHER_AUTHORIZATION"] = "denied"
        app.launchEnvironment["QA_WEATHER_FIXTURE"] = "high-dry"
        app.launchEnvironment["QA_WEATHER_NOW"] = "2026-08-11T03:00:00Z"
        app.launch()

        let openRegion = app.buttons["weather.open-region"]
        scrollToWeatherControl(openRegion, in: app)
        XCTAssertTrue(app.staticTexts["weather.purpose"].exists)
        openRegion.tap()
        XCTAssertTrue(
            app.buttons["weather.use-current-location"]
                .waitForExistence(timeout: 5)
        )
        XCTAssertTrue(app.staticTexts["weather.recent-regions"].exists)
        let back = app.buttons["weather.region.back"]
        XCTAssertTrue(back.exists)
        XCTAssertTrue(back.isHittable)
        XCTAssertFalse(app.buttons["weather.region.save"].exists)
        attachScreenshot(named: "region-402x874-light")
        let region = app.textFields["weather.manual-region"]
        region.tap()
        region.typeText("서울")
        let result = app.buttons["weather.region-result.manual-seoul"]
        XCTAssertTrue(result.waitForExistence(timeout: 5))
        attachScreenshot(named: "task-13-weather-settings")
        result.tap()
        let savedRegion = app.staticTexts["weather.region"]
        XCTAssertTrue(savedRegion.waitForExistence(timeout: 5))
        XCTAssertTrue(savedRegion.label.contains("서울"))

        scrollToWeatherControl(openRegion, in: app)
        openRegion.tap()
        XCTAssertTrue(region.waitForExistence(timeout: 5))
        let persistedResult = app.buttons["weather.region-result.manual-seoul"]
        XCTAssertTrue(persistedResult.waitForExistence(timeout: 5))
        XCTAssertEqual(persistedResult.value as? String, "선택됨")
    }

    func testPerPlantWeatherAlertControlIsVisible() {
        let app = weatherApp()
        app.launchEnvironment["QA_WEATHER_AUTHORIZATION"] = "denied"
        app.launchEnvironment["QA_WEATHER_MANUAL_REGION"] = "manual-seoul"
        app.launchEnvironment["QA_WEATHER_FIXTURE"] = "high-dry"
        app.launchEnvironment["QA_WEATHER_NOW"] = "2026-08-11T03:00:00Z"
        app.launch()

        let alertCount = app.staticTexts["weather.alert-count"]
        XCTAssertTrue(alertCount.waitForExistence(timeout: 5))
        XCTAssertEqual(
            alertCount.label,
            "예정 위험 알림 4건"
        )
        app.buttons["tab.collection"].tap()
        let row = app.buttons["collection.row.0"]
        XCTAssertTrue(row.waitForExistence(timeout: 5))
        row.tap()
        XCTAssertTrue(
            app.switches["weather.plant-alerts-enabled"]
                .waitForExistence(timeout: 5)
        )
        let alerts = app.switches["weather.plant-alerts-enabled"]
        app.swipeUp()
        XCTAssertTrue(alerts.isHittable)
        alerts.coordinate(
            withNormalizedOffset: CGVector(dx: 0.9, dy: 0.5)
        ).tap()
        XCTAssertEqual(alerts.value as? String, "0")
        attachScreenshot(named: "task-13-weather-plant-toggle")
        app.buttons["tab.home"].tap()
        app.buttons["tab.collection"].tap()
        XCTAssertTrue(row.waitForExistence(timeout: 5))
        row.tap()
        let restoredAlerts = app.switches["weather.plant-alerts-enabled"]
        let alertsRemainDisabled = XCTNSPredicateExpectation(
            predicate: NSPredicate(format: "value == '0'"),
            object: restoredAlerts
        )
        XCTAssertEqual(
            XCTWaiter.wait(for: [alertsRemainDisabled], timeout: 5),
            .completed
        )
    }
}
