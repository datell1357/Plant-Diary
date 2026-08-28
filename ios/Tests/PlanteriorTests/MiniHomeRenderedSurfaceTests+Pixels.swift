import CoreGraphics
import Foundation
@testable import Planterior
import SwiftUI
import Testing
import UIKit

extension MiniHomeRenderedSurfaceTests {
    func assertRenderedPlacementBasesAreTransparent(
        _ assets: [FigmaAsset]
    ) throws {
        for asset in assets {
            let rawImage = try #require(
                UIImage(named: asset.resourceName, in: .main, compatibleWith: nil)
            )
            let rawCGImage = try #require(rawImage.cgImage)
            let renderer = ImageRenderer(content: MiniRoomPlacementVisual(
                asset: asset,
                size: CGSize(width: rawCGImage.width, height: rawCGImage.height)
            ))
            renderer.scale = 1
            renderer.isOpaque = false
            let renderedImage = try #require(renderer.uiImage)
            let rawTrailingAlpha = try trailingAlphaCount(rawImage)
            let renderedTrailingAlpha = try trailingAlphaCount(renderedImage)
            print(
                "MINIHOME_ALPHA asset=\(asset.resourceName) "
                    + "raw=\(rawTrailingAlpha) rendered=\(renderedTrailingAlpha)"
            )
            #expect(
                renderedTrailingAlpha == 0,
                "rendered placement-image base: \(asset)"
            )
        }
    }

    func assertPaintedCopy(
        _ image: UIImage,
        differsFrom background: UIImage,
        at points: [CGPoint],
        radius: Int = 12,
        minimumChangedPixels: Int = 8
    ) {
        let paintedBounds = changedPixelBounds(image, background)
        for point in points {
            let changed = changedPixelCount(
                image,
                background,
                around: point,
                radius: radius
            )
            let diagnostic = "missing painted plant pixels at pinned point \(point): \(changed); "
                + "image=\(image.size) scale=\(image.scale) "
                + "pixelBounds=\(String(describing: paintedBounds))"
            #expect(
                changed >= minimumChangedPixels,
                "\(diagnostic)"
            )
        }
    }

    private func changedPixelBounds(
        _ image: UIImage,
        _ background: UIImage
    ) -> CGRect? {
        guard let foregroundPixels = pixels(image),
              let backgroundPixels = pixels(background),
              foregroundPixels.width == backgroundPixels.width,
              foregroundPixels.height == backgroundPixels.height
        else {
            return nil
        }
        var bounds = CGRect.null
        for pixelY in 0 ..< foregroundPixels.height {
            for pixelX in 0 ..< foregroundPixels.width {
                let offset = (pixelY * foregroundPixels.width + pixelX) * 4
                let difference = (0 ..< 3).reduce(0) {
                    $0 + abs(
                        Int(foregroundPixels.bytes[offset + $1])
                            - Int(backgroundPixels.bytes[offset + $1])
                    )
                }
                if difference > 30 {
                    bounds = bounds.union(
                        CGRect(x: pixelX, y: pixelY, width: 1, height: 1)
                    )
                }
            }
        }
        return bounds.isNull ? nil : bounds
    }

    private func changedPixelCount(
        _ image: UIImage,
        _ background: UIImage,
        around point: CGPoint,
        radius: Int
    ) -> Int {
        guard let foregroundPixels = pixels(image),
              let backgroundPixels = pixels(background),
              foregroundPixels.width == backgroundPixels.width,
              foregroundPixels.height == backgroundPixels.height
        else {
            Issue.record("rendered surfaces must have matching RGBA buffers")
            return 0
        }
        let xRange = max(0, Int(point.x) - radius) ... min(
            foregroundPixels.width - 1,
            Int(point.x) + radius
        )
        let yRange = max(0, Int(point.y) - radius) ... min(
            foregroundPixels.height - 1,
            Int(point.y) + radius
        )
        return yRange.reduce(into: 0) { count, pixelY in
            for pixelX in xRange {
                let offset = (pixelY * foregroundPixels.width + pixelX) * 4
                let difference = (0 ..< 3).reduce(0) {
                    $0 + abs(
                        Int(foregroundPixels.bytes[offset + $1])
                            - Int(backgroundPixels.bytes[offset + $1])
                    )
                }
                if difference > 30 {
                    count += 1
                }
            }
        }
    }

    private func trailingAlphaCount(_ image: UIImage) throws -> Int {
        let buffer = try #require(pixels(image))
        return (0 ..< buffer.height).reduce(into: 0) { count, row in
            let rowStart = row * buffer.width * 4
            count += ((buffer.width - 6) ..< buffer.width).count {
                buffer.bytes[rowStart + $0 * 4 + 3] > 0
            }
        }
    }

    private func pixels(_ image: UIImage) -> PixelBuffer? {
        guard let cgImage = image.cgImage else { return nil }
        var bytes = [UInt8](repeating: 0, count: cgImage.width * cgImage.height * 4)
        guard let context = CGContext(
            data: &bytes,
            width: cgImage.width,
            height: cgImage.height,
            bitsPerComponent: 8,
            bytesPerRow: cgImage.width * 4,
            space: CGColorSpaceCreateDeviceRGB(),
            bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue
        ) else {
            return nil
        }
        context.draw(cgImage, in: CGRect(x: 0, y: 0, width: cgImage.width, height: cgImage.height))
        return PixelBuffer(bytes: bytes, width: cgImage.width, height: cgImage.height)
    }
}

func referenceRGB(
    in image: UIImage,
    at point: CGPoint
) throws -> [UInt8] {
    let cgImage = try #require(image.cgImage)
    var bytes = [UInt8](
        repeating: 0,
        count: cgImage.width * cgImage.height * 4
    )
    let context = try #require(
        CGContext(
            data: &bytes,
            width: cgImage.width,
            height: cgImage.height,
            bitsPerComponent: 8,
            bytesPerRow: cgImage.width * 4,
            space: CGColorSpaceCreateDeviceRGB(),
            bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue
        )
    )
    context.draw(
        cgImage,
        in: CGRect(x: 0, y: 0, width: cgImage.width, height: cgImage.height)
    )
    let offset = (Int(point.y) * cgImage.width + Int(point.x)) * 4
    return Array(bytes[offset ..< offset + 3])
}

struct AlphaCounts {
    let transparent: Int
    let opaque: Int
    let total: Int
}

private struct PixelBuffer {
    let bytes: [UInt8]
    let width: Int
    let height: Int
}
