import XCTest

@MainActor
final class ShareUITests: XCTestCase, MiniHomeUITestSupport {
    func testCommittedImageLinkRevokeAndCancellation() {
        let app = shareApp()
        app.launch()
        openShare(in: app)

        let revision = app.staticTexts["minihome.share.revision"]
        let digest = app.staticTexts["minihome.share.digest"]
        XCTAssertEqual(revision.label, "저장된 1판")
        XCTAssertTrue(digest.waitForExistence(timeout: 5))
        let digestValue = digest.value as? String ?? ""

        tap("minihome.share.link", in: app)
        waitForShareState("30일 공유 링크 생성됨", in: app)
        tap("minihome.share.revoke", in: app)
        waitForShareState("공유 링크 해제됨", in: app)
        tap("minihome.share.image", in: app)
        waitForShareState("공유 취소됨 · 오류 없음", in: app)

        attachShareEvidence(digest: digestValue)
        attachScreenshot(named: "task-17-share")
    }

    func testShareControlsRemainReachableAtAX5() {
        let app = shareApp(ax5: true)
        app.launch()
        openShare(in: app)
        app.swipeUp()
        app.swipeUp()

        let image = app.buttons["minihome.share.image"]
        let link = app.buttons["minihome.share.link"]
        XCTAssertTrue(image.waitForExistence(timeout: 5))
        XCTAssertTrue(link.waitForExistence(timeout: 5))
        XCTAssertTrue(image.isHittable)
        XCTAssertGreaterThanOrEqual(image.frame.height, 44)
        attachScreenshot(named: "task-17-share-ax5")
    }
}
