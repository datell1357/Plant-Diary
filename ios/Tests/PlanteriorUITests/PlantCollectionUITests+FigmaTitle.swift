import XCTest

@MainActor
extension PlantCollectionFigmaUITests {
    /// The 도감 header and the plant rows share one padded content column. At
    /// AX5 an incompressible status pill made each row demand more width than
    /// the screen, so the whole column overflowed symmetrically: every element
    /// - the header included - rendered past BOTH screen edges, clipping the
    /// leading 나 and slicing the trailing search affordance.
    ///
    /// Anchoring the title against a row keeps this honest: they share the
    /// same container inset, so the row is the live definition of "inside the
    /// content column" without pinning a pixel or a font metric.
    func assertTitleStaysInsideContentColumn(in app: XCUIApplication) {
        let title = app.staticTexts["collection.title"]
        let row = app.buttons["collection.row.0"]
        let action = app.buttons["collection.search.action"]
        XCTAssertTrue(title.exists)
        XCTAssertTrue(row.exists)

        // Rows scroll vertically, so only HORIZONTAL containment is the
        // invariant here: nothing in the column may cross a screen edge.
        let screen = app.windows.element(boundBy: 0).frame
        let tolerance = 0.5
        for (name, element) in [("collection.title", title), ("collection.row.0", row)] {
            XCTAssertGreaterThanOrEqual(
                element.frame.minX,
                screen.minX - tolerance,
                "\(name) crosses the leading screen edge at AX5, "
                    + "was \(element.frame)"
            )
            XCTAssertLessThanOrEqual(
                element.frame.maxX,
                screen.maxX + tolerance,
                "\(name) crosses the trailing screen edge at AX5, "
                    + "was \(element.frame)"
            )
        }
        XCTAssertGreaterThanOrEqual(
            title.frame.minX,
            row.frame.minX - tolerance,
            "collection.title overflows the leading content inset at AX5"
        )
        XCTAssertLessThanOrEqual(
            title.frame.maxX,
            row.frame.maxX + tolerance,
            "collection.title overflows the trailing content inset at AX5"
        )
        XCTAssertFalse(
            title.frame.intersects(action.frame),
            "collection.title must not collide with the search affordance"
        )
    }
}
