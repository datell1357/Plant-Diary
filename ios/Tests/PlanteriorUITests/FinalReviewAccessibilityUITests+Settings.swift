import XCTest

@MainActor
extension SettingsDeletionUITests {
    func testAX5RegionLabelKeepsAReservedIconColumn() {
        let app = figmaSettingsApp()
        app.launchEnvironment["QA_SETTINGS_SIZE_CATEGORY"] = "AX5"
        app.launchArguments += accessibilityArguments
        app.launch()
        openFigmaSettings(in: app)

        let region = app.buttons["settings.region.open"]
        scrollToHittable(region, in: app.scrollViews["settings.screen"])
        region.tap()
        let card = app.buttons["weather.use-current-location"]
        let label = app.staticTexts["현재 위치로 설정"]
        XCTAssertTrue(label.waitForExistence(timeout: 5))
        XCTAssertGreaterThanOrEqual(label.frame.minX, card.frame.minX + 56)
    }
}

let accessibilityArguments = [
    "-AppleLanguages", "(ko)",
    "-AppleLocale", "ko_KR",
    "-UIPreferredContentSizeCategoryName",
    "UICTContentSizeCategoryAccessibilityXXXL"
]

@MainActor
func scrollToHittable(
    _ element: XCUIElement,
    in scrollView: XCUIElement
) {
    for _ in 0 ..< 8 where !element.isHittable {
        scrollView.swipeUp()
    }
    XCTAssertTrue(element.isHittable)
}
