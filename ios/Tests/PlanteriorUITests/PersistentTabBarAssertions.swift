import XCTest

@MainActor
extension XCTestCase {
    static let persistentTabIdentifiers = [
        "tab.home",
        "tab.collection",
        "tab.camera",
        "tab.storage",
        "tab.settings"
    ]

    func assertSinglePersistentTabBar(
        in app: XCUIApplication,
        selected selectedIdentifier: String,
        file: StaticString = #filePath,
        line: UInt = #line
    ) {
        var tabs: [String: XCUIElement] = [:]
        for identifier in Self.persistentTabIdentifiers {
            let matches = app.buttons.matching(identifier: identifier)
            XCTAssertEqual(
                matches.count,
                1,
                "expected one persistent owner for \(identifier)",
                file: file,
                line: line
            )
            let tab = matches.element(boundBy: 0)
            XCTAssertTrue(tab.isHittable, "\(identifier) must be hittable", file: file, line: line)
            XCTAssertTrue(app.frame.intersects(tab.frame), file: file, line: line)
            tabs[identifier] = tab
        }

        let selected = Self.persistentTabIdentifiers.filter {
            tabs[$0]?.isSelected == true
        }
        XCTAssertEqual(selected, [selectedIdentifier], file: file, line: line)

        let materialHeight: CGFloat = 62
        let nativeBottomSafeArea: CGFloat = 34
        let materialMinY = app.frame.maxY - nativeBottomSafeArea - materialHeight
        if app.frame.size == CGSize(width: 402, height: 874) {
            XCTAssertEqual(materialMinY, 778, accuracy: 0.5, file: file, line: line)
        }
        for identifier in Self.persistentTabIdentifiers where identifier != "tab.camera" {
            guard let tab = tabs[identifier] else { continue }
            XCTAssertGreaterThanOrEqual(tab.frame.minY, materialMinY, file: file, line: line)
            XCTAssertLessThanOrEqual(
                tab.frame.maxY,
                materialMinY + materialHeight,
                file: file,
                line: line
            )
        }
        if let camera = tabs["tab.camera"] {
            XCTAssertEqual(camera.frame.height, 52, accuracy: 0.5, file: file, line: line)
            XCTAssertLessThan(camera.frame.minY, materialMinY + 8, file: file, line: line)
            XCTAssertLessThanOrEqual(
                camera.frame.maxY,
                materialMinY + materialHeight,
                file: file,
                line: line
            )
        }
    }

    func assertNoPersistentAppTabBar(
        in app: XCUIApplication,
        file: StaticString = #filePath,
        line: UInt = #line
    ) {
        for identifier in Self.persistentTabIdentifiers {
            XCTAssertEqual(
                app.buttons.matching(identifier: identifier).count,
                0,
                "full-screen surface must not expose \(identifier)",
                file: file,
                line: line
            )
        }
    }
}
