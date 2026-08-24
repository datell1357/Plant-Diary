import XCTest

extension WeatherFlowUITests {
    func weatherApp() -> XCUIApplication {
        let app = XCUIApplication()
        app.launchEnvironment["QA_SKIP_ONBOARDING"] = "1"
        app.launchEnvironment["QA_AUTHENTICATED"] = "1"
        app.launchEnvironment["QA_COLLECTION_FIXTURE"] = "1"
        app.launchEnvironment["QA_RESET_WEATHER"] = "1"
        app.launchEnvironment["QA_WATERING_TODAY"] = "2026-08-11"
        return app
    }

    func scrollToWeatherControl(
        _ control: XCUIElement,
        in app: XCUIApplication
    ) {
        XCTAssertTrue(control.waitForExistence(timeout: 5))
        let scroll = app.scrollViews["home.screen"]
        for _ in 0 ..< 6 where !control.isHittable {
            scroll.swipeUp()
        }
        XCTAssertTrue(control.isHittable)
    }

    func attachScreenshot(named name: String) {
        let attachment = XCTAttachment(
            screenshot: XCUIScreen.main.screenshot()
        )
        attachment.name = name
        attachment.lifetime = .keepAlways
        add(attachment)
    }
}
