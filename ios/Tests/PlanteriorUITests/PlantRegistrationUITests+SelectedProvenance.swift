import XCTest

@MainActor
extension PlantRegistrationProvenanceUITests {
    func testCuratedManualSelectionPersistsSourceLinkAfterRelaunch() {
        let accountID = "manual-care-selected"
        registerCuratedManualPlant(accountID: accountID)

        let collection = collectionApp(accountID: accountID)
        collection.launch()
        XCTAssertTrue(collection.buttons["collection.row.0"].waitForExistence(timeout: 5))
        collection.buttons["collection.row.0"].tap()
        let source = guideSource(in: collection)
        XCTAssertTrue(source.waitForExistence(timeout: 5))
        XCTAssertTrue(source.isHittable)
        XCTAssertEqual(
            source.label,
            "출처: 농촌진흥청 실내정원용 식물 · 공공데이터 15059042"
        )
        XCTAssertEqual(
            source.value as? String,
            "https://www.data.go.kr/data/15059042/openapi.do"
        )
        let provenance = guideProvenance(in: collection)
        let expectedProvenance = [
            "제공기관: 농촌진흥청",
            "데이터셋: 실내정원용 식물 (15059042)",
            "원문: https://www.data.go.kr/data/15059042/openapi.do",
            "변환 안내: 앱이 공개 데이터를 식물 관리 지침으로 요약해 표시했습니다."
        ].joined(separator: "\n")
        XCTAssertEqual(
            provenance.value as? String,
            expectedProvenance
        )
        attachScreenshot(collection, named: "manual-selected-source-detail-normal")
    }

    private func registerCuratedManualPlant(accountID: String) {
        let registration = manualRegistrationApp(
            accountID: accountID,
            resetCollection: true
        )
        registration.launch()
        let search = registration.textFields["registration.search"]
        XCTAssertTrue(search.waitForExistence(timeout: 5))
        search.tap()
        search.typeText("몬스테라")
        let option = registration.buttons[
            "registration.care-option.monstera-deliciosa"
        ]
        XCTAssertTrue(option.waitForExistence(timeout: 5))
        option.tap()
        XCTAssertEqual(option.value as? String, "선택됨")
        attachScreenshot(registration, named: "manual-curated-option-selected-normal")

        let name = registration.textFields["registration.name"]
        name.tap()
        name.typeText("우리 집 잎이")
        dismissKeyboard(in: registration)
        registration.buttons["registration.submit"].tap()
        XCTAssertTrue(
            registration.staticTexts["registration.saved"].waitForExistence(timeout: 5)
        )
        registration.terminate()
    }
}
