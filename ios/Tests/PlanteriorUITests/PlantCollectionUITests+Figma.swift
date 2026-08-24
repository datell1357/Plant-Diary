import XCTest

@MainActor
final class PlantCollectionFigmaUITests: XCTestCase {
    func testFigmaCollectionListHasFiveReferencePlantsAndSearchAction() {
        let app = figmaCollectionApp()
        app.launch()

        XCTAssertTrue(app.scrollViews["collection.screen"].waitForExistence(timeout: 10))
        XCTAssertEqual(app.staticTexts["collection.title"].label, "나의 도감")
        assertCollectionHeadingMatchesReference(in: app)
        XCTAssertEqual(
            app.staticTexts["collection.summary.title"].label,
            "등록된 식물 총 5개 🌱"
        )
        XCTAssertEqual(
            app.staticTexts["collection.summary.subtitle"].label,
            "초보 식집사 단계에서 씩씩하게 자라는 중!"
        )
        let expectedRows = [
            ("몬몬이 (몬스테라)", "오늘 물주기!"),
            ("뾰족이 (스투키)", "D-3"),
            ("초록이 (미니 선인장)", "D-14"),
            ("야자 (아레카야자)", "D-2"),
            ("스킨이 (스킨답서스)", "D-5")
        ]
        for (index, expected) in expectedRows.enumerated() {
            let row = app.buttons["collection.row.\(index)"]
            XCTAssertTrue(row.exists, "missing reference row \(index)")
            XCTAssertEqual(row.label, expected.0)
            XCTAssertEqual(row.value as? String, expected.1)
            XCTAssertTrue(app.images["collection.image.local-\(index)"].exists)
        }
        let dueStatus = app.staticTexts["collection.status.0"]
        XCTAssertEqual(dueStatus.label, "오늘 물주기!")
        XCTAssertEqual(dueStatus.value as? String, "주의")
        XCTAssertFalse(app.textFields["collection.search"].exists)
        let searchAction = app.buttons["collection.search.action"]
        XCTAssertTrue(searchAction.exists)
        XCTAssertGreaterThanOrEqual(searchAction.frame.width, 44)
        XCTAssertGreaterThanOrEqual(searchAction.frame.height, 44)
        let add = app.buttons["collection.add"]
        XCTAssertTrue(add.exists)
        XCTAssertGreaterThanOrEqual(add.frame.width, 44)
        XCTAssertGreaterThan(add.frame.midY, app.buttons["collection.row.3"].frame.midY)
        attachScreenshot(named: "collection-list-402x874")

        searchAction.tap()
        XCTAssertTrue(app.textFields["collection.search"].waitForExistence(timeout: 5))
    }

    func testFigmaPlantDetailIsImageLedAndKeepsLiveCareActions() {
        let app = figmaCollectionApp()
        app.launchEnvironment["TZ"] = "Pacific/Kiritimati"
        app.launch()

        XCTAssertTrue(app.buttons["collection.row.0"].waitForExistence(timeout: 10))
        app.buttons["collection.row.0"].tap()

        XCTAssertTrue(app.scrollViews["plant.detail.screen"].waitForExistence(timeout: 5))
        XCTAssertTrue(app.images["plant.detail.hero"].exists)
        XCTAssertTrue(app.staticTexts["몬스테라"].exists)
        let guide = app.otherElements["plant.detail.guide"]
        let watering = app.otherElements["plant.detail.watering-card"]
        let memo = app.otherElements["plant.detail.memo"]
        assertPlantDetailChromeAndHeroMatchReference(in: app)
        XCTAssertTrue(guide.exists)
        XCTAssertTrue(watering.exists)
        XCTAssertEqual(
            app.staticTexts["watering.compact-date"].label,
            "2026. 05. 15 (4일 전)",
            "elapsed CalendarDate days must not depend on the device timezone"
        )
        XCTAssertTrue(app.buttons["watering.complete"].exists)
        XCTAssertTrue(memo.exists)
        let memoBody = app.staticTexts["plant.detail.memo.body"]
        XCTAssertTrue(memoBody.exists)
        XCTAssertEqual(
            memoBody.label,
            "최근에 새 잎이 돋아나기 시작했어요! 잎 끝이 마르지 않게 저녁마다 "
                + "습도 관리를 위한 스프레이를 분무해주고 있습니다."
        )
        XCTAssertLessThan(app.images["plant.detail.hero"].frame.maxY, guide.frame.minY)
        XCTAssertLessThan(guide.frame.maxY, watering.frame.minY)
        XCTAssertLessThan(watering.frame.maxY, memo.frame.minY)
        XCTAssertFalse(app.textFields["plant.detail.nickname"].exists)
        XCTAssertFalse(app.tables.firstMatch.exists, "detail must not regress to Form")
        attachScreenshot(named: "collection-detail-402x874")

        app.buttons["plant.detail.edit"].tap()
        XCTAssertTrue(app.textFields["plant.detail.nickname"].waitForExistence(timeout: 5))
    }

