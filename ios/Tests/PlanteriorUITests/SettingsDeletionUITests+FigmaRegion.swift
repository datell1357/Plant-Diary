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
        XCTAssertTrue(back.isHittable)
        XCTAssertFalse(app.buttons["weather.region.save"].exists)
        XCTAssertEqual(back.frame.minX, 16, accuracy: 1)
        XCTAssertEqual(back.frame.minY, 50, accuracy: 2)
        XCTAssertEqual(back.frame.width, 44, accuracy: 1)
        XCTAssertEqual(back.frame.height, 44, accuracy: 1)
        XCTAssertTrue(title.exists)
        XCTAssertFalse(back.frame.intersects(title.frame))
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
        let app = figmaSettingsApp(accessibilitySize: true)
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
        let title = app.staticTexts
            .matching(NSPredicate(format: "label == %@", "관리 지역 설정"))
            .element(boundBy: 0)
        XCTAssertTrue(title.exists)
        XCTAssertGreaterThan(
            title.frame.height,
            56,
            "the AX5 Region title must use multiple lines"
        )
        XCTAssertFalse(
            app.buttons["weather.region.back"].frame.intersects(title.frame)
        )
        XCTAssertEqual(title.label, "관리 지역 설정")
        XCTAssertFalse(title.label.contains("\u{2026}"))
        XCTAssertTrue(app.buttons["weather.region.back"].isHittable)

        let firstRow = app.buttons["weather.region-result.manual-seoul"]
        let secondRow = app.buttons["weather.region-result.manual-busan"]
        let thirdRow = app.buttons["weather.region-result.manual-haeundae"]
        let firstName = app.staticTexts["서울특별시 강남구"]
        let secondName = app.staticTexts["경기도 성남시 분당구"]
        let thirdName = app.staticTexts["부산광역시 해운대구"]
        let status = app.staticTexts["기준 지역"]
        for row in [firstRow, secondRow, thirdRow] {
            XCTAssertTrue(row.exists)
            XCTAssertGreaterThan(
                row.frame.height,
                52,
                "AX5 Region rows must expand beyond their default Large height"
            )
        }
        XCTAssertLessThanOrEqual(firstRow.frame.maxY, secondRow.frame.minY)
        XCTAssertLessThanOrEqual(secondRow.frame.maxY, thirdRow.frame.minY)
        for (row, name, expectedName) in [
            (firstRow, firstName, "서울특별시 강남구"),
            (secondRow, secondName, "경기도 성남시 분당구"),
            (thirdRow, thirdName, "부산광역시 해운대구")
        ] {
            XCTAssertTrue(name.exists)
            XCTAssertEqual(name.label, expectedName)
            XCTAssertFalse(name.label.contains("\u{2026}"))
            XCTAssertTrue(
                row.frame.contains(name.frame),
                "the complete Region name must remain inside its AX5 row"
            )
        }
        XCTAssertTrue(status.exists)
        XCTAssertEqual(status.label, "기준 지역")
        XCTAssertFalse(status.label.contains("\u{2026}"))
        XCTAssertTrue(firstRow.frame.contains(status.frame))
        attachJSON(
            [
                "firstRowHeight": firstRow.frame.height,
                "secondRowHeight": secondRow.frame.height,
                "thirdRowHeight": thirdRow.frame.height,
                "titleFrameHeight": title.frame.height,
                "titleFrameWidth": title.frame.width
            ],
            named: "region-ax5-geometry"
        )
        scrollToHittable(
            thirdRow,
            in: app.scrollViews["region-settings.screen"]
        )
        attachScreenshot(named: "region-korean-ax5-reduce-motion")
    }
}
