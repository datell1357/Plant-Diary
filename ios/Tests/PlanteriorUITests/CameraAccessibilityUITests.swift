import XCTest

@MainActor
final class CameraAccessibilityUITests: XCTestCase {
    func testKoreanAX5CameraStateKeepsCompleteLabelsAndReachableControls() throws {
        let app = launchAX5Camera()
        defer { attachScreenshot() }

        assertAX5CameraFrames(in: app)
        try app.performAccessibilityAudit(
            for: [
                .contrast,
                .elementDetection,
                .hitRegion,
                .sufficientElementDescription,
                .textClipped,
                .trait
            ]
        )
    }

    private func launchAX5Camera() -> XCUIApplication {
        let app = XCUIApplication()
        app.launchEnvironment["QA_SKIP_ONBOARDING"] = "1"
        app.launchEnvironment["QA_AUTHENTICATED"] = "1"
        app.launchEnvironment["QA_CAMERA_STATIC_FIXTURE"] = "1"
        app.launchArguments += [
            "-AppleLanguages", "(ko)",
            "-AppleLocale", "ko_KR",
            "-UIPreferredContentSizeCategoryName",
            "UICTContentSizeCategoryAccessibilityXXXL"
        ]
        app.launch()

        let cameraAction = app.buttons["tab.camera"]
        XCTAssertTrue(cameraAction.waitForExistence(timeout: 10))
        cameraAction.tap()
        XCTAssertTrue(app.otherElements["capture.camera"].waitForExistence(timeout: 10))
        return app
    }

    private func assertAX5CameraFrames(in app: XCUIApplication) {
        let screenFrame = app.windows.firstMatch.frame
        let cameraSurface = app.otherElements["capture.camera"].frame
        XCTAssertEqual(cameraSurface.minX, screenFrame.minX, accuracy: 1)
        XCTAssertEqual(cameraSurface.minY, screenFrame.minY, accuracy: 1)
        XCTAssertEqual(cameraSurface.width, screenFrame.width, accuracy: 1)
        XCTAssertEqual(cameraSurface.height, screenFrame.height, accuracy: 1)

        let hint = app.staticTexts["capture.hint"]
        let close = app.buttons["capture.close"]
        let viewport = app.images["capture.viewport"]
        let library = app.buttons["capture.library"]
        let libraryLabel = app.staticTexts["capture.library.label"]
        let shutter = app.buttons["capture.shutter"]
        let flash = app.buttons["capture.flash"]
        let flashLabel = app.staticTexts["capture.flash.label"]

        XCTAssertEqual(hint.label, "식물을 프레임 안에 맞춰주세요")
        XCTAssertEqual(libraryLabel.label, "사진 보관함")
        XCTAssertEqual(flashLabel.label, "플래시")
        XCTAssertTrue(viewport.exists, "the explicit QA route must use the camera fixture")
        XCTAssertFalse(app.otherElements["capture.viewport.live"].exists)
        XCTAssertGreaterThan(
            libraryLabel.frame.height,
            50,
            "the rendered label must use the effective AX5 size, not the old AX1 cap"
        )

        for control in [close, library, shutter, flash] {
            XCTAssertTrue(control.isHittable)
            XCTAssertGreaterThanOrEqual(control.frame.width.rounded(), 44)
            XCTAssertGreaterThanOrEqual(control.frame.height.rounded(), 44)
            XCTAssertTrue(screenFrame.contains(control.frame))
        }
        for element in [hint, viewport, libraryLabel, flashLabel] {
            XCTAssertTrue(screenFrame.contains(element.frame))
        }

        XCTAssertTrue(
            library.frame.insetBy(dx: -0.5, dy: -0.5).contains(libraryLabel.frame),
            "library \(library.frame) must contain label \(libraryLabel.frame)"
        )
        XCTAssertTrue(
            flash.frame.insetBy(dx: -0.5, dy: -0.5).contains(flashLabel.frame),
            "flash \(flash.frame) must contain label \(flashLabel.frame)"
        )
        XCTAssertLessThanOrEqual(close.frame.maxX, hint.frame.minX)
        XCTAssertLessThanOrEqual(viewport.frame.maxY, library.frame.minY)
        XCTAssertLessThanOrEqual(library.frame.maxX, shutter.frame.minX)
        XCTAssertLessThanOrEqual(shutter.frame.maxX, flash.frame.minX)
    }

    private func attachScreenshot() {
        let attachment = XCTAttachment(screenshot: XCUIScreen.main.screenshot())
        attachment.name = "camera-korean-390x844-ax5"
        attachment.lifetime = .keepAlways
        add(attachment)
    }
}
