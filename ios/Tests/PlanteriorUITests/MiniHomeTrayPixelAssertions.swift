import CoreGraphics
import UIKit
import XCTest

@MainActor
extension MiniHomeFigmaUITests {
    func assertDefaultCanvasHasNoPlacementBasePixels(in app: XCUIApplication) {
        let sample = renderedBlackPixelSample(
            screenshot: app.screenshot(),
            frame: app.otherElements["minihome.editor.canvas"].frame,
            identifier: "minihome.editor.canvas.default"
        )
        XCTAssertEqual(
            sample.nearBlackPixels,
            0,
            "the default live canvas must not paint placement-image bases"
        )
        attachJSON(
            [
                "identifier": sample.identifier,
                "nearBlackPixels": sample.nearBlackPixels,
                "totalPixels": sample.totalPixels
            ],
            named: "minihome-default-canvas-pixels"
        )
    }

    func renderedBlackPixelSample(
        screenshot: XCUIScreenshot,
        frame: CGRect,
        identifier: String
    ) -> MiniHomeTrayPixelSample {
        guard let crop = screenshotCrop(screenshot, frame: frame) else {
            XCTFail("\(identifier) must crop from the AX5 screenshot")
            return .failed(identifier: identifier)
        }
        guard let pixels = premultipliedPixels(crop) else {
            XCTFail("\(identifier) must decode as premultiplied RGBA")
            return .failed(identifier: identifier)
        }
        let nearBlackPixels = stride(from: 0, to: pixels.count, by: 4).count {
            pixels[$0] <= 8
                && pixels[$0 + 1] <= 8
                && pixels[$0 + 2] <= 8
                && pixels[$0 + 3] > 0
        }
        return MiniHomeTrayPixelSample(
            identifier: identifier,
            nearBlackPixels: nearBlackPixels,
            totalPixels: crop.width * crop.height
        )
    }

    private func screenshotCrop(
        _ screenshot: XCUIScreenshot,
        frame: CGRect
    ) -> CGImage? {
        guard let image = screenshot.image.cgImage else { return nil }
        let screenshotSize = screenshot.image.size
        let scaleX = CGFloat(image.width) / screenshotSize.width
        let scaleY = CGFloat(image.height) / screenshotSize.height
        return image.cropping(to: CGRect(
            x: frame.minX * scaleX,
            y: frame.minY * scaleY,
            width: frame.width * scaleX,
            height: frame.height * scaleY
        ).integral)
    }

    private func premultipliedPixels(_ image: CGImage) -> [UInt8]? {
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
            return nil
        }
        context.draw(
            image,
            in: CGRect(x: 0, y: 0, width: image.width, height: image.height)
        )
        return pixels
    }
}

struct MiniHomeTrayPixelSample {
    let identifier: String
    let nearBlackPixels: Int
    let totalPixels: Int

    static func failed(identifier: String) -> Self {
        Self(identifier: identifier, nearBlackPixels: .max, totalPixels: 1)
    }
}
