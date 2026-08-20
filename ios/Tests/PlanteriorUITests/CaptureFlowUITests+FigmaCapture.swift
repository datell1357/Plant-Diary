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
    func testCaptureCameraState() {
        let app = XCUIApplication()
        launchCapture(app)
        openCamera(app)

        XCTAssertTrue(
            app.otherElements["capture.camera"].waitForExistence(timeout: 10)
        )
        XCTAssertTrue(app.buttons["capture.shutter"].isHittable)
        attachFigmaScreenshot(named: "capture-camera")
    }

    func testCapturePhotoReviewState() {
        let app = XCUIApplication()
        launchCapture(app, environment: ["QA_PHOTO_FIXTURE": "valid"])
        openCamera(app)

        XCTAssertTrue(
            app.otherElements["capture.photo-review"]
                .waitForExistence(timeout: 10)
        )
        XCTAssertTrue(app.images["photo.review"].exists)
        attachFigmaScreenshot(named: "capture-photo-review")
    }

    func testCaptureIdentifyingState() {
        let app = XCUIApplication()
        launchCapture(
            app,
            environment: [
                "QA_PHOTO_FIXTURE": "valid",
                "QA_IDENTIFICATION_STATE": "pending"
            ]
        )
        submitReviewedPhoto(app)

        XCTAssertTrue(
            app.otherElements["capture.identifying"]
                .waitForExistence(timeout: 10)
        )
        attachFigmaScreenshot(named: "capture-identifying")
    }

    func testCaptureIdentificationResultState() {
        let app = XCUIApplication()
        launchCapture(app, environment: ["QA_PHOTO_FIXTURE": "valid"])
        submitReviewedPhoto(app)

        XCTAssertTrue(
            app.otherElements["capture.identification-result"]
                .waitForExistence(timeout: 15)
        )
        XCTAssertTrue(app.buttons["capture.result.register"].isHittable)
        attachFigmaScreenshot(named: "capture-identification-result")
    }

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
        XCTAssertEqual(hint.label, "식물을 초점에 맞춰주세요")
        let viewport = app.images["capture.viewport"]
        XCTAssertTrue(viewport.exists, "the viewfinder must render a real image layer")
        XCTAssertGreaterThanOrEqual(viewport.frame.width.rounded(), 300)
        XCTAssertTrue(app.buttons["capture.library"].exists)
        XCTAssertEqual(app.buttons["capture.library"].label, "사진 보관함")
        XCTAssertTrue(app.buttons["capture.switch"].exists)
        let shutter = app.buttons["capture.shutter"]
        XCTAssertTrue(shutter.exists)
        XCTAssertEqual(shutter.label, "촬영")
        XCTAssertGreaterThanOrEqual(
            shutter.frame.height.rounded(),
            64,
            "§6.11 shutter is a 72pt circle inside an 80pt ring"
        )
        XCTAssertFalse(app.staticTexts["capture.result.species"].exists)
        assertMinimumTargets(
            app,
            identifiers: ["capture.close", "capture.library", "capture.switch", "capture.shutter"]
        )
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

        for identifier in ["capture.library", "capture.switch"] {
            let control = app.buttons[identifier]
            XCTAssertTrue(control.waitForExistence(timeout: 5))
            XCTAssertGreaterThanOrEqual(control.frame.width, 88)
            XCTAssertGreaterThanOrEqual(control.frame.height, 44)
            XCTAssertFalse(control.label.contains("\u{2026}"))
        }
        let libraryLabel = app.staticTexts["capture.library.label"]
        let switchLabel = app.staticTexts["capture.switch.label"]
        XCTAssertTrue(libraryLabel.exists)
        XCTAssertTrue(switchLabel.exists)
        XCTAssertGreaterThanOrEqual(libraryLabel.frame.width, 80)
        XCTAssertGreaterThanOrEqual(switchLabel.frame.width, 80)
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
        let replace = app.buttons["photo.replace"]
        let manual = app.buttons["photo.manual"]
        XCTAssertGreaterThanOrEqual(replace.frame.height.rounded(), 44)
        XCTAssertGreaterThanOrEqual(manual.frame.height.rounded(), 44)
        XCTAssertGreaterThanOrEqual(
            manual.frame.minY,
            replace.frame.maxY,
            "AX5 tertiary actions must stack instead of becoming narrow columns"
        )

        app.buttons["photo.acknowledge"].tap()
        app.alerts["사진 처리 안내"].buttons["동의하고 계속"].tap()
        XCTAssertTrue(
            app.otherElements["capture.identification-result"].waitForExistence(timeout: 15)
        )
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

    private func attachFigmaScreenshot(named name: String) {
        let attachment = XCTAttachment(
            screenshot: XCUIScreen.main.screenshot()
        )
        attachment.name = name
        attachment.lifetime = .keepAlways
        add(attachment)
    }
}
