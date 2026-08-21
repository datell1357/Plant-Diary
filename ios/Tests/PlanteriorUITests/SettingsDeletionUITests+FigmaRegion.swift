import XCTest

@MainActor
extension SettingsDeletionUITests {
    /// Visual-matrix capture for `careSettings.region` under Korean AX5 +
    /// Reduce Motion. The default-type capture lives in `WeatherFlowUITests`,
    /// which taps `weather.open-region` on Home without scrolling; at AX5 that
    /// row sits below the fold, so this reaches the same screen through the
    /// Settings entry point and scrolls the control into view first.
    /// Assertions stay structural so no copy or pixel geometry is pinned.
    func testFigmaRegionSettingsAtKoreanAX5ReduceMotion() {
        let app = figmaSettingsApp()
        app.launchEnvironment["QA_SETTINGS_SIZE_CATEGORY"] = "AX5"
        app.launchEnvironment["QA_REDUCE_MOTION"] = "1"
        app.launchEnvironment["QA_WEATHER_MANUAL_REGION"] = "manual-seoul"
        app.launchArguments += [
            "-AppleLanguages", "(ko)",
            "-AppleLocale", "ko_KR"
        ]
        app.launch()
        openFigmaSettings(in: app)

        let openRegion = app.buttons["settings.region.open"]
        XCTAssertTrue(openRegion.waitForExistence(timeout: 10))
        // AX5 pushes the row below the fold; scroll until it is actionable
        // rather than sleeping and hoping the layout settles.
        let scroll = app.scrollViews["settings.screen"]
        var scrolls = 0
        while !openRegion.isHittable, scrolls < 6 {
            scroll.swipeUp()
            scrolls += 1
        }
        XCTAssertTrue(
            openRegion.isHittable,
            "settings.region.open must stay reachable at AX5"
        )
        openRegion.tap()

        XCTAssertTrue(
            app.scrollViews["region-settings.screen"]
                .waitForExistence(timeout: 10)
        )
        XCTAssertTrue(
            app.buttons["weather.use-current-location"]
                .waitForExistence(timeout: 5)
        )
        XCTAssertTrue(app.buttons["weather.region.back"].isHittable)
        attachScreenshot(named: "region-korean-ax5-reduce-motion")
    }
}
