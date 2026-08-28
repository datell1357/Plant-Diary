import XCTest

@MainActor
extension AppLaunchUITests {
    func openMilestonesAfterRecordingHitGeometry(in app: XCUIApplication) {
        let context = MilestoneHitContext(app: app)
        recordMilestoneHitDiagnostic(context, phase: "before-scroll")

        // Machine-consumed mirrors of the 62pt bar and 34pt native safe area.
        let materialMinY = context.app.frame.maxY - 96
        let fullyClear = XCTNSPredicateExpectation(
            predicate: NSPredicate { object, _ in
                guard let button = object as? XCUIElement else { return false }
                return button.frame.maxY <= materialMinY - 16
                    && button.frame.minY >= context.scroll.frame.minY
            },
            object: context.milestones
        )
        context.scroll.swipeUp()
        XCTAssertEqual(
            XCTWaiter.wait(for: [fullyClear], timeout: 5),
            .completed
        )
        recordMilestoneHitDiagnostic(context, phase: "after-scroll")

        context.milestones.tap()
        let destination = app.scrollViews["milestones.screen"]
        XCTAssertTrue(destination.waitForExistence(timeout: 5))
        recordMilestoneDestinationDiagnostic(
            context,
            destination: destination
        )
        let selectedTabs = ["tab.home", "tab.collection", "tab.storage", "tab.settings"]
            .filter { app.buttons[$0].isSelected }
        XCTAssertEqual(selectedTabs, ["tab.settings"])
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

    private func recordMilestoneHitDiagnostic(
        _ context: MilestoneHitContext,
        phase: String,
        destinationExists: Bool = false
    ) {
        let materialMinY = context.app.frame.maxY - 96
        let activationPoint = context.milestones.coordinate(
            withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5)
        ).screenPoint
        attachNavigationJSON(
            [
                "phase": phase,
                "milestones": navigationElement(
                    context.milestones,
                    activationPoint: activationPoint
                ),
                "settingsScrollFrame": navigationFrame(context.scroll.frame),
                "tabHome": navigationElement(context.homeTab),
                "tabSettings": navigationElement(context.settingsTab),
                "materialFrame": navigationFrame(
                    CGRect(
                        x: context.app.frame.minX,
                        y: materialMinY,
                        width: context.app.frame.width,
                        height: context.app.frame.maxY - materialMinY
                    )
                ),
                "destinationExists": destinationExists
            ],
            named: "milestone-hit-\(phase)"
        )
    }

    private func recordMilestoneDestinationDiagnostic(
        _ context: MilestoneHitContext,
        destination: XCUIElement
    ) {
        attachNavigationJSON(
            [
                "phase": "after-tap",
                "destinationExists": destination.exists,
                "destinationFrame": navigationFrame(destination.frame),
                "tabHome": navigationElement(context.homeTab),
                "tabSettings": navigationElement(context.settingsTab)
            ],
            named: "milestone-hit-after-tap"
        )
    }

    private func navigationElement(
        _ element: XCUIElement,
        activationPoint: CGPoint? = nil
    ) -> [String: Any] {
        let exists = element.exists
        var result: [String: Any] = [
            "frame": navigationFrame(element.frame),
            "exists": exists,
            "isHittable": exists && element.isHittable,
            "isSelected": exists && element.isSelected
        ]
        if let activationPoint {
            result["activationPoint"] = [
                "x": activationPoint.x,
                "y": activationPoint.y
            ]
        }
        return result
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

@MainActor
private struct MilestoneHitContext {
    let app: XCUIApplication
    let milestones: XCUIElement
    let scroll: XCUIElement
    let settingsTab: XCUIElement
    let homeTab: XCUIElement

    init(app: XCUIApplication) {
        self.app = app
        milestones = app.buttons["settings.milestones"]
        scroll = app.scrollViews["settings.screen"]
        settingsTab = app.buttons["tab.settings"]
        homeTab = app.buttons["tab.home"]
    }
}
