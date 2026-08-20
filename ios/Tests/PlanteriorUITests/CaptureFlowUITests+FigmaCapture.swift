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

    private func attachFigmaScreenshot(named name: String) {
        let attachment = XCTAttachment(
            screenshot: XCUIScreen.main.screenshot()
        )
        attachment.name = name
        attachment.lifetime = .keepAlways
        add(attachment)
    }
}
