import XCTest

@MainActor
extension SettingsDeletionUITests {
    func testNavigationOwnedTabBarKeepsSettingsRowsClearOfTabMaterial() {
        let app = figmaSettingsApp(accessibilitySize: true)
        app.launchEnvironment["QA_REDUCE_MOTION"] = "1"
        app.launchArguments += [
            "-AppleLanguages", "(ko)",
            "-AppleLocale", "ko_KR"
        ]
        app.launch()
        openFigmaSettings(in: app)

        let scroll = app.scrollViews["settings.screen"]
        let finalRow = app.buttons["settings.delete-account"]
        let tab = app.buttons["tab.settings"]
        XCTAssertTrue(finalRow.waitForExistence(timeout: 5))
        XCTAssertTrue(tab.exists)

        // Machine-consumed mirrors of PlanteriorSpacing.large/small. UI tests
        // intentionally do not link the app's design-system product.
        let separation: CGFloat = 16
        let materialTopInset: CGFloat = 8
        let tabBarFootprint: CGFloat = 62
        let materialMinY = tab.frame.minY - materialTopInset
        let clearanceBoundary = materialMinY - separation
        assertSettingsTabClearanceEpsilonContract()
        assertInitialRootRowsClearTabMaterial(in: app, materialMinY: materialMinY)

        let fullyClear = XCTNSPredicateExpectation(
            predicate: NSPredicate { object, _ in
                guard let row = object as? XCUIElement, row.exists else { return false }
                return SettingsTabClearance.contains(
                    maxY: row.frame.maxY,
                    boundary: clearanceBoundary
                )
            },
            object: finalRow
        )
        scrollToHittable(finalRow, in: scroll)
        scroll.swipeUp()
        XCTAssertEqual(XCTWaiter.wait(for: [fullyClear], timeout: 3), .completed)

        XCTAssertEqual(finalRow.label, "계정 삭제")
        XCTAssertFalse(finalRow.label.contains("\u{2026}"))
        XCTAssertGreaterThanOrEqual(finalRow.frame.height, 44)
        XCTAssertTrue(
            SettingsTabClearance.contains(
                maxY: finalRow.frame.maxY,
                boundary: clearanceBoundary
            )
        )
        XCTAssertFalse(finalRow.frame.intersects(tab.frame))
        attachSettingsTabClearanceEvidence(
            finalRowMaxY: finalRow.frame.maxY,
            materialMinY: materialMinY,
            separation: separation,
            tabBarFootprint: tabBarFootprint
        )
    }

    func attachSettingsTabClearanceEvidence(
        finalRowMaxY: CGFloat,
        materialMinY: CGFloat,
        separation: CGFloat,
        tabBarFootprint: CGFloat
    ) {
        attachJSON(
            [
                "owner": "tabStack.fixedBottomRegion",
                "tabBarFootprint": tabBarFootprint,
                "finalRowMaxY": finalRowMaxY,
                "materialMinY": materialMinY,
                "separation": separation
            ],
            named: "navigation-owned-tabbar-settings-ax5"
        )
        attachScreenshot(named: "settings-root-ax5-bottom-clearance")
    }

    func testQuietHoursAXBottomScrollExposesCompleteCopyAboveStickySave() {
        let app = figmaSettingsApp(accessibilitySize: true)
        app.launchEnvironment["QA_REDUCE_MOTION"] = "1"
        app.launchArguments += ["-AppleLanguages", "(ko)", "-AppleLocale", "ko_KR"]
        app.launch()
        openFigmaSettings(in: app)

        let quietHours = scrollQuietHoursRowAboveTabMaterial(in: app)
        quietHours.tap()
        assertOneSelectedSettingsTab(in: app)
        assertQuietHoursBottomScroll(in: app)
    }

