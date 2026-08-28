import XCTest

@MainActor
extension SettingsDeletionUITests {
    func testSettingsAndQuietHoursExposeOneSwitchStopPerControl() {
        let app = figmaSettingsApp(accessibilitySize: true)
        app.launchArguments += ["-AppleLanguages", "(ko)", "-AppleLocale", "ko_KR"]
        app.launch()
        openFigmaSettings(in: app)

        assertSettingsRootSwitchSemantics(in: app)
        openAndAssertQuietHoursSwitchSemantics(in: app)
    }

    private func assertSettingsRootSwitchSemantics(in app: XCUIApplication) {
        let watering = app.switches["settings.alerts.watering-enabled"]
        let weather = app.switches["settings.alerts.weather-enabled"]
        assertNativeSwitch(
            watering,
            identifier: "settings.alerts.watering-enabled",
            title: "물 주기 알림",
            value: "1",
            in: app
        )
        assertNativeSwitch(
            weather,
            identifier: "settings.alerts.weather-enabled",
            title: "날씨 알림",
            value: "1",
            in: app
        )
        assertAXTraversal(
            in: app,
            isExactly: [
                "settings.alerts.watering-enabled",
                "settings.alerts.weather-enabled",
                "settings.quiet-hours.open"
            ]
        )
        attachAXHierarchy(
            named: "settings-root-switch-ax5-order",
            elements: [
                ("watering", watering),
                ("weather", weather),
                ("quiet-hours", app.buttons["settings.quiet-hours.open"])
            ]
        )
    }

    private func openAndAssertQuietHoursSwitchSemantics(in app: XCUIApplication) {
        let quiet = app.buttons["settings.quiet-hours.open"]
        app.scrollViews["settings.screen"].swipeUp()
        XCTAssertTrue(quiet.waitForExistence(timeout: 5))
        quiet.tap()

        let enabled = app.switches["quiet-hours.enabled"]
        let start = app.datePickers["quiet-hours.start"]
        let end = app.datePickers["quiet-hours.end"]

        assertNativeSwitch(
            enabled,
            identifier: "quiet-hours.enabled",
            title: "알림 금지 시간 사용",
            value: "0",
            in: app
        )
        XCTAssertFalse(start.isEnabled)
        XCTAssertFalse(end.isEnabled)
        XCTAssertEqual(start.value as? String, "22:00:00")
        XCTAssertEqual(end.value as? String, "07:00:00")
        assertQuietHoursPaintedLabels(in: app)
        assertQuietHoursTraversal(in: app)
        XCTAssertEqual(app.datePickers.matching(identifier: start.identifier).count, 1)
        XCTAssertEqual(app.datePickers.matching(identifier: end.identifier).count, 1)
        assertNativeSwitchAction(enabled, from: "0", to: "1")
        XCTAssertTrue(start.isEnabled)
        XCTAssertTrue(end.isEnabled)

        let scroll = app.scrollViews["quiet-hours.screen"]
        let save = app.buttons["quiet-hours.save"]
        scrollToHittable(save, in: scroll)
        XCTAssertEqual(app.buttons.matching(identifier: save.identifier).count, 1)
        XCTAssertEqual(save.label, "저장하기")
        XCTAssertFalse(save.label.contains("\u{2026}"))
        XCTAssertGreaterThanOrEqual(save.frame.height, 44)
        XCTAssertTrue(scroll.frame.contains(save.frame))
    }

