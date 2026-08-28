import XCTest

@MainActor
final class CaptureFlowUITests: XCTestCase {
    func testCameraDeniedPermissionKeepsNativeRecoveryPaths() {
        let app = XCUIApplication()
        launchCapture(app, environment: ["QA_CAMERA_DENIED": "1"])
        openCamera(app)
        XCTAssertTrue(app.otherElements["capture.camera"].waitForExistence(timeout: 10))
        app.buttons["capture.shutter"].tap()
        let recovery = app.staticTexts["capture.error"]
        XCTAssertTrue(recovery.waitForExistence(timeout: 5))
        XCTAssertTrue(app.buttons["capture.settings"].exists)
        XCTAssertTrue(app.buttons["capture.library"].exists)
        XCTAssertFalse(app.otherElements["capture.fake-camera"].exists)
    }

    func testPhotoReviewRendersChosenPhotoWithIdentifyAndRetakeActions() {
        let app = XCUIApplication()
        launchCapture(app, environment: ["QA_PHOTO_FIXTURE": "valid"])
        openCamera(app)
        let review = app.otherElements["capture.photo-review"]
        XCTAssertTrue(review.waitForExistence(timeout: 10))
        XCTAssertTrue(app.staticTexts["capture.review.title"].exists)
        XCTAssertEqual(app.staticTexts["capture.review.title"].label, "사진 확인")
        XCTAssertTrue(app.buttons["capture.review.back"].exists)
        let photo = app.images["photo.review"]
        XCTAssertTrue(photo.exists, "review renders the chosen photo")
        XCTAssertEqual(photo.label, "촬영한 식물 사진")
        XCTAssertEqual(photo.frame.minX, 8, accuracy: 2)
        XCTAssertEqual(photo.frame.minY, 188, accuracy: 2)
        XCTAssertEqual(photo.frame.width, 386, accuracy: 2)
        XCTAssertEqual(photo.frame.height, 444, accuracy: 2)
        XCTAssertFalse(app.otherElements["capture.review.content"].exists)
        XCTAssertTrue(app.staticTexts["capture.review.caption"].exists)
        let identify = app.buttons["photo.acknowledge"]
        let retake = app.buttons["photo.retake"]
        XCTAssertTrue(identify.exists)
        XCTAssertEqual(identify.label, "이 사진으로 식별하기")
        XCTAssertTrue(retake.exists)
        XCTAssertEqual(retake.label, "다시 촬영")
        XCTAssertEqual(identify.frame.minY, 721, accuracy: 2)
        XCTAssertEqual(retake.frame.minY, 779, accuracy: 2)
        XCTAssertLessThan(identify.frame.minY, retake.frame.minY)
        XCTAssertGreaterThanOrEqual(identify.frame.height.rounded(), 44)
        XCTAssertFalse(app.buttons["photo.replace"].exists)
        XCTAssertFalse(app.buttons["photo.manual"].exists)
        assertMinimumTargets(
            app,
            identifiers: ["photo.acknowledge", "photo.retake"]
        )
    }

    func testPhotoReviewPreservesConsentAcknowledgementAndDenial() {
        let app = XCUIApplication()
        launchCapture(app, environment: ["QA_PHOTO_FIXTURE": "valid"])
        openCamera(app)
        XCTAssertTrue(app.images["photo.review"].waitForExistence(timeout: 10))
        app.buttons["photo.acknowledge"].tap()
        let consent = app.alerts["사진 처리 안내"]
        XCTAssertTrue(consent.waitForExistence(timeout: 5))
        consent.buttons["취소"].tap()
        XCTAssertTrue(app.otherElements["capture.photo-review"].waitForExistence(timeout: 5))
        XCTAssertFalse(app.otherElements["capture.identifying"].exists)
    }

    func testIdentifyingKeepsPhotoContextWithSemanticProgress() {
        let app = XCUIApplication()
        launchCapture(
            app,
            environment: ["QA_PHOTO_FIXTURE": "valid", "QA_IDENTIFICATION_STATE": "pending"]
        )
        submitReviewedPhoto(app)
        let identifying = app.otherElements["capture.identifying"]
        XCTAssertTrue(identifying.waitForExistence(timeout: 10))
        XCTAssertTrue(app.images["capture.identifying.backdrop"].exists)
        let headline = app.staticTexts["identification.pending"]
        XCTAssertTrue(headline.exists)
        XCTAssertEqual(headline.label, "AI가 식물을 분석하고 있어요...")
        XCTAssertTrue(app.staticTexts["capture.identifying.hint"].exists)
        let progress = app.otherElements["capture.identifying.progress"]
        XCTAssertTrue(progress.exists, "progress must be a semantic element, not decoration")
        XCTAssertEqual(progress.value as? String, "분석 중")
        XCTAssertEqual(progress.frame.minX, 141, accuracy: 2)
        XCTAssertEqual(progress.frame.width, 120, accuracy: 2)
        XCTAssertEqual(progress.frame.height, 120, accuracy: 2)
        XCTAssertEqual(progress.frame.minY, 200, accuracy: 2)
    }