    func assertQuietHoursBottomScroll(in app: XCUIApplication) {
        let scroll = app.scrollViews["quiet-hours.screen"]
        let save = app.buttons["quiet-hours.save"]
        XCTAssertTrue(scroll.waitForExistence(timeout: 5))
        XCTAssertTrue(save.waitForExistence(timeout: 5))
        let leading = app.staticTexts["quiet-hours.information.leading"]
        let trailing = app.staticTexts["quiet-hours.information.trailing"]
        let warning = app.staticTexts["quiet-hours.warning"]
        assertQuietHoursCopy(leading: leading, trailing: trailing)

        let leadingVisible = quietHoursVisibleExpectation(leading, scroll: scroll)
        let trailingVisible = quietHoursVisibleExpectation(trailing, scroll: scroll)
        XCTAssertEqual(
            XCTWaiter.wait(for: [leadingVisible, trailingVisible], timeout: 3), .completed
        )
        attachScreenshot(named: "quiet-hours-ax5-intro-complete")
        let warningVisible = quietHoursVisibleExpectation(warning, scroll: scroll)
        scroll.swipeUp()
        XCTAssertEqual(XCTWaiter.wait(for: [warningVisible], timeout: 3), .completed)
        attachScreenshot(named: "quiet-hours-ax5-warning-bottom")
        XCTAssertEqual(
            warning.label,
            "태풍, 한파, 폭염\u{00A0}등 식물 생존에 직접적 영향을 미치는 "
                + "기상 특보 및 재난 알림은 시간 설정과 관계없이 즉시 발송됩니다."
        )
        XCTAssertFalse(warning.label.contains("\u{2026}"))
        XCTAssertEqual(warning.descendants(matching: .any).count, 0)
        XCTAssertGreaterThanOrEqual(warning.frame.height, 44)
        XCTAssertLessThanOrEqual(warning.frame.maxY, save.frame.minY - 8)
        scrollToHittable(save, in: scroll)
        XCTAssertEqual(save.label, "저장하기")
        XCTAssertFalse(save.label.contains("\u{2026}"))
        XCTAssertGreaterThanOrEqual(save.frame.height, 44)
        XCTAssertTrue(scroll.frame.contains(save.frame))
        XCTAssertTrue(app.windows.element(boundBy: 0).frame.contains(save.frame))
        attachScreenshot(named: "quiet-hours-ax5-save-bottom")
    }

    func assertQuietHoursCopy(leading: XCUIElement, trailing: XCUIElement) {
        XCTAssertEqual(leading.label, "설정 완료 시 해당 시간 동안 물\u{00A0}주기, 영양제 주기 등")
        XCTAssertEqual(
            trailing.label,
            "일상적인 식물\u{00A0}관리\u{00A0}알림 및 푸시가 발송되지 않습니다."
        )
        XCTAssertFalse(leading.label.contains("\u{2026}"))
        XCTAssertFalse(trailing.label.contains("\u{2026}"))
    }

    func quietHoursVisibleExpectation(
        _ element: XCUIElement,
        scroll: XCUIElement
    ) -> XCTNSPredicateExpectation {
        XCTNSPredicateExpectation(
            predicate: NSPredicate { object, _ in
                guard let target = object as? XCUIElement, target.exists else { return false }
                return target.frame.minY >= scroll.frame.minY
                    && target.frame.maxY <= scroll.frame.maxY - 8
            },
            object: element
        )
    }

    func quietBackAndTitleDoNotIntersect(
        in app: XCUIApplication,
        title: XCUIElement
    ) -> Bool {
        !app.buttons["quiet-hours.back"].frame.intersects(title.frame)
    }

    func figmaSettingsApp(
        accountID: String? = nil,
        accessibilitySize: Bool = false
    ) -> XCUIApplication {
        let app = XCUIApplication()
        app.launchEnvironment["QA_SKIP_ONBOARDING"] = "1"
        app.launchEnvironment["QA_AUTHENTICATED"] = "1"
        app.launchEnvironment["QA_ACCOUNT_ID"] = accountID
            ?? "qa-settings-\(UUID().uuidString)"
        app.launchEnvironment["QA_AUTH_PROFILE_NAME"] = "민지"
        app.launchEnvironment["QA_AUTH_PROFILE_EMAIL"] = "minji@email.com"
        app.launchEnvironment["QA_WEATHER_AUTHORIZATION"] = "full"
        app.launchEnvironment["QA_WEATHER_MANUAL_REGION"] = "manual-seoul"
        app.launchEnvironment["QA_SETTINGS_LOCATION_TEXT"] =
            "서울특별시 강남구 역삼동"
        app.launchEnvironment["TZ"] = "Asia/Seoul"
        if accessibilitySize {
            app.launchEnvironment["QA_SETTINGS_SIZE_CATEGORY"] = "AX5"
            app.launchArguments += [
                "-UIPreferredContentSizeCategoryName",
                "UICTContentSizeCategoryAccessibilityXXXL"
            ]
        }
        return app
    }

    func openFigmaSettings(in app: XCUIApplication) {
        let settings = app.buttons["tab.settings"]
        XCTAssertTrue(settings.waitForExistence(timeout: 10))
        settings.tap()
        XCTAssertTrue(app.scrollViews["settings.screen"].waitForExistence(timeout: 5))
    }
}
