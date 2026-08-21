import XCTest

@MainActor
final class PlantCollectionFigmaUITests: XCTestCase {
    func testFigmaCollectionListHasSummaryPlantMediaStatusAndFloatingAction() {
        let app = figmaCollectionApp()
        app.launch()

        XCTAssertTrue(app.scrollViews["collection.screen"].waitForExistence(timeout: 10))
        XCTAssertEqual(app.staticTexts["collection.title"].label, "나의 도감")
        XCTAssertEqual(
            app.staticTexts["collection.summary.title"].label,
            "등록된 식물 총 2개 🌱"
        )
        XCTAssertTrue(app.images["collection.summary.illustration"].exists)
        XCTAssertTrue(app.images["collection.image.local-0"].exists)
        XCTAssertEqual(app.buttons["collection.row.0"].value as? String, "오늘 물주기")
        XCTAssertTrue(app.buttons["collection.add"].exists)
        XCTAssertGreaterThanOrEqual(app.buttons["collection.add"].frame.width, 44)
        XCTAssertEqual(app.scrollViews.count, 1)
        attachScreenshot(named: "collection-list-402x874")
    }

    func testFigmaPlantDetailIsImageLedAndKeepsLiveCareActions() {
        let app = figmaCollectionApp()
        app.launch()

        XCTAssertTrue(app.buttons["collection.row.0"].waitForExistence(timeout: 10))
        app.buttons["collection.row.0"].tap()

        XCTAssertTrue(app.scrollViews["plant.detail.screen"].waitForExistence(timeout: 5))
        XCTAssertTrue(app.images["plant.detail.hero"].exists)
        XCTAssertEqual(app.staticTexts["plant.detail.title"].label, "몬스테라")
        XCTAssertTrue(app.staticTexts["plant.detail.species"].exists)
        XCTAssertTrue(app.otherElements["plant.detail.guide"].exists)
        XCTAssertTrue(app.staticTexts["watering.next-date"].exists)
        XCTAssertTrue(app.buttons["watering.complete"].exists)
        XCTAssertTrue(app.buttons["plant.detail.remedy"].exists)
        XCTAssertFalse(app.tables.firstMatch.exists, "detail must not regress to Form")
        attachScreenshot(named: "collection-detail-402x874")
    }

    func testFigmaSymptomRemedyExpandsStructuredEducationalGuidance() {
        let app = figmaCollectionApp()
        app.launch()

        XCTAssertTrue(app.buttons["collection.row.0"].waitForExistence(timeout: 10))
        app.buttons["collection.row.0"].tap()
        XCTAssertTrue(app.buttons["plant.detail.remedy"].waitForExistence(timeout: 5))
        app.buttons["plant.detail.remedy"].tap()

        XCTAssertTrue(app.scrollViews["remedy.screen"].waitForExistence(timeout: 5))
        XCTAssertTrue(app.navigationBars["증상 대처법"].exists)
        XCTAssertEqual(app.staticTexts["remedy.context"].label, "반려식물: 몬스테라 🌿")
        XCTAssertTrue(app.buttons["remedy.symptom.0"].exists)
        XCTAssertTrue(app.staticTexts["remedy.cause.0"].exists)
        XCTAssertTrue(app.staticTexts["remedy.action.0"].exists)

        app.buttons["remedy.symptom.1"].tap()
        XCTAssertTrue(app.staticTexts["remedy.cause.1"].waitForExistence(timeout: 5))
        XCTAssertTrue(app.staticTexts["remedy.action.1"].exists)
        XCTAssertTrue(app.navigationBars.buttons.firstMatch.exists)
        attachScreenshot(named: "collection-remedy-402x874")
    }

