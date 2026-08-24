import XCTest

@MainActor
extension CaptureFlowUITests {
    func testDirectCorrectionPreservesSelectedCandidateContext() {
        let app = XCUIApplication()
        launchCapture(app, environment: ["QA_PHOTO_FIXTURE": "valid"])
        submitReviewedPhoto(app)
        XCTAssertTrue(
            app.otherElements["capture.identification-result"].waitForExistence(timeout: 15)
        )
        app.buttons["identification.candidate.2"].tap()
        app.buttons["identification.manual"].tap()

        XCTAssertTrue(app.navigationBars["식물 등록"].waitForExistence(timeout: 10))
        XCTAssertEqual(
            app.textFields["registration.name"].value as? String,
            "필로덴드론"
        )
    }

    func testIdentificationResultBackReturnsToReviewedPhoto() {
        let app = XCUIApplication()
        launchCapture(app, environment: ["QA_PHOTO_FIXTURE": "valid"])
        submitReviewedPhoto(app)
        XCTAssertTrue(
            app.otherElements["capture.identification-result"].waitForExistence(timeout: 15)
        )

        app.buttons["capture.result.back"].tap()

        XCTAssertTrue(
            app.otherElements["capture.photo-review"].waitForExistence(timeout: 10),
            "Back must revise the retained photo instead of dismissing the capture journey"
        )
        XCTAssertTrue(app.images["photo.review"].exists)
    }

    func testSuccessfulRegistrationClosesFlowAndDuplicateOpensRealPlant() {
        let app = XCUIApplication()
        launchCapture(
            app,
            environment: [
                "QA_PHOTO_FIXTURE": "valid",
                "QA_RESET_COLLECTION": "1"
            ]
        )
        submitReviewedPhoto(app)
        XCTAssertTrue(
            app.otherElements["capture.identification-result"].waitForExistence(timeout: 15)
        )
        app.buttons["capture.result.register"].tap()
        XCTAssertTrue(app.navigationBars["식물 등록"].waitForExistence(timeout: 10))
        app.buttons["registration.submit"].tap()
        XCTAssertTrue(
            app.navigationBars["식물 등록"].waitForNonExistence(timeout: 10),
            "successful identified registration must complete the capture flow"
        )
        let camera = app.buttons["tab.camera"]
        XCTAssertTrue(camera.waitForExistence(timeout: 10))
        XCTAssertTrue(camera.isHittable)

        submitReviewedPhoto(app)
        XCTAssertTrue(
            app.otherElements["capture.identification-result"].waitForExistence(timeout: 15)
        )
        app.buttons["capture.result.register"].tap()
        XCTAssertTrue(app.navigationBars["식물 등록"].waitForExistence(timeout: 10))
        app.buttons["registration.submit"].tap()
        let openExisting = app.buttons["registration.open-existing"].firstMatch
        XCTAssertTrue(openExisting.waitForExistence(timeout: 5))
        openExisting.tap()

        XCTAssertTrue(
            app.scrollViews["plant.detail.screen"].waitForExistence(timeout: 10),
            "duplicate recovery must route to the exact existing PlantCare detail"
        )
        XCTAssertTrue(
            app.staticTexts["몬스테라 델리오사"].waitForExistence(timeout: 5),
            "duplicate recovery must show the exact existing plant title"
        )
    }
}
