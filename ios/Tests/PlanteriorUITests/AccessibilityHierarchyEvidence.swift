import XCTest

@MainActor
extension XCTestCase {
    func attachAXHierarchy(
        named name: String,
        elements: [(String, XCUIElement)]
    ) {
        let stops = elements.map { name, element in
            [
                "name": name,
                "identifier": element.identifier,
                "label": element.label,
                "value": element.value as? String ?? "",
                "exists": String(element.exists),
                "frame": NSCoder.string(for: element.frame)
            ]
        }
        guard let data = try? JSONSerialization.data(
            withJSONObject: stops,
            options: [.prettyPrinted, .sortedKeys]
        ) else {
            XCTFail("AX hierarchy evidence could not be encoded")
            return
        }
        let attachment = XCTAttachment(
            data: data,
            uniformTypeIdentifier: "public.json"
        )
        attachment.name = name
        attachment.lifetime = .keepAlways
        add(attachment)
    }

    func assertAXTraversal(
        in root: XCUIElement,
        isExactly expectedIdentifiers: [String],
        file: StaticString = #filePath,
        line: UInt = #line
    ) {
        let expected = Set(expectedIdentifiers)
        let actual = root.descendants(matching: .any)
            .allElementsBoundByIndex
            .map(\.identifier)
            .filter(expected.contains)
        XCTAssertEqual(
            actual,
            expectedIdentifiers,
            "AX traversal must expose every expected stop once in paint order",
            file: file,
            line: line
        )
    }

    func assertStrictVerticalOrder(
        _ elements: [(String, XCUIElement)],
        file: StaticString = #filePath,
        line: UInt = #line
    ) {
        for (before, after) in zip(elements, elements.dropFirst()) {
            XCTAssertTrue(before.1.exists, "missing AX stop: \(before.0)", file: file, line: line)
            XCTAssertTrue(after.1.exists, "missing AX stop: \(after.0)", file: file, line: line)
            XCTAssertLessThanOrEqual(
                before.1.frame.minY,
                after.1.frame.minY,
                "AX order must follow paint order: \(before.0) before \(after.0)",
                file: file,
                line: line
            )
        }
    }
}

@MainActor
extension PlantCollectionFigmaUITests {
    func testCollectionAX5RowsKeepAtomicSpeciesAndStableOrder() {
        let app = figmaCollectionApp()
        app.launchEnvironment["QA_COLLECTION_SIZE_CATEGORY"] = "AX5"
        app.launchArguments += ["-AppleLanguages", "(ko)", "-AppleLocale", "ko_KR"]
        app.launch()

        waitForFigmaCollectionFixture(in: app)
        let rows = (0 ..< 5).map { app.buttons["collection.row.\($0)"] }
        XCTAssertTrue(rows[0].exists)
        XCTAssertEqual(rows[0].label, "몬몬이 (몬스테라)")
        XCTAssertFalse(rows[0].label.contains("\u{2026}"))
        assertStrictVerticalOrder(
            rows.enumerated().map { ("row.\($0.offset)", $0.element) }
        )
        assertAXTraversal(
            in: app,
            isExactly: (0 ..< 5).map { "collection.row.\($0)" }
        )
        attachAXHierarchy(
            named: "collection-ax5-order",
            elements: rows.enumerated().map { ("row.\($0.offset)", $0.element) }
        )
    }
}
