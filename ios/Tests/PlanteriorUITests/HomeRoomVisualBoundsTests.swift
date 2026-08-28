import UIKit
import XCTest

final class HomeRoomVisualBoundsTests: XCTestCase {
    func testCapturedWoodHighlightStillFindsRenderedCardBounds() throws {
        let expected = CGRect(x: 16, y: 138, width: 370, height: 326)

        let frame = try XCTUnwrap(
            HomeRoomVisualBounds.frame(from: fixture(cardFrame: expected))
        )

        XCTAssertEqual(frame, expected)
    }

    func testShiftedAndUndersizedCardsKeepTheirActualPixelBounds() throws {
        let reference = CGRect(x: 16, y: 138, width: 370, height: 326)
        let alteredFrames = [
            CGRect(x: 22, y: 138, width: 370, height: 326),
            CGRect(x: 16, y: 138, width: 340, height: 300)
        ]

        for expected in alteredFrames {
            let frame = try XCTUnwrap(
                HomeRoomVisualBounds.frame(from: fixture(cardFrame: expected))
            )
            XCTAssertEqual(frame, expected)
            XCTAssertNotEqual(frame, reference)
        }
    }

    private func fixture(cardFrame: CGRect) -> UIImage {
        let size = CGSize(width: 402, height: 874)
        let format = UIGraphicsImageRendererFormat()
        format.scale = 1
        format.opaque = true
        return UIGraphicsImageRenderer(size: size, format: format).image { context in
            UIColor(red: 252 / 255, green: 251 / 255, blue: 247 / 255, alpha: 1)
                .setFill()
            context.fill(CGRect(origin: .zero, size: size))

            UIColor(red: 231 / 255, green: 231 / 255, blue: 231 / 255, alpha: 1)
                .setFill()
            UIBezierPath(roundedRect: cardFrame, cornerRadius: 12).fill()

            // Exact near-threshold warm pixel observed in the captured room floor.
            UIColor(red: 213 / 255, green: 193 / 255, blue: 173 / 255, alpha: 1)
                .setFill()
            context.fill(
                CGRect(
                    x: cardFrame.minX,
                    y: cardFrame.midY,
                    width: cardFrame.width,
                    height: cardFrame.height / 3
                )
            )
        }
    }
}
