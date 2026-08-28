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
        let body = app.scrollViews["region-settings.screen"]
        let titles = app.staticTexts.matching(identifier: "관리 지역 설정")
        let title = titles.allElementsBoundByIndex.first {
            $0.frame.maxY <= body.frame.minY
        } ?? titles.firstMatch
        let topOffset: CGFloat = app.frame.height == 874 ? 0 : -15
        XCTAssertTrue(back.isHittable)
        XCTAssertFalse(app.buttons["weather.region.save"].exists)
        XCTAssertEqual(back.frame.minX, 16, accuracy: 1)
        XCTAssertEqual(back.frame.minY, 50 + topOffset, accuracy: 2)
        XCTAssertEqual(back.frame.width, 44, accuracy: 1)
        XCTAssertEqual(back.frame.height, 44, accuracy: 1)
        XCTAssertTrue(title.exists)
        XCTAssertFalse(back.frame.intersects(title.frame))
        XCTAssertEqual(body.frame.minY, 100 + topOffset, accuracy: 1)
        XCTAssertEqual(body.frame.maxY, app.frame.height, accuracy: 1)
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
        let app = figmaSettingsApp(accessibilitySize: true)
        app.launchEnvironment["QA_REDUCE_MOTION"] = "1"
        app.launchEnvironment["QA_WEATHER_MANUAL_REGION"] = "manual-seoul"
        app.launchArguments += [
            "-AppleLanguages", "(ko)",
            "-AppleLocale", "ko_KR"
        ]
        app.launch()
        openAX5RegionSettings(in: app)

        let title = app.staticTexts
            .matching(NSPredicate(format: "label == %@", "관리 지역 설정"))
            .element(boundBy: 0)
        XCTAssertTrue(title.exists)
        XCTAssertGreaterThan(
            title.frame.height,
            56,
            "the AX5 Region title must use multiple lines"
        )
        XCTAssertEqual(title.label, "관리 지역 설정")
        XCTAssertFalse(title.label.contains("\u{2026}"))
        assertRegionHeaderReservesBackControlColumn(in: app, title: title)

        let firstRow = assertRegionAX5RowsKeepCompleteNames(in: app)
        assertRegionFirstRowClearsHeaderBand(in: app, title: title)
        attachJSON(
            [
                "firstRowHeight": firstRow.frame.height,
                "titleFrameHeight": title.frame.height,
                "titleFrameWidth": title.frame.width
            ],
            named: "region-ax5-geometry"
        )
        scrollToHittable(
            app.buttons["weather.region-result.manual-haeundae"],
            in: app.scrollViews["region-settings.screen"]
        )
        attachScreenshot(named: "region-korean-ax5-reduce-motion")
    }
}
