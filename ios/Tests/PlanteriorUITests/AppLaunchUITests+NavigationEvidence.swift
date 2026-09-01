import XCTest

@MainActor
extension AppLaunchUITests {
    func assertMilestonesHidden(in app: XCUIApplication) {
        XCTAssertFalse(app.buttons["settings.milestones"].exists)
        XCTAssertFalse(app.staticTexts["꾸미기 마일스톤"].exists)
    }

    func assertHomePaintedAndInteractiveBoundary(
        in app: XCUIApplication,
        homeScreen: XCUIElement,
        materialMinY: CGFloat
    ) {
        let homeControls = app.buttons.allElementsBoundByIndex.filter {
            $0.identifier.hasPrefix("home.")
        }
        XCTAssertFalse(homeControls.isEmpty)
        for control in homeControls {
            XCTAssertTrue(
                control.frame.maxY <= materialMinY
                    || control.frame.minY >= materialMinY,
                "\(control.identifier) must not straddle tab material"
            )
        }
        attachNavigationJSON(
            [
                "materialMinY": materialMinY,
                "homeScreenFrame": navigationFrame(homeScreen.frame),
                "homeControlFrames": homeControls.reduce(
                    into: [String: [String: CGFloat]]()
                ) {
                    $0[$1.identifier] = navigationFrame($1.frame)
                }
            ],
            named: "home-painted-boundary-diagnostic"
        )
        let attachment = XCTAttachment(screenshot: app.screenshot())
        attachment.name = "home-painted-boundary-diagnostic"
        attachment.lifetime = .keepAlways
        add(attachment)
    }

    private func navigationFrame(_ frame: CGRect) -> [String: CGFloat] {
        [
            "minX": frame.minX,
            "minY": frame.minY,
            "maxX": frame.maxX,
            "maxY": frame.maxY,
            "width": frame.width,
            "height": frame.height
        ]
    }

    private func attachNavigationJSON(_ value: Any, named name: String) {
        guard let data = try? JSONSerialization.data(
            withJSONObject: value,
            options: [.prettyPrinted, .sortedKeys]
        ) else {
            XCTFail("JSON evidence could not be encoded")
            return
        }
        let attachment = XCTAttachment(
            data: data,
            uniformTypeIdentifier: "public.json"
        )
        attachment.name = name
        attachment.lifetime = .keepAlways
        add(attachment)
    }
}
