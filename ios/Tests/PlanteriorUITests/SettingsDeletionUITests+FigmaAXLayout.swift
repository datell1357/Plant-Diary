import XCTest

@MainActor
extension SettingsDeletionUITests {
    func testFigmaSettingsAndQuietHoursAtKoreanAX5ReduceMotion() {
        let app = figmaSettingsApp(accessibilitySize: true)
        app.launchEnvironment["QA_REDUCE_MOTION"] = "1"
        app.launchArguments += [
            "-AppleLanguages", "(ko)",
            "-AppleLocale", "ko_KR"
        ]
        app.launch()
        openFigmaSettings(in: app)

        let email = app.staticTexts["settings.profile.email"]
        XCTAssertTrue(email.exists)
        XCTAssertEqual(email.label, "minji@email.com")
        XCTAssertEqual(
            app.staticTexts.matching(identifier: "settings.profile.email").count,
            1,
            "the complete email must remain one accessibility value"
        )
        XCTAssertGreaterThanOrEqual(
            email.frame.width,
            300,
            "the visible email must reserve one readable atomic line"
        )
        attachScreenshot(named: "settings-korean-ax5-reduce-motion")

        let quietHours = openQuietHoursAtAX5(in: app)
        assertQuietHoursTimeControls(
            in: app,
            scroll: quietHours.scroll,
            title: quietHours.title
        )
        let save = assertQuietHoursAXSave(in: app, scroll: quietHours.scroll)
        let returned = XCTNSPredicateExpectation(
            predicate: NSPredicate(format: "exists == true"),
            object: app.buttons["settings.quiet-hours.open"]
        )
        save.tap()
        XCTAssertEqual(XCTWaiter.wait(for: [returned], timeout: 5), .completed)
    }

    private func openQuietHoursAtAX5(
        in app: XCUIApplication
    ) -> (scroll: XCUIElement, title: XCUIElement) {
        let quietHours = scrollQuietHoursRowAboveTabMaterial(in: app)
        quietHours.tap()
        assertOneSelectedSettingsTab(in: app)

        let scroll = app.scrollViews["quiet-hours.screen"]
        XCTAssertTrue(scroll.waitForExistence(timeout: 5))
        let title = app.staticTexts
            .matching(NSPredicate(format: "label == %@", "알림 금지 시간 설정"))
            .element(boundBy: 0)
        XCTAssertTrue(title.exists)
        XCTAssertGreaterThan(title.frame.height, 56)
        XCTAssertTrue(quietBackAndTitleDoNotIntersect(in: app, title: title))
        XCTAssertEqual(title.label, "알림 금지 시간 설정")
        XCTAssertFalse(title.label.contains("\u{2026}"))
        return (scroll, title)
    }

    private func assertQuietHoursTimeControls(
        in app: XCUIApplication,
        scroll: XCUIElement,
        title: XCUIElement
    ) {
        let enabled = app.switches["quiet-hours.enabled"]
        if enabled.value as? String != "1" {
            let changed = XCTNSPredicateExpectation(
                predicate: NSPredicate(format: "value == '1'"),
                object: enabled
            )
            enabled.tap()
            XCTAssertEqual(XCTWaiter.wait(for: [changed], timeout: 3), .completed)
        }
        for (identifier, label) in [
            ("quiet-hours.start", "시작 시간"),
            ("quiet-hours.end", "종료 시간")
        ] {
            let picker = app.datePickers[identifier]
            XCTAssertTrue(picker.exists)
            XCTAssertGreaterThanOrEqual(picker.frame.height, 44)
            XCTAssertTrue(app.staticTexts[label].exists)
            scrollAboveTabMaterial(picker, in: scroll, of: app)
            picker.tap()
            let dismiss = app.buttons.matching(identifier: "PopoverDismissRegion")
            XCTAssertTrue(dismiss.firstMatch.waitForExistence(timeout: 2))
            title.coordinate(
                withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5)
            ).tap()
        }
    }

    private func assertQuietHoursAXSave(
        in app: XCUIApplication,
        scroll: XCUIElement
    ) -> XCUIElement {
        let save = app.buttons["quiet-hours.save"]
        scrollToHittable(save, in: scroll)
        attachScreenshot(named: "quiet-hours-korean-ax5-reduce-motion")
        XCTAssertTrue(save.isHittable)
        XCTAssertGreaterThanOrEqual(save.frame.height, 44)
        XCTAssertTrue(scroll.frame.contains(save.frame))
        XCTAssertTrue(app.windows.element(boundBy: 0).frame.contains(save.frame))
        XCTAssertEqual(save.label, "저장하기")
        XCTAssertFalse(save.label.contains("\u{2026}"))
        attachJSON(
            [
                "saveFrameHeight": save.frame.height,
                "scrollFrameHeight": scroll.frame.height
            ],
            named: "quiet-hours-ax5-geometry"
        )
        return save
    }
}
