import XCTest

@MainActor
extension SettingsDeletionUITests {
    func testFigmaRegionUsesDeterministicSeoulStateAndPersistsSelection() {
        let app = figmaSettingsApp()
        app.launchArguments += [
            "-AppleLanguages", "(ko)",
            "-AppleLocale", "ko_KR"
        ]
        app.launch()
        openFigmaSettings(in: app)

        app.buttons["settings.region.open"].tap()
        XCTAssertTrue(
            app.scrollViews["region-settings.screen"]
                .waitForExistence(timeout: 5)
        )
        XCTAssertEqual(
            app.staticTexts["weather.current-location-text"].label,
            "서울특별시 강남구 역삼동"
        )
        XCTAssertEqual(
            app.buttons["weather.region-result.manual-seoul"].value as? String,
            "선택됨"
        )
        let back = app.buttons["weather.region.back"]
        let topBar = app.otherElements["region-settings.top-bar"]
        let body = app.scrollViews["region-settings.screen"]
        XCTAssertTrue(back.isHittable)
        XCTAssertFalse(app.buttons["weather.region.save"].exists)
        XCTAssertEqual(back.frame.minX, 16, accuracy: 1)
        XCTAssertEqual(back.frame.minY, 50, accuracy: 2)
        XCTAssertEqual(back.frame.width, 44, accuracy: 1)
        XCTAssertEqual(back.frame.height, 44, accuracy: 1)
        XCTAssertEqual(topBar.frame.minY, 44, accuracy: 1)
        XCTAssertEqual(topBar.frame.height, 56, accuracy: 1)
        XCTAssertEqual(body.frame.minY, 100, accuracy: 1)
        XCTAssertEqual(body.frame.maxY, 874, accuracy: 1)
        assertReferenceRegionAnatomy(in: app)
        attachScreenshot(named: "region-402x874-light")

        app.buttons["weather.region-result.manual-haeundae"].tap()
        app.buttons["weather.region.back"].tap()
        XCTAssertTrue(
            app.buttons["settings.region.open"].waitForExistence(timeout: 5)
        )
        XCTAssertTrue(app.buttons["settings.region.open"].label.contains("부산광역시"))
        app.buttons["settings.region.open"].tap()
        XCTAssertEqual(
            app.buttons["weather.region-result.manual-haeundae"].value as? String,
            "선택됨"
        )
    }

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
