import XCTest

@MainActor
extension PlantCollectionUITests {
    func assertDeleteIsReachableAndRequiresConfirmation(
        in app: XCUIApplication
    ) {
        let detailScroll = app.scrollViews["plant.detail.screen"]
        let delete = app.buttons["plant.detail.delete"]
        scrollToHittable(delete, in: detailScroll)
        let collectionTabs = app.buttons.matching(
            NSPredicate(format: "identifier == %@", "tab.collection")
        )
        let tabBar = collectionTabs.firstMatch
        XCTAssertEqual(collectionTabs.count, 1)
        XCTAssertTrue(tabBar.isSelected)
        XCTAssertTrue(tabBar.isHittable)
        XCTAssertTrue(delete.isHittable)
        let materialMinY = app.frame.maxY - 62 - 34
        if app.frame.height == 874 {
            XCTAssertEqual(materialMinY, 778, accuracy: 0.5)
        }
        for control in [
            app.buttons["plant.detail.back"],
            app.buttons["plant.detail.edit"],
            delete
        ] {
            XCTAssertTrue(control.exists)
            XCTAssertLessThanOrEqual(control.frame.maxY, materialMinY)
        }
        XCTAssertFalse(
            delete.frame.intersects(tabBar.frame),
            "the delete action must clear the persistent tab bar"
        )
        assertDetailContentStaysBelowNavigationChrome(in: app)
        attachScreenshot(named: "collection-detail-delete-hittable")
        delete.tap()
        XCTAssertTrue(app.sheets.firstMatch.waitForExistence(timeout: 5))
        XCTAssertTrue(
            app.buttons["plant.detail.delete-confirm"].waitForExistence(timeout: 5)
        )
        app.buttons.matching(
            identifier: "plant.detail.delete-cancel"
        ).firstMatch.tap()
        XCTAssertFalse(app.sheets.firstMatch.exists)
        XCTAssertTrue(app.buttons["plant.detail.delete"].exists)

        assertCollectionReturnsAfterDelete(in: app)
    }

    /// The bottom guard above already proves content clears the tab material.
    /// This is its missing top twin: scrolled detail content must clip under
    /// the persistent nav chrome instead of overprinting the status bar, the
    /// nav title, and the back chevron.
    func assertDetailContentStaysBelowNavigationChrome(
        in app: XCUIApplication
    ) {
        let topBar = app.otherElements["plant.detail.top-bar"]
        let scroll = app.scrollViews["plant.detail.screen"]
        let back = app.buttons["plant.detail.back"]
        let edit = app.buttons["plant.detail.edit"]
        XCTAssertTrue(topBar.exists)
        XCTAssertTrue(scroll.exists)

        let chromeMaxY = topBar.frame.maxY
        XCTAssertGreaterThanOrEqual(
            scroll.frame.minY,
            chromeMaxY - 0.5,
            "plant.detail.screen starts above the nav chrome; "
                + "scroll=\(scroll.frame) chrome=\(topBar.frame)"
        )
        for control in [back, edit] {
            XCTAssertTrue(control.exists)
            XCTAssertLessThanOrEqual(
                control.frame.maxY,
                chromeMaxY + 0.5,
                "\(control.identifier) must stay inside the nav chrome band"
            )
            XCTAssertTrue(control.isHittable)
        }
        attachAXHierarchy(
            named: "collection-detail-nav-boundary",
            elements: [
                ("top-bar", topBar),
                ("scroll", scroll),
                ("back", back),
                ("edit", edit)
            ]
        )
        assertNavigationChromePixelsSurviveScrolling(in: app, topBar: topBar)
    }

    /// Geometry cannot prove clipping - XCUITest reports every row at its
    /// unclipped LAYOUT position, and hit-testing still resolves to the
    /// chrome even when content paints over it. The pixels are the only
    /// honest oracle: if the scroll view clips, the chrome band renders
    /// identically at the top of the list and after scrolling to the bottom.
    func assertNavigationChromePixelsSurviveScrolling(
        in app: XCUIApplication,
        topBar: XCUIElement
    ) {
        let scroll = app.scrollViews["plant.detail.screen"]
        let scrolledImage = chromeBandImage(in: app, band: topBar.frame)
        let scrolled = scrolledImage.pngData() ?? Data()
        let atTop = XCTNSPredicateExpectation(
            predicate: NSPredicate(format: "hittable == true"),
            object: app.images["plant.detail.hero"]
        )
        for _ in 0 ..< 8 where !app.images["plant.detail.hero"].isHittable {
            scroll.swipeDown(velocity: .fast)
        }
        XCTAssertEqual(XCTWaiter.wait(for: [atTop], timeout: 5), .completed)
        let unscrolledImage = chromeBandImage(in: app, band: topBar.frame)
        let unscrolled = unscrolledImage.pngData() ?? Data()
        for (name, image) in [
            ("collection-nav-band-scrolled", scrolledImage),
            ("collection-nav-band-unscrolled", unscrolledImage)
        ] {
            let attachment = XCTAttachment(image: image)
            attachment.name = name
            attachment.lifetime = .keepAlways
            add(attachment)
        }
        XCTAssertEqual(
            scrolled,
            unscrolled,
            "scrolled detail content repaints the nav chrome band instead of "
                + "clipping beneath it; band=\(topBar.frame)"
        )
    }

    private func chromeBandImage(
        in app: XCUIApplication,
        band: CGRect
    ) -> UIImage {
        let full = app.screenshot().image
        let scale = full.size.width / app.frame.width
        let crop = CGRect(
            x: band.minX * scale,
            y: band.minY * scale,
            width: band.width * scale,
            height: band.height * scale
        )
        guard let cropped = full.cgImage?.cropping(to: crop) else {
            XCTFail("could not crop the nav chrome band \(band)")
            return UIImage()
        }
        return UIImage(cgImage: cropped)
    }

    func assertCollectionReturnsAfterDelete(in app: XCUIApplication) {
        let collectionTabs = app.buttons.matching(
            NSPredicate(format: "identifier == %@", "tab.collection")
        )
        let collectionTab = collectionTabs.firstMatch
        let collectionReturned = XCTNSPredicateExpectation(
            predicate: NSPredicate(format: "exists == true"),
            object: app.scrollViews["collection.screen"]
        )
        app.buttons["plant.detail.back"].tap()
        XCTAssertEqual(
            XCTWaiter.wait(for: [collectionReturned], timeout: 5),
            .completed
        )
        XCTAssertEqual(collectionTabs.count, 1)
        XCTAssertTrue(collectionTab.isSelected)
        XCTAssertTrue(collectionTab.isHittable)
        XCTAssertTrue(app.textFields["collection.search"].exists)
        XCTAssertTrue(app.buttons["collection.row.0"].exists)
    }
}
