import XCTest

@MainActor
extension SettingsDeletionUITests {
    /// AX5 pushes `settings.region.open` below the fold, so scroll until it is
    /// actionable rather than sleeping and hoping the layout settles.
    func openAX5RegionSettings(in app: XCUIApplication) {
        openFigmaSettings(in: app)
        let openRegion = app.buttons["settings.region.open"]
        XCTAssertTrue(openRegion.waitForExistence(timeout: 10))
        let scroll = app.scrollViews["settings.screen"]
        var scrolls = 0
        while !openRegion.isHittable, scrolls < 6 {
            scroll.swipeUp()
            scrolls += 1
        }
        XCTAssertTrue(
            openRegion.isHittable,
            "settings.region.open must stay reachable at AX5"
        )
        openRegion.tap()
        XCTAssertTrue(
            app.scrollViews["region-settings.screen"]
                .waitForExistence(timeout: 10)
        )
        XCTAssertTrue(
            app.buttons["weather.use-current-location"]
                .waitForExistence(timeout: 5)
        )
    }

    /// Every AX5 Region row expands past its Large height, stays ordered, and
    /// keeps its complete Korean name inside its own frame. Returns the first
    /// row so the caller can anchor the header-boundary checks on it.
    @discardableResult
    func assertRegionAX5RowsKeepCompleteNames(
        in app: XCUIApplication
    ) -> XCUIElement {
        let rows = [
            ("manual-seoul", "서울특별시 강남구"),
            ("manual-busan", "경기도 성남시 분당구"),
            ("manual-haeundae", "부산광역시 해운대구")
        ].map { (app.buttons["weather.region-result.\($0.0)"], $0.1) }
        for (row, expectedName) in rows {
            let name = app.staticTexts[expectedName]
            XCTAssertTrue(row.exists)
            XCTAssertGreaterThan(
                row.frame.height,
                52,
                "AX5 Region rows must expand beyond their default Large height"
            )
            XCTAssertTrue(name.exists)
            XCTAssertEqual(name.label, expectedName)
            XCTAssertFalse(name.label.contains("\u{2026}"))
            XCTAssertTrue(
                row.frame.contains(name.frame),
                "the complete Region name must remain inside its AX5 row"
            )
        }
        XCTAssertLessThanOrEqual(rows[0].0.frame.maxY, rows[1].0.frame.minY)
        XCTAssertLessThanOrEqual(rows[1].0.frame.maxY, rows[2].0.frame.minY)

        let status = app.staticTexts["기준 지역"]
        XCTAssertTrue(status.exists)
        XCTAssertEqual(status.label, "기준 지역")
        XCTAssertFalse(status.label.contains("\u{2026}"))
        XCTAssertTrue(rows[0].0.frame.contains(status.frame))
        return rows[0].0
    }

    /// At AX5 the two-line title claimed the whole header band and left the
    /// back chevron sitting inside its vertical span with no space of its
    /// own. Non-intersection alone was not enough: assert the control keeps a
    /// >=44pt target in a reserved band the wrapped title never enters.
    func assertRegionHeaderReservesBackControlColumn(
        in app: XCUIApplication,
        title: XCUIElement
    ) {
        let back = app.buttons["weather.region.back"]
        XCTAssertTrue(back.exists)
        XCTAssertTrue(back.isHittable)
        XCTAssertGreaterThanOrEqual(back.frame.width, 44)
        XCTAssertGreaterThanOrEqual(back.frame.height, 44)
        XCTAssertFalse(
            back.frame.intersects(title.frame),
            "back=\(back.frame) title=\(title.frame)"
        )
        // The chevron previously floated WITHIN the wrapped title's rows -
        // horizontally beside it but vertically swallowed by it - so it read
        // as an orphaned glyph rather than a control. Its band must not be
        // contained inside the title's; the AX header stacks it above.
        XCTAssertLessThanOrEqual(
            back.frame.maxY,
            title.frame.minY + 0.5,
            "the back control must own a reserved band above the wrapped "
                + "title; back=\(back.frame) title=\(title.frame)"
        )
    }

    /// The second title line previously pushed the list down into the header
    /// boundary and clipped the first row mid-glyph. The first row must start
    /// fully below the whole header band, title included.
    func assertRegionFirstRowClearsHeaderBand(
        in app: XCUIApplication,
        title: XCUIElement
    ) {
        let body = app.scrollViews["region-settings.screen"]
        let back = app.buttons["weather.region.back"]
        let firstRow = app.buttons["weather.use-current-location"]
        XCTAssertTrue(body.exists)
        XCTAssertTrue(firstRow.exists)
        let headerMaxY = max(title.frame.maxY, back.frame.maxY)
        XCTAssertGreaterThanOrEqual(
            body.frame.minY,
            headerMaxY - 0.5,
            "region body starts inside the header band; "
                + "body=\(body.frame) headerMaxY=\(headerMaxY)"
        )
        XCTAssertGreaterThanOrEqual(
            firstRow.frame.minY,
            headerMaxY - 0.5,
            "the first region row is clipped by the header band; "
                + "row=\(firstRow.frame) headerMaxY=\(headerMaxY)"
        )
        // The card is the scroll view's first child, so a body that starts
        // above the header clips it mid-glyph even when the row's own frame
        // reads as tall enough.
        XCTAssertGreaterThanOrEqual(
            firstRow.frame.minY,
            body.frame.minY - 0.5,
            "the current-location card is clipped by the body's top edge; "
                + "row=\(firstRow.frame) body=\(body.frame)"
        )
        attachJSON(
            [
                "back": frameJSON(back.frame),
                "title": frameJSON(title.frame),
                "body": frameJSON(body.frame),
                "firstRow": frameJSON(firstRow.frame)
            ],
            named: "region-ax5-header-boundary"
        )
    }

    func frameJSON(_ frame: CGRect) -> [String: CGFloat] {
        [
            "minX": frame.minX, "minY": frame.minY,
            "width": frame.width, "height": frame.height,
            "maxX": frame.maxX, "maxY": frame.maxY
        ]
    }
}
