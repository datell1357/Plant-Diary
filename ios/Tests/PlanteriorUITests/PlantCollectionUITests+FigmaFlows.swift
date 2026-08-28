import XCTest

@MainActor
extension PlantCollectionFigmaUITests {
    func testFigmaKoreanAX5ListAndDetailRemainScrollable() {
        let app = figmaCollectionApp()
        app.launchArguments += [
            "-AppleLanguages", "(ko)",
            "-AppleLocale", "ko_KR",
            "-UIPreferredContentSizeCategoryName",
            "UICTContentSizeCategoryAccessibilityXXXL"
        ]
        app.launch()

        waitForFigmaCollectionFixture(in: app)
        let rows = (app.buttons["collection.row.0"], app.buttons["collection.row.1"])
        let add = app.buttons["collection.add"]
        let status = app.staticTexts["collection.status.0"]
        XCTAssertTrue(rows.0.exists && rows.1.exists)
        XCTAssertTrue(add.isHittable)
        assertCollectionTabBarContract(in: app, add: add)
        XCTAssertTrue(status.exists)
        assertFirstRowNameUsesCompleteAX5Frame(in: app, status: status)
        XCTAssertFalse(rows.0.frame.intersects(add.frame) || rows.1.frame.intersects(add.frame))
        XCTAssertGreaterThan(status.frame.width, status.frame.height)
        assertTitleStaysInsideContentColumn(in: app)
        attachScreenshot(named: "collection-list-korean-ax5")
        app.scrollViews["collection.screen"].swipeUp()
        XCTAssertTrue(rows.1.isHittable)
        rows.1.tap()
        XCTAssertTrue(app.scrollViews["plant.detail.screen"].waitForExistence(timeout: 5))
        XCTAssertTrue(app.buttons["watering.complete"].exists)
        XCTAssertTrue(app.buttons["plant.detail.remedy"].exists)
        let edit = app.buttons["plant.detail.edit"]
        edit.tap()
        assertPlantDetailSpeciesAccessibility(in: app)
        edit.tap()
        attachScreenshot(named: "collection-detail-korean-ax5")

        returnFromCollectionDetail(in: app, add: add)
    }

    func testFigmaReduceMotionLightCollectionRemainsUsable() {
        let app = figmaCollectionApp()
        app.launchEnvironment["QA_REDUCE_MOTION"] = "1"
        app.launch()

        waitForFigmaCollectionFixture(in: app)
        XCTAssertTrue(app.buttons["collection.add"].isHittable)
        attachScreenshot(named: "collection-list-light-reduce-motion")
    }

    func assertPlantDetailMemoGeometry(
        in app: XCUIApplication,
        memo: XCUIElement,
        memoCard: XCUIElement
    ) {
        let memoBody = app.staticTexts["plant.detail.memo.body"]
        XCTAssertTrue(memoBody.exists)
        XCTAssertEqual(
            memoBody.label,
            "최근에 새 잎이 돋아나기 시작했어요! 잎 끝이 마르지 않게 저녁마다 "
                + "습도 관리를 위한 스프레이를 분무해주고 있습니다. 🌿"
        )
        let topOffset: CGFloat = app.frame.height == 874 ? 0 : -15
        XCTAssertEqual(memoCard.frame.minX, 16, accuracy: 0.5)
        XCTAssertEqual(memoCard.frame.minY, 662.667 + topOffset, accuracy: 0.5)
        XCTAssertEqual(memoCard.frame.width, app.frame.width - 32, accuracy: 0.5)
        XCTAssertEqual(memoCard.frame.height, 112.333, accuracy: 0.1)
        XCTAssertEqual(memoCard.frame.maxY, 775 + topOffset, accuracy: 0.1)
        XCTAssertEqual(memoBody.frame.minX, 28, accuracy: 0.5)
        XCTAssertEqual(
            memoBody.frame.width,
            min(340, app.frame.width - 56),
            accuracy: 0.5
        )
        XCTAssertEqual(memoBody.frame.height, 58, accuracy: 0.5)
        XCTAssertEqual(
            memoBody.value as? String,
            "lines=3;ending=preserved"
        )
    }

    func assertRemedyExpandedContent(in app: XCUIApplication) {
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
        let firstCard = app.otherElements["remedy.card.0"]
        XCTAssertTrue(firstCard.frame.contains(firstAction.frame))
        XCTAssertLessThanOrEqual(firstAction.frame.height, 58)
        XCTAssertFalse(app.staticTexts["remedy.cause.1"].exists)
        XCTAssertLessThan(
            app.otherElements["remedy.card.1"].frame.height,
            app.otherElements["remedy.card.0"].frame.height
        )
    }

    func assertRemedySecondSymptomCanReturn(
        in app: XCUIApplication,
        remedyBack: XCUIElement,
        collectionTab: XCUIElement
    ) {
        app.buttons["remedy.symptom.1"].tap()
        let secondCause = app.staticTexts["remedy.cause.1"]
        XCTAssertTrue(secondCause.waitForExistence(timeout: 5))
        XCTAssertTrue(secondCause.label.contains("나타날 수 있어요."))
        XCTAssertTrue(app.staticTexts["remedy.action.1"].exists)

        let detailReturned = XCTNSPredicateExpectation(
            predicate: NSPredicate(format: "exists == true"),
            object: app.scrollViews["plant.detail.screen"]
        )
        remedyBack.tap()
        XCTAssertEqual(
            XCTWaiter.wait(for: [detailReturned], timeout: 5),
            .completed
        )
        XCTAssertTrue(collectionTab.isSelected)
        XCTAssertTrue(collectionTab.isHittable)
    }

    func assertEmptyRoutesRemainAvailable(in app: XCUIApplication) {
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
        attachEmptyFrameReceipt(in: app)
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

        assertManualRegistrationReturnsToEmptyCollection(in: app, manual: manual)
    }

    func assertManualRegistrationReturnsToEmptyCollection(
        in app: XCUIApplication,
        manual: XCUIElement
    ) {
        manual.tap()
        let registration = app.textFields["registration.search"]
        XCTAssertTrue(registration.waitForExistence(timeout: 5))
        let collectionTab = assertCollectionPersistentTabBar(
            in: app,
            controls: [app.buttons["registration.submit"]]
        )
        let collectionReturned = XCTNSPredicateExpectation(
            predicate: NSPredicate(format: "exists == true"),
            object: app.scrollViews["collection.screen"]
        )
        let back = app.navigationBars.buttons.firstMatch
        XCTAssertTrue(back.isHittable)
        back.tap()
        XCTAssertEqual(
            XCTWaiter.wait(for: [collectionReturned], timeout: 5),
            .completed
        )
        XCTAssertTrue(collectionTab.isSelected)
        XCTAssertTrue(app.images["collection.empty.illustration"].exists)
    }
}