    func testFigmaSymptomRemedyExpandsStructuredEducationalGuidance() {
        let app = figmaCollectionApp()
        app.launch()

        XCTAssertTrue(app.buttons["collection.row.0"].waitForExistence(timeout: 10))
        app.buttons["collection.row.0"].tap()
        let remedyLink = app.buttons["plant.detail.remedy"]
        XCTAssertTrue(remedyLink.waitForExistence(timeout: 5))
        let detailScroll = app.scrollViews["plant.detail.screen"]
        detailScroll.swipeUp()
        XCTAssertTrue(remedyLink.isHittable)
        remedyLink.tap()

        XCTAssertTrue(app.scrollViews["remedy.screen"].waitForExistence(timeout: 5))
        XCTAssertTrue(app.staticTexts["증상 대처법"].exists)
        assertRemedyChromeAndExpandedCardMatchReference(in: app)
        let context = app.staticTexts["remedy.context"]
        XCTAssertEqual(context.label, "반려식물: 몬스테라 🌱")
        XCTAssertEqual(
            context.frame.height,
            28,
            accuracy: 0.5
        )
        for index in 0 ..< 4 {
            XCTAssertTrue(app.buttons["remedy.symptom.\(index)"].exists)
        }
        XCTAssertEqual(app.buttons["remedy.symptom.0"].value as? String, "펼쳐짐")
        XCTAssertTrue(app.staticTexts["remedy.cause.0"].exists)
        let firstAction = app.staticTexts["remedy.action.0"]
        XCTAssertTrue(firstAction.exists)
        XCTAssertEqual(
            firstAction.label,
            "물 주기 간격을 대폭 늘려 화분의 속흙까지 완전히 건조시키고 "
                + "배수 상태를 확인하세요. 필요시 영양제를 보충합니다."
        )
        XCTAssertFalse(app.staticTexts["remedy.cause.1"].exists)
        XCTAssertLessThan(
            app.otherElements["remedy.card.1"].frame.height,
            app.otherElements["remedy.card.0"].frame.height
        )
        attachScreenshot(named: "collection-remedy-402x874")

        app.buttons["remedy.symptom.1"].tap()
        let secondCause = app.staticTexts["remedy.cause.1"]
        XCTAssertTrue(secondCause.waitForExistence(timeout: 5))
        XCTAssertTrue(secondCause.label.contains("나타날 수 있어요."))
        XCTAssertTrue(app.staticTexts["remedy.action.1"].exists)
    }

    func testFigmaTrueEmptyIsDistinctFromSearchEmptyAndOffersBothRoutes() {
        let app = figmaCollectionApp(empty: true)
        app.launch()

        XCTAssertTrue(app.images["collection.empty.illustration"].waitForExistence(timeout: 10))
        let heading = app.staticTexts["collection.title"]
        XCTAssertTrue(heading.exists, "true-empty must retain the Collection heading")
        XCTAssertEqual(heading.label, "나의 도감")
        assertCollectionHeadingMatchesReference(in: app)
        XCTAssertEqual(
            app.staticTexts["collection.empty.title"].label,
            "아직 등록된 식물이 없어요 🥺"
        )
        XCTAssertEqual(
            app.staticTexts["collection.empty.body"].label,
            "첫 번째 반려식물을 등록하고 성장기를 남겨보세요"
        )
        XCTAssertEqual(app.buttons["collection.empty.camera"].label, "사진으로 식별하기")
        XCTAssertEqual(app.buttons["collection.empty.manual"].label, "직접 등록하기")
        XCTAssertFalse(app.staticTexts["검색 결과가 없어요"].exists)
        XCTAssertFalse(app.buttons["collection.search.action"].exists)
        XCTAssertFalse(app.textFields["collection.search"].exists)
        XCTAssertGreaterThan(app.images["collection.empty.illustration"].frame.minY, 120)
        XCTAssertLessThan(app.images["collection.empty.illustration"].frame.minY, 180)
        let camera = app.buttons["collection.empty.camera"]
        let manual = app.buttons["collection.empty.manual"]
        let homeTab = app.buttons["tab.home"]
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
        attachScreenshot(named: "collection-empty-before-camera")
        camera.tap()
        XCTAssertTrue(app.otherElements["capture.camera"].waitForExistence(timeout: 5))
        app.buttons["capture.close"].tap()
        XCTAssertTrue(
            app.otherElements["capture.camera"].waitForNonExistence(timeout: 5)
        )
        XCTAssertTrue(
            app.images["collection.empty.illustration"].waitForExistence(timeout: 5)
        )
        XCTAssertTrue(app.buttons["tab.home"].exists)
        assertCollectionHeadingMatchesReference(in: app)
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
}
