import XCTest

@MainActor
extension SettingsDeletionUITests {
    func assertReferenceRootAnatomy(in app: XCUIApplication) {
        let wateringToggle = app.switches["settings.alerts.watering-enabled"]
        let weatherToggle = app.switches["settings.alerts.weather-enabled"]
        let quietHours = app.buttons["settings.quiet-hours.open"]
        let region = app.buttons["settings.region.open"]
        let back = app.buttons["settings.back"]
        let title = app.staticTexts
            .matching(NSPredicate(format: "label == %@", "설정"))
            .element(boundBy: 1)
        let body = app.scrollViews["settings.screen"]

        for toggle in [wateringToggle, weatherToggle] {
            XCTAssertTrue(toggle.exists)
            XCTAssertGreaterThanOrEqual(toggle.frame.width, 43.5)
            XCTAssertGreaterThanOrEqual(toggle.frame.height, 43.5)
        }
        XCTAssertEqual(quietHours.frame.height, 52, accuracy: 1)
        XCTAssertEqual(region.frame.height, 52, accuracy: 1)
        XCTAssertLessThan(quietHours.frame.maxY, region.frame.minY)
        let topOffset: CGFloat = app.frame.height == 874 ? 0 : -15
        XCTAssertTrue(back.isHittable)
        XCTAssertEqual(
            back.frame,
            CGRect(x: 16, y: 50 + topOffset, width: 44, height: 44),
            accuracy: 2
        )
        XCTAssertTrue(title.exists)
        XCTAssertFalse(back.frame.intersects(title.frame))
        XCTAssertEqual(body.frame.minY, 100 + topOffset, accuracy: 1)
        let settingsTab = app.buttons["tab.settings"]
        let materialMinY = app.frame.maxY - 62 - 34
        XCTAssertTrue(settingsTab.isSelected)
        for control in [wateringToggle, weatherToggle, quietHours, region] {
            XCTAssertTrue(
                control.frame.maxY <= materialMinY
                    || control.frame.minY >= materialMinY,
                "\(control.identifier) must not straddle tab material"
            )
        }
        attachAXHierarchy(
            named: "settings-default-switch-targets",
            elements: [
                ("watering", wateringToggle),
                ("weather", weatherToggle)
            ]
        )
    }

    func assertInitialRootRowsClearTabMaterial(
        in app: XCUIApplication,
        materialMinY: CGFloat
    ) {
        let scroll = app.scrollViews["settings.screen"]
        let boundaryRows = [
            ("watering", app.switches["settings.alerts.watering-enabled"]),
            ("weather", app.switches["settings.alerts.weather-enabled"]),
            ("quiet-hours", app.buttons["settings.quiet-hours.open"])
        ]
        // A boundary row may extend beneath the safe-area-owned tab material,
        // but it must expose one complete 44pt target before clipping begins.
        let boundaryClear = XCTNSPredicateExpectation(
            predicate: NSPredicate { _, _ in
                boundaryRows.allSatisfy { _, row in
                    let visibleTargetMaxY = min(
                        row.frame.maxY,
                        row.frame.minY + 44
                    )
                    return SettingsTabClearance.contains(
                        maxY: visibleTargetMaxY,
                        boundary: materialMinY
                    )
                }
            },
            object: scroll
        )
        let outcome = XCTWaiter.wait(for: [boundaryClear], timeout: 3)
        attachJSON(
            [
                "materialMinY": materialMinY,
                "scrollMaxY": scroll.frame.maxY,
                "rows": boundaryRows.reduce(into: [String: [String: CGFloat]]()) {
                    $0[$1.0] = [
                        "minY": $1.1.frame.minY,
                        "maxY": $1.1.frame.maxY,
                        "exists": $1.1.exists ? 1 : 0
                    ]
                }
            ],
            named: "settings-ax5-initial-painted-bounds"
        )
        attachScreenshot(named: "settings-root-ax5-initial-viewport")
        XCTAssertEqual(outcome, .completed)
    }

    func assertReferenceRegionAnatomy(in app: XCUIApplication) {
        let firstRow = app.buttons["weather.region-result.manual-seoul"]
        let secondRow = app.buttons["weather.region-result.manual-busan"]
        let topOffset: CGFloat = app.frame.height == 874 ? 0 : -15
        let contentWidth = app.frame.width - 40

        XCTAssertEqual(
            firstRow.frame,
            CGRect(x: 20, y: 299 + topOffset, width: contentWidth, height: 52),
            accuracy: 1
        )
        XCTAssertEqual(
            secondRow.frame,
            CGRect(x: 20, y: 351 + topOffset, width: contentWidth, height: 52),
            accuracy: 1
        )
    }

