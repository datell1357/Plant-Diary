import CoreGraphics
import UIKit
import XCTest

extension HomeDashboardUITests {
    func assertRoomVisualGeometry(in app: XCUIApplication, state: String) {
        XCTAssertFalse(
            app.images["home.room.hero"].exists,
            "the decorative room base must not be a VoiceOver stop"
        )
        for identifier in ["home.room.decorate", "home.room.share"] {
            let control = app.buttons[identifier]
            XCTAssertTrue(control.exists)
            XCTAssertTrue(control.isHittable)
            XCTAssertGreaterThanOrEqual(control.frame.width.rounded(), 44)
            XCTAssertGreaterThanOrEqual(control.frame.height.rounded(), 44)
        }

        let screenshot = app.screenshot()
        guard let roomFrame = roomVisualFrame(from: screenshot) else {
            XCTFail("the rendered room container must be measurable from pixels")
            return
        }
        XCTAssertEqual(app.windows.firstMatch.frame.width, 402, accuracy: 1)
        XCTAssertEqual(roomFrame.minX, 16, accuracy: 1)
        XCTAssertEqual(roomFrame.minY, 138, accuracy: 1)
        XCTAssertEqual(roomFrame.width, 370, accuracy: 1)
        XCTAssertEqual(roomFrame.height, 326, accuracy: 1)

        guard let crop = roomCrop(from: screenshot, frame: roomFrame) else {
            XCTFail("the visible room crop must be available")
            return
        }
        assertRoomImageContent(crop)
        let attachment = XCTAttachment(image: UIImage(cgImage: crop))
        attachment.name = "home-room-visual-402-\(state)"
        attachment.lifetime = .keepAlways
        add(attachment)
    }

    func roomVisualFrame(in app: XCUIApplication) -> CGRect {
        guard let frame = roomVisualFrame(from: app.screenshot()) else {
            XCTFail("the rendered room container must be measurable from pixels")
            return .null
        }
        return frame
    }

    private func roomVisualFrame(from screenshot: XCUIScreenshot) -> CGRect? {
        HomeRoomVisualBounds.frame(from: screenshot)
    }

    private func roomCrop(
        from screenshot: XCUIScreenshot,
        frame: CGRect
    ) -> CGImage? {
        guard let image = screenshot.image.cgImage else {
            return nil
        }
        let screenSize = screenshot.image.size
        let scaleX = CGFloat(image.width) / screenSize.width
        let scaleY = CGFloat(image.height) / screenSize.height
        let pixelFrame = CGRect(
            x: frame.minX * scaleX,
            y: frame.minY * scaleY,
            width: frame.width * scaleX,
            height: frame.height * scaleY
        ).integral
        return image.cropping(to: pixelFrame)
    }

    private func assertRoomImageContent(_ image: CGImage) {
        var pixels = [UInt8](repeating: 0, count: image.width * image.height * 4)
        let bitmapInfo = CGBitmapInfo.byteOrder32Big.rawValue
            | CGImageAlphaInfo.premultipliedLast.rawValue
        guard let context = CGContext(
            data: &pixels,
            width: image.width,
            height: image.height,
            bitsPerComponent: 8,
            bytesPerRow: image.width * 4,
            space: CGColorSpaceCreateDeviceRGB(),
            bitmapInfo: bitmapInfo
        ) else {
            XCTFail("the room crop must expose RGBA pixels")
            return
        }
        context.draw(image, in: CGRect(x: 0, y: 0, width: image.width, height: image.height))

        var warmPixels = 0
        var minimumLuminance = 255
        var maximumLuminance = 0
        var quantizedColors = Set<Int>()
        for offset in stride(from: 0, to: pixels.count, by: 4) {
            let red = Int(pixels[offset])
            let green = Int(pixels[offset + 1])
            let blue = Int(pixels[offset + 2])
            let luminance = (red * 3 + green * 6 + blue) / 10
            minimumLuminance = min(minimumLuminance, luminance)
            maximumLuminance = max(maximumLuminance, luminance)
            if red > green + 8, green > blue + 8 {
                warmPixels += 1
            }
            quantizedColors.insert((red / 16) << 8 | (green / 16) << 4 | blue / 16)
        }

        XCTAssertGreaterThan(maximumLuminance - minimumLuminance, 60)
        XCTAssertGreaterThan(quantizedColors.count, 40)
        XCTAssertGreaterThan(
            warmPixels,
            image.width * image.height / 20,
            "the non-AX crop must contain the room's warm wood floor"
        )
    }
}
