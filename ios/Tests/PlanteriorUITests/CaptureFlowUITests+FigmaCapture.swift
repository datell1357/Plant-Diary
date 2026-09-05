import XCTest

/// Visual-matrix capture harness for the Figma `plant-capture-flow-board`
/// states, which are only reachable by tapping the camera action and the
/// review consent flow: `capture.camera`, `capture.photoReview`,
/// `capture.identifying`, and `capture.identificationResult`.
///
/// Behaviour is already asserted by `CaptureFlowUITests+Figma`; this file
/// reuses its launch/navigation helpers, drives each state, and attaches a
/// named screenshot. Assertions stay structural so no copy or pixel value is
/// pinned here.
extension CaptureFlowUITests {
    func testCameraCaptureRendersFigmaBlackChromeWithShutterControls() {
        let app = XCUIApplication()
        launchCapture(app)
        openCamera(app)
        let surface = app.otherElements["capture.camera"]
        XCTAssertTrue(surface.waitForExistence(timeout: 10))
        XCTAssertTrue(app.buttons["capture.close"].exists)
        XCTAssertEqual(app.buttons["capture.close"].label, "촬영 닫기")
        let hint = app.staticTexts["capture.hint"]
        XCTAssertTrue(hint.exists)
        XCTAssertEqual(hint.label, "식물을 프레임 안에 맞춰주세요")
        let viewport = app.images["capture.viewport"]
        XCTAssertTrue(viewport.exists, "the viewfinder must render a real image layer")
        assertFrame(viewport, x: 41, y: 265, width: 320, height: 320)
        let reticle = app.otherElements["capture.reticle"]
        XCTAssertTrue(reticle.exists)
        assertFrame(reticle, x: 81, y: 305, width: 240, height: 240)
        let library = app.buttons["capture.library"]
        XCTAssertTrue(library.exists)
        XCTAssertEqual(library.label, "사진 보관함")
        XCTAssertGreaterThanOrEqual(library.frame.width.rounded(), 44)
        XCTAssertGreaterThanOrEqual(library.frame.height.rounded(), 44)
        let flash = app.buttons["capture.flash"]
        XCTAssertTrue(flash.exists)
        XCTAssertFalse(app.buttons["capture.switch"].exists)
        let shutter = app.buttons["capture.shutter"]
        XCTAssertTrue(shutter.exists)
        XCTAssertEqual(shutter.label, "촬영")
        assertFrame(
            shutter,
            x: 165,
            y: 746,
            width: 72,
            height: 72,
            message: "the reference shutter uses a 72pt outer ring"
        )
        XCTAssertLessThan(library.frame.midX, shutter.frame.midX)
        XCTAssertLessThan(shutter.frame.midX, flash.frame.midX)
        XCTAssertFalse(app.staticTexts["capture.result.species"].exists)
        assertMinimumTargets(
            app,
            identifiers: ["capture.close", "capture.library", "capture.shutter", "capture.flash"]
        )
    }

    func testFlashTogglesStateWithoutEnteringCapturePath() {
        let app = XCUIApplication()
        launchCapture(app, environment: ["QA_CAMERA_DENIED": "1"])
        openCamera(app)

        let flash = app.buttons["capture.flash"]
        XCTAssertTrue(flash.waitForExistence(timeout: 5))
        flash.tap()

        XCTAssertTrue(app.otherElements["capture.camera"].exists)
        XCTAssertFalse(
            app.staticTexts["capture.error"].exists,
            "Flash must not invoke the camera capture path"
        )
        XCTAssertEqual(flash.value as? String, "켜짐")
        XCTAssertTrue(flash.isSelected)

        flash.tap()
        XCTAssertEqual(flash.value as? String, "꺼짐")
        XCTAssertFalse(flash.isSelected)
    }