    func testIdentifyingCapturePhaseIsDeterministicallyStatic() {
        let app = XCUIApplication()
        launchCapture(
            app,
            environment: [
                "QA_PHOTO_FIXTURE": "valid",
                "QA_IDENTIFICATION_STATE": "pending",
                "QA_CAPTURE_STATIC_PHASE": "1"
            ]
        )
        submitReviewedPhoto(app)

        XCTAssertTrue(app.otherElements["capture.identifying"].waitForExistence(timeout: 10))
        XCTAssertEqual(
            app.otherElements.matching(identifier: "capture.identifying.progress").count,
            1
        )
        XCTAssertFalse(app.otherElements["capture.identifying.progress.static"].exists)
        XCTAssertFalse(app.otherElements["capture.identifying.progress.animated"].exists)
    }

    func testIdentifyingUnderReduceMotionKeepsStateWithoutSubstituteAnimation() {
        let app = XCUIApplication()
        launchCapture(
            app,
            environment: [
                "QA_PHOTO_FIXTURE": "valid",
                "QA_IDENTIFICATION_STATE": "pending",
                "QA_REDUCE_MOTION": "1"
            ]
        )
        submitReviewedPhoto(app)
        XCTAssertTrue(app.otherElements["capture.identifying"].waitForExistence(timeout: 10))
        XCTAssertTrue(app.staticTexts["identification.pending"].exists)
        XCTAssertEqual(
            app.otherElements.matching(identifier: "capture.identifying.progress").count,
            1
        )
        XCTAssertFalse(app.otherElements["capture.identifying.progress.static"].exists)
        XCTAssertFalse(app.otherElements["capture.identifying.progress.animated"].exists)
    }

    func testIdentificationResultShowsConfidenceSpeciesAndAlternates() {
        let app = XCUIApplication()
        launchCapture(app, environment: ["QA_PHOTO_FIXTURE": "valid"])
        submitReviewedPhoto(app)
        let result = app.otherElements["capture.identification-result"]
        XCTAssertTrue(result.waitForExistence(timeout: 15))
        XCTAssertTrue(app.staticTexts["capture.result.title"].exists)
        XCTAssertEqual(app.staticTexts["capture.result.title"].label, "식별 결과")
        let hero = app.images["capture.result.hero"]
        XCTAssertTrue(hero.exists)
        XCTAssertEqual(hero.frame.height, 160, accuracy: 2)
        let resultCard = app.otherElements["capture.result.card"]
        XCTAssertTrue(resultCard.exists)
        XCTAssertEqual(resultCard.frame.minX, 20, accuracy: 2)
        XCTAssertEqual(resultCard.frame.width, 362, accuracy: 2)
        XCTAssertEqual(resultCard.frame.minY, 142, accuracy: 2)
        XCTAssertEqual(hero.frame.minX, 20, accuracy: 2)
        XCTAssertEqual(hero.frame.minY, 142, accuracy: 2)
        let confidence = app.descendants(matching: .any)["capture.result.confidence"]
        XCTAssertTrue(confidence.exists)
        XCTAssertEqual(confidence.label, "신뢰도 95%", "the top candidate matches the fixture")
        XCTAssertTrue(app.staticTexts["capture.result.species"].exists)
        XCTAssertTrue(app.staticTexts["capture.result.binomial"].exists)
        XCTAssertTrue(app.staticTexts["capture.result.alternates.header"].exists)
        XCTAssertEqual(app.staticTexts["capture.result.alternates.header"].label, "다른 후보")
        XCTAssertTrue(app.buttons["identification.candidate.1"].exists)
        XCTAssertEqual(
            app.buttons["identification.candidate.1"].label,
            "몬스테라 아단소니, 신뢰도 72%"
        )
        XCTAssertEqual(
            app.buttons["identification.candidate.2"].label,
            "필로덴드론, 신뢰도 45%"
        )
        let register = app.buttons["capture.result.register"]
        XCTAssertTrue(register.exists)
        XCTAssertEqual(register.label, "이 식물로 등록하기")
        XCTAssertEqual(register.frame.minY, 723, accuracy: 2)
        XCTAssertTrue(app.buttons["identification.manual"].exists)
        XCTAssertTrue(app.buttons["identification.manual-registration"].exists)
        assertMinimumTargets(app, identifiers: ["capture.result.register"])
    }

    func testIdentificationResultSelectionHandsOffToRegistration() {
        let app = XCUIApplication()
        launchCapture(app, environment: ["QA_PHOTO_FIXTURE": "valid"])
        submitReviewedPhoto(app)
        XCTAssertTrue(
            app.otherElements["capture.identification-result"].waitForExistence(timeout: 15)
        )
        app.buttons["identification.candidate.1"].tap()
        app.buttons["capture.result.register"].tap()
        XCTAssertTrue(app.navigationBars["식물 등록"].waitForExistence(timeout: 10))

        let name = app.textFields["registration.name"]
        XCTAssertEqual(name.value as? String, "몬스테라 아단소니")
        let search = app.textFields["registration.search"]
        XCTAssertEqual(search.value as? String, "몬스테라 아단소니")
        search.tap()
        search.typeText(" 검색")
        XCTAssertEqual(
            name.value as? String,
            "몬스테라 아단소니",
            "public-species search must not overwrite the personal display name"
        )
        XCTAssertTrue(app.buttons["registration.submit"].isEnabled)
    }
}