    func assertReferenceQuietHoursAnatomy(in app: XCUIApplication) {
        let toggle = app.switches["quiet-hours.enabled"]
        let start = app.datePickers["quiet-hours.start"]
        let end = app.datePickers["quiet-hours.end"]
        let body = app.scrollViews["quiet-hours.screen"]
        let warning = app.staticTexts["quiet-hours.warning"]
        let save = app.buttons["quiet-hours.save"]
        let title = app.staticTexts
            .matching(NSPredicate(format: "label == %@", "알림 금지 시간 설정"))
            .firstMatch

        XCTAssertTrue(toggle.exists)
        XCTAssertGreaterThanOrEqual(toggle.frame.width, 44)
        XCTAssertGreaterThanOrEqual(toggle.frame.height, 44)
        for picker in [start, end] {
            XCTAssertTrue(picker.exists)
            XCTAssertTrue(picker.isHittable)
        }
        XCTAssertLessThanOrEqual(start.frame.maxY, end.frame.minY)
        let topOffset: CGFloat = app.frame.height == 874 ? 0 : -15
        let contentWidth = app.frame.width - 40
        XCTAssertTrue(title.exists)
        XCTAssertTrue(quietBackAndTitleDoNotIntersect(in: app, title: title))
        XCTAssertEqual(body.frame.minY, 100 + topOffset, accuracy: 1)
        let settingsTab = app.buttons["tab.settings"]
        let materialMinY = settingsTab.frame.minY - 8
        XCTAssertTrue(settingsTab.isSelected)
        XCTAssertEqual(app.buttons.matching(identifier: "tab.settings").count, 1)
        XCTAssertLessThanOrEqual(body.frame.maxY, materialMinY)
        XCTAssertEqual(
            warning.frame,
            CGRect(x: 20, y: 397 + topOffset, width: contentWidth, height: 80),
            accuracy: 1
        )
        XCTAssertEqual(save.frame.width, contentWidth, accuracy: 1)
        XCTAssertEqual(save.frame.height, 48, accuracy: 1)
        XCTAssertLessThanOrEqual(save.frame.maxY, materialMinY)
        attachAXHierarchy(
            named: "quiet-hours-default-switch-target",
            elements: [("enabled", toggle)]
        )
    }

    func assertQuietHoursPersistence(in app: XCUIApplication) {
        let toggle = app.switches["quiet-hours.enabled"]
        let start = app.datePickers["quiet-hours.start"]
        let end = app.datePickers["quiet-hours.end"]
        let save = app.buttons["quiet-hours.save"]
        toggle.tap()
        XCTAssertTrue(start.isEnabled)
        XCTAssertTrue(end.isEnabled)
        for picker in [start, end] {
            picker.tap()
            let dismiss = app.buttons["PopoverDismissRegion"]
            XCTAssertTrue(dismiss.waitForExistence(timeout: 2))
            dismiss.tap()
        }
        let returned = XCTNSPredicateExpectation(
            predicate: NSPredicate(format: "exists == true"),
            object: app.buttons["settings.quiet-hours.open"]
        )
        XCTAssertTrue(save.isHittable)
        save.tap()
        XCTAssertEqual(XCTWaiter.wait(for: [returned], timeout: 5), .completed)
        app.buttons["settings.quiet-hours.open"].tap()
        XCTAssertEqual(app.switches["quiet-hours.enabled"].value as? String, "1")
        XCTAssertTrue(app.datePickers["quiet-hours.start"].isEnabled)
        XCTAssertTrue(app.datePickers["quiet-hours.end"].isEnabled)
    }
}

private func XCTAssertEqual(
    _ actual: CGRect,
    _ expected: CGRect,
    accuracy: CGFloat,
    file: StaticString = #filePath,
    line: UInt = #line
) {
    XCTAssertEqual(actual.minX, expected.minX, accuracy: accuracy, file: file, line: line)
    XCTAssertEqual(actual.minY, expected.minY, accuracy: accuracy, file: file, line: line)
    XCTAssertEqual(actual.width, expected.width, accuracy: accuracy, file: file, line: line)
    XCTAssertEqual(actual.height, expected.height, accuracy: accuracy, file: file, line: line)
}
