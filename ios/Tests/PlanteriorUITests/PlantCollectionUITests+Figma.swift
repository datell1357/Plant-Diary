import XCTest

@MainActor
final class PlantCollectionFigmaUITests: XCTestCase {
    func testFigmaCollectionListHasFiveReferencePlantsAndSearchAction() {
        let app = figmaCollectionApp()
        app.launch()

        waitForFigmaCollectionFixture(in: app)
        XCTAssertEqual(app.staticTexts["collection.title"].label, "나의 도감")
        assertCollectionHeadingMatchesReference(in: app)
        assertCollectionListGeometryMatchesReference(in: app)
        XCTAssertEqual(
            app.staticTexts["collection.summary.title"].label,
            "등록된 식물 총 5개 🌱"
        )
        XCTAssertEqual(
            app.staticTexts["collection.summary.subtitle"].label,
            "초보 식집사 단계에서 씩씩하게 자라는 중!"
        )
        let expectedRows = [
            ("몬몬이 (몬스테라)", "오늘 물주기"),
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
        XCTAssertEqual(dueStatus.label, "오늘 물주기")
        XCTAssertEqual(dueStatus.value as? String, "주의")
        XCTAssertFalse(app.textFields["collection.search"].exists)
        let searchAction = app.buttons["collection.search.action"]
        XCTAssertTrue(searchAction.exists)
        XCTAssertGreaterThanOrEqual(searchAction.frame.width, 44)
        XCTAssertGreaterThanOrEqual(searchAction.frame.height, 44)
        assertCollectionAddActionMatchesReference(in: app)
        attachListFrameReceipt(in: app)
        attachScreenshot(named: "collection-list-402x874")

        searchAction.tap()
        XCTAssertTrue(app.textFields["collection.search"].waitForExistence(timeout: 5))
    }

    func testFigmaPlantDetailIsImageLedAndKeepsLiveCareActions() {
        let app = figmaCollectionApp()
        app.launchEnvironment["TZ"] = "Pacific/Kiritimati"
        app.launch()

        waitForFigmaCollectionFixture(in: app)
        XCTAssertTrue(app.buttons["collection.row.0"].exists)
        app.buttons["collection.row.0"].tap()

        XCTAssertTrue(app.scrollViews["plant.detail.screen"].waitForExistence(timeout: 5))
        assertSinglePersistentTabBar(in: app, selected: "tab.collection")
        XCTAssertTrue(app.images["plant.detail.hero"].exists)
        XCTAssertTrue(app.staticTexts["몬스테라"].exists)
        let guide = app.otherElements["plant.detail.guide"]
        let watering = app.otherElements["plant.detail.watering-card"]
        let memo = app.otherElements["plant.detail.memo"]
        let memoCard = app.otherElements["plant.detail.memo.card"]
        assertPlantDetailChromeAndHeroMatchReference(in: app)
        XCTAssertTrue(guide.exists)
        XCTAssertEqual(
            guide.value as? String,
            "출처: 농촌진흥청 실내정원용 식물, 공공데이터 15059042"
        )
        XCTAssertTrue(watering.exists)
        let topOffset: CGFloat = app.frame.height == 874 ? 0 : -15
        XCTAssertEqual(watering.frame.minX, 16, accuracy: 0.5)
        XCTAssertEqual(watering.frame.minY, 552 + topOffset, accuracy: 0.5)
        XCTAssertEqual(watering.frame.width, app.frame.width - 32, accuracy: 0.5)
        XCTAssertEqual(watering.frame.height, 67, accuracy: 0.5)
        XCTAssertEqual(
            app.staticTexts["watering.compact-date"].label,
            "2026. 05. 15 (4일 전)",
            "elapsed CalendarDate days must not depend on the device timezone"
        )
        XCTAssertTrue(app.buttons["watering.complete"].exists)
        XCTAssertTrue(memo.exists)
        XCTAssertTrue(memoCard.exists)
        assertPlantDetailMemoGeometry(in: app, memo: memo, memoCard: memoCard)
        XCTAssertLessThan(app.images["plant.detail.hero"].frame.maxY, guide.frame.minY)
        XCTAssertLessThan(guide.frame.maxY, watering.frame.minY)
        XCTAssertLessThan(watering.frame.maxY, memo.frame.minY)
        XCTAssertFalse(app.textFields["plant.detail.nickname"].exists)
        XCTAssertFalse(app.tables.firstMatch.exists, "detail must not regress to Form")
        attachDetailFrameReceipt(in: app)
        attachScreenshot(named: "collection-detail-402x874")

        app.buttons["plant.detail.edit"].tap()
        XCTAssertTrue(app.textFields["plant.detail.nickname"].waitForExistence(timeout: 5))
        let species = app.staticTexts["plant.detail.species"]
        XCTAssertTrue(species.exists)
        XCTAssertEqual(species.label, "Monstera deliciosa")
        XCTAssertFalse(species.label.unicodeScalars.contains("\u{2060}"))
    }

    func testFigmaSymptomRemedyExpandsStructuredEducationalGuidance() {
        let app = figmaCollectionApp()
        app.launch()

        waitForFigmaCollectionFixture(in: app)
        XCTAssertTrue(app.buttons["collection.row.0"].exists)
        app.buttons["collection.row.0"].tap()
        let remedyLink = app.buttons["plant.detail.remedy"]
        XCTAssertTrue(remedyLink.waitForExistence(timeout: 5))
        let detailScroll = app.scrollViews["plant.detail.screen"]
        detailScroll.swipeUp()
        XCTAssertTrue(remedyLink.isHittable)
        remedyLink.tap()

        XCTAssertTrue(app.scrollViews["remedy.screen"].waitForExistence(timeout: 5))
        XCTAssertTrue(app.staticTexts["증상 대처법"].exists)
        let remedyBack = app.buttons["remedy.back"]
        let collectionTab = assertCollectionPersistentTabBar(
            in: app,
            controls: [remedyBack]
        )
        assertRemedyChromeAndExpandedCardMatchReference(in: app)
        assertRemedyExpandedContent(in: app)
        attachRemedyFrameReceipt(in: app)
        attachScreenshot(named: "collection-remedy-402x874")

        assertRemedySecondSymptomCanReturn(
            in: app,
            remedyBack: remedyBack,
            collectionTab: collectionTab
        )
    }

    func testFigmaTrueEmptyIsDistinctFromSearchEmptyAndOffersBothRoutes() {
        let app = figmaCollectionApp(empty: true)
        app.launch()

        waitForFigmaCollectionFixture(in: app, empty: true)
        XCTAssertTrue(app.images["collection.empty.illustration"].exists)
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
            "첫 번째 반려식물을 등록하고 성장기를 남겨보세요!"
        )
        XCTAssertEqual(app.buttons["collection.empty.camera"].label, "사진으로 식별하기")
        XCTAssertEqual(app.buttons["collection.empty.manual"].label, "직접 등록하기")
        XCTAssertFalse(app.staticTexts["검색 결과가 없어요"].exists)
        XCTAssertFalse(app.buttons["collection.search.action"].exists)
        XCTAssertFalse(app.textFields["collection.search"].exists)
        assertCollectionEmptyGeometryMatchesReference(in: app)
        assertEmptyRoutesRemainAvailable(in: app)
    }
}