    func testFigmaTrueEmptyIsDistinctFromSearchEmptyAndOffersBothRoutes() {
        let app = figmaCollectionApp(empty: true)
        app.launch()

        XCTAssertTrue(app.images["collection.empty.illustration"].waitForExistence(timeout: 10))
        XCTAssertEqual(
            app.staticTexts["collection.empty.title"].label,
            "아직 등록된 식물이 없어요 😢"
        )
        XCTAssertEqual(
            app.staticTexts["collection.empty.body"].label,
            "첫 번째 반려식물을 등록하고 성장기를 남겨보세요"
        )
        XCTAssertEqual(app.buttons["collection.empty.camera"].label, "사진으로 식별하기")
        XCTAssertEqual(app.buttons["collection.empty.manual"].label, "직접 등록하기")
        XCTAssertFalse(app.staticTexts["검색 결과가 없어요"].exists)
        let camera = app.buttons["collection.empty.camera"]
        let manual = app.buttons["collection.empty.manual"]
        let homeTab = app.buttons["tab.home"]
        attachScreenshot(named: "collection-empty-before-camera")
        guard manual.isHittable, !manual.frame.intersects(homeTab.frame) else {
            XCTFail(
                "empty manual route must clear the tab bar; "
                    + "manual=\(manual.frame), tab=\(homeTab.frame)"
            )
            return
        }
        guard camera.isHittable, !camera.frame.intersects(homeTab.frame) else {
            XCTFail(
                "empty camera must clear the tab bar; camera=\(camera.frame), tab=\(homeTab.frame)"
            )
            return
        }
        camera.tap()
        XCTAssertTrue(app.otherElements["capture.camera"].waitForExistence(timeout: 5))
        attachScreenshot(named: "collection-empty-402x874")
    }

    func testFigmaKoreanAX5ListAndDetailRemainScrollable() {
        let app = figmaCollectionApp()
        app.launchArguments += [
            "-AppleLanguages", "(ko)",
            "-AppleLocale", "ko_KR",
            "-UIPreferredContentSizeCategoryName",
            "UICTContentSizeCategoryAccessibilityXXXL"
        ]
        app.launch()

        XCTAssertTrue(app.scrollViews["collection.screen"].waitForExistence(timeout: 10))
        let row = app.buttons["collection.row.0"]
        let add = app.buttons["collection.add"]
        let status = app.staticTexts["collection.status.0"]
        XCTAssertTrue(row.exists)
        XCTAssertTrue(add.isHittable)
        XCTAssertTrue(status.exists)
        XCTAssertFalse(row.frame.intersects(add.frame))
        XCTAssertGreaterThan(status.frame.width, status.frame.height)
        assertTitleStaysInsideContentColumn(in: app)
        attachScreenshot(named: "collection-list-korean-ax5")
        app.buttons["collection.row.0"].tap()
        XCTAssertTrue(app.scrollViews["plant.detail.screen"].waitForExistence(timeout: 5))
        XCTAssertTrue(app.buttons["watering.complete"].exists)
        XCTAssertTrue(app.buttons["plant.detail.remedy"].exists)
        attachScreenshot(named: "collection-detail-korean-ax5")
    }

    func testFigmaReduceMotionLightCollectionRemainsUsable() {
        let app = figmaCollectionApp()
        app.launchEnvironment["QA_REDUCE_MOTION"] = "1"
        app.launch()

        XCTAssertTrue(app.scrollViews["collection.screen"].waitForExistence(timeout: 10))
        XCTAssertTrue(app.buttons["collection.add"].isHittable)
        attachScreenshot(named: "collection-list-light-reduce-motion")
    }

    private func figmaCollectionApp(empty: Bool = false) -> XCUIApplication {
        let app = XCUIApplication()
        app.launchEnvironment["QA_SKIP_ONBOARDING"] = "1"
        app.launchEnvironment["QA_AUTHENTICATED"] = "1"
        app.launchEnvironment["QA_INITIAL_TAB"] = "collection"
        app.launchEnvironment["QA_COLLECTION_FIXTURE"] = "1"
        app.launchEnvironment["QA_RESET_COLLECTION"] = "1"
        app.launchEnvironment["QA_WATERING_TODAY"] = "2026-08-11"
        if empty {
            app.launchEnvironment["QA_COLLECTION_EMPTY"] = "1"
        }
        return app
    }

    private func attachScreenshot(named name: String) {
        let attachment = XCTAttachment(screenshot: XCUIScreen.main.screenshot())
        attachment.name = name
        attachment.lifetime = .keepAlways
        add(attachment)
    }
}