    private func assertQuietHoursPaintedLabels(in app: XCUIApplication) {
        assertCompletePaintedLabel(
            app.staticTexts["quiet-hours.start.label"],
            expected: "시작 시간"
        )
        assertCompletePaintedLabel(
            app.staticTexts["quiet-hours.end.label"],
            expected: "종료 시간"
        )
        assertCompletePaintedLabel(
            app.staticTexts["quiet-hours.information.leading"],
            expected: "설정 완료 시 해당 시간 동안 물\u{00A0}주기, 영양제 주기 등"
        )
        assertCompletePaintedLabel(
            app.staticTexts["quiet-hours.information.trailing"],
            expected: "일상적인 식물\u{00A0}관리\u{00A0}알림 및 푸시가 발송되지 않습니다."
        )
        let warning = app.staticTexts["quiet-hours.warning"]
        assertCompletePaintedLabel(
            warning,
            expected: "태풍, 한파, 폭염\u{00A0}등 식물 생존에 직접적 영향을 미치는 "
                + "기상 특보 및 재난 알림은 시간 설정과 관계없이 즉시 발송됩니다."
        )
        XCTAssertEqual(app.staticTexts.matching(identifier: warning.identifier).count, 1)
        XCTAssertEqual(warning.descendants(matching: .any).count, 0)
        let warningCopies = app.descendants(matching: .any)
            .matching(identifier: "quiet-hours.warning-copy")
        XCTAssertEqual(warningCopies.count, 0)
    }

    private func assertQuietHoursTraversal(in app: XCUIApplication) {
        let identifiers = [
            "quiet-hours.enabled",
            "quiet-hours.information.leading",
            "quiet-hours.information.trailing",
            "quiet-hours.start.label",
            "quiet-hours.start",
            "quiet-hours.end.label",
            "quiet-hours.end",
            "quiet-hours.warning",
            "quiet-hours.save"
        ]
        assertAXTraversal(in: app, isExactly: identifiers)
        attachAXHierarchy(
            named: "settings-quiet-hours-ax5-order",
            elements: identifiers.map { ($0, app.descendants(matching: .any)[$0]) }
        )
    }

    private func assertNativeSwitch(
        _ control: XCUIElement,
        identifier: String,
        title: String,
        value: String,
        in app: XCUIApplication,
        file: StaticString = #filePath,
        line: UInt = #line
    ) {
        XCTAssertEqual(
            app.switches.matching(identifier: identifier).count,
            1,
            file: file,
            line: line
        )
        XCTAssertEqual(control.label, title, file: file, line: line)
        XCTAssertEqual(control.value as? String, value, file: file, line: line)
        XCTAssertTrue(control.isEnabled, file: file, line: line)
        XCTAssertTrue(control.isHittable, file: file, line: line)
        XCTAssertGreaterThanOrEqual(control.frame.height, 44, file: file, line: line)
        XCTAssertFalse(control.label.contains("\u{2026}"), file: file, line: line)
        let paintedLabels = app.staticTexts.matching(
            NSPredicate(format: "label == %@", title)
        )
        XCTAssertEqual(paintedLabels.count, 1, file: file, line: line)
    }

    private func assertNativeSwitchAction(
        _ control: XCUIElement,
        from initialValue: String,
        to changedValue: String,
        file: StaticString = #filePath,
        line: UInt = #line
    ) {
        XCTAssertEqual(control.value as? String, initialValue, file: file, line: line)
        let changed = XCTNSPredicateExpectation(
            predicate: NSPredicate(format: "value == %@", changedValue),
            object: control
        )
        control.tap()
        XCTAssertEqual(
            XCTWaiter.wait(for: [changed], timeout: 5),
            .completed,
            "the native switch action must update its current value",
            file: file,
            line: line
        )
    }

    private func assertCompletePaintedLabel(
        _ element: XCUIElement,
        expected: String,
        file: StaticString = #filePath,
        line: UInt = #line
    ) {
        XCTAssertTrue(element.exists, file: file, line: line)
        XCTAssertEqual(element.label, expected, file: file, line: line)
        XCTAssertFalse(element.label.contains("\u{2026}"), file: file, line: line)
        XCTAssertGreaterThan(element.frame.width, 0, file: file, line: line)
        XCTAssertGreaterThanOrEqual(element.frame.height, 44, file: file, line: line)
    }
}