    func testKoreanAX5CameraTertiaryControlsKeepReadableLabelsAndTargets() {
        let app = XCUIApplication()
        app.launchArguments += [
            "-AppleLanguages", "(ko)",
            "-AppleLocale", "ko_KR",
            "-UIPreferredContentSizeCategoryName",
            "UICTContentSizeCategoryAccessibilityXXXL"
        ]
        launchCapture(app)
        openCamera(app)

        for identifier in ["capture.library", "capture.flash"] {
            let control = app.buttons[identifier]
            XCTAssertTrue(control.waitForExistence(timeout: 5))
            XCTAssertGreaterThanOrEqual(control.frame.width, 88)
            XCTAssertGreaterThanOrEqual(control.frame.height, 44)
            XCTAssertFalse(control.label.contains("\u{2026}"))
        }
        let libraryLabel = app.staticTexts["capture.library.label"]
        let flashLabel = app.staticTexts["capture.flash.label"]
        XCTAssertTrue(libraryLabel.exists)
        XCTAssertTrue(flashLabel.exists)
        XCTAssertEqual(libraryLabel.label, "사진 보관함")
        XCTAssertEqual(flashLabel.label, "플래시")
        XCTAssertTrue(app.buttons["capture.library"].frame.contains(libraryLabel.frame))
        XCTAssertTrue(app.buttons["capture.flash"].frame.contains(flashLabel.frame))
    }

    func testKoreanAX5ReviewAndResultControlsReflowAndRemainReachable() async {
        let app = XCUIApplication()
        app.launchArguments += [
            "-AppleLanguages", "(ko)",
            "-AppleLocale", "ko_KR",
            "-UIPreferredContentSizeCategoryName",
            "UICTContentSizeCategoryAccessibilityXXXL"
        ]
        launchCapture(app, environment: ["QA_PHOTO_FIXTURE": "valid"])
        openCamera(app)

        let photo = app.images["photo.review"]
        XCTAssertTrue(photo.waitForExistence(timeout: 10))
        let identify = app.buttons["photo.acknowledge"]
        let more = app.buttons["photo.more"]
        XCTAssertGreaterThanOrEqual(identify.frame.height.rounded(), 44)
        XCTAssertGreaterThanOrEqual(more.frame.height.rounded(), 44)
        XCTAssertGreaterThanOrEqual(more.frame.minY, identify.frame.maxY)
        XCTAssertFalse(app.buttons["photo.replace"].exists)
        XCTAssertFalse(app.buttons["photo.manual"].exists)

        identify.tap()
        app.alerts["사진 처리 안내"].buttons["동의하고 계속"].tap()
        XCTAssertTrue(
            app.otherElements["capture.identification-result"].waitForExistence(timeout: 15)
        )
        assertResultSpeciesAccessibility(in: app)
        let confidence = app.descendants(matching: .any)["capture.result.confidence"]
        XCTAssertLessThan(
            confidence.frame.height,
            confidence.frame.width,
            "the AX5 confidence indicator must not collapse into a vertical pill"
        )

        let candidateTwo = app.buttons["identification.candidate.1"]
        app.swipeUp()
        app.swipeUp()
        XCTAssertTrue(candidateTwo.isHittable)
        candidateTwo.tap()
        await assertSelected(candidateTwo)

        let candidateThree = app.buttons["identification.candidate.2"]
        app.swipeUp()
        XCTAssertTrue(candidateThree.isHittable)
        candidateThree.tap()
        await assertSelected(candidateThree)

        let register = app.buttons["capture.result.register"]
        XCTAssertTrue(register.isHittable)
        register.tap()
        XCTAssertTrue(app.navigationBars["식물 등록"].waitForExistence(timeout: 10))
    }

    private func assertSelected(_ candidate: XCUIElement) async {
        let selected = XCTNSPredicateExpectation(
            predicate: NSPredicate(format: "value == %@", "선택됨"),
            object: candidate
        )
        await fulfillment(of: [selected], timeout: 2)
    }

    private func assertFrame(
        _ element: XCUIElement,
        x expectedX: CGFloat,
        y expectedY: CGFloat,
        width: CGFloat,
        height: CGFloat,
        accuracy: CGFloat = 2,
        message: String = ""
    ) {
        XCTAssertEqual(element.frame.minX, expectedX, accuracy: accuracy, message)
        XCTAssertEqual(element.frame.minY, expectedY, accuracy: accuracy, message)
        XCTAssertEqual(element.frame.width, width, accuracy: accuracy, message)
        XCTAssertEqual(element.frame.height, height, accuracy: accuracy, message)
    }
}
