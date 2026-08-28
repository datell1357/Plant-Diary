import XCTest

enum SettingsTabClearance {
    static let subpixelEpsilon: CGFloat = 0.001

    static func contains(maxY: CGFloat, boundary: CGFloat) -> Bool {
        maxY <= boundary + subpixelEpsilon
    }
}

@MainActor
extension XCTestCase {
    func assertSettingsTabClearanceEpsilonContract() {
        XCTAssertTrue(SettingsTabClearance.contains(maxY: 762.0000000000001, boundary: 762))
        XCTAssertFalse(SettingsTabClearance.contains(maxY: 762.1, boundary: 762))
    }

    func scrollQuietHoursRowAboveTabMaterial(
        in app: XCUIApplication
    ) -> XCUIElement {
        let quietHours = app.buttons["settings.quiet-hours.open"]
        let settingsScroll = app.scrollViews["settings.screen"]
        let settingsTab = app.buttons["tab.settings"]
        XCTAssertTrue(quietHours.waitForExistence(timeout: 5))
        XCTAssertTrue(settingsScroll.exists)
        XCTAssertTrue(settingsTab.exists)

        let materialMinY = settingsTab.frame.minY - 8
        let fullyClear = XCTNSPredicateExpectation(
            predicate: NSPredicate { object, _ in
                guard let row = object as? XCUIElement, row.exists else {
                    return false
                }
                return row.frame.maxY <= materialMinY - 16
            },
            object: quietHours
        )
        settingsScroll.swipeUp()
        XCTAssertEqual(
            XCTWaiter.wait(for: [fullyClear], timeout: 5),
            .completed
        )
        XCTAssertGreaterThanOrEqual(quietHours.frame.height, 44)
        XCTAssertEqual(quietHours.label, "알림 금지 시간 설정, 없음")
        XCTAssertFalse(quietHours.label.contains("\u{2026}"))
        XCTAssertFalse(quietHours.frame.intersects(settingsTab.frame))
        return quietHours
    }

    /// Scrolls `element` until it clears the persistent tab-bar material by at least 16pt.
    ///
    /// Geometry is the reliable gate here: XCUITest can report a control as hittable while
    /// its synthesized activation point still lands inside the fixed tab material.
    func scrollAboveTabMaterial(
        _ element: XCUIElement,
        in scrollView: XCUIElement,
        of app: XCUIApplication
    ) {
        let tabMaterialTop = app.buttons["tab.camera"]
        XCTAssertTrue(tabMaterialTop.exists)
        let clearanceY = tabMaterialTop.frame.minY - 16
        let fullyClear = XCTNSPredicateExpectation(
            predicate: NSPredicate { object, _ in
                guard let target = object as? XCUIElement, target.exists else {
                    return false
                }
                return target.frame.maxY <= clearanceY
            },
            object: element
        )
        for _ in 0 ..< 8 where element.frame.maxY > clearanceY {
            scrollView.swipeUp()
        }
        XCTAssertEqual(
            XCTWaiter.wait(for: [fullyClear], timeout: 5),
            .completed
        )
        XCTAssertTrue(element.isHittable)
    }

    func assertOneSelectedSettingsTab(in app: XCUIApplication) {
        assertSinglePersistentTabBar(in: app, selected: "tab.settings")
    }
}
