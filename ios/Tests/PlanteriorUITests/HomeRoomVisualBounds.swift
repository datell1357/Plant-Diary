import CoreGraphics
import UIKit
import XCTest

/// Measures the Home room card from rendered pixels. The warm wood floor is a
/// stable feature of the shipped room surface, and the surrounding painted
/// card provides its actual bounds. No accessibility identifier or element is
/// introduced, preserving the decorative hero's VoiceOver-hidden contract.
enum HomeRoomVisualBounds {
    @MainActor
    static func frame(from screenshot: XCUIScreenshot) -> CGRect? {
        frame(from: screenshot.image)
    }

    static func frame(from image: UIImage) -> CGRect? {
        guard let cgImage = image.cgImage,
              let pixels = RGBAPixels(image: cgImage),
              let wood = pixels.longestWoodRun(),
              wood.length >= pixels.width / 2
        else {
            return nil
        }

        let seedColumn = wood.start + wood.length / 2
        let seedRow = wood.row
        let canvas = pixels.color(atColumn: 0, row: seedRow)
        guard !pixels.color(atColumn: seedColumn, row: seedRow).isCanvas(
            comparedTo: canvas
        ) else {
            return nil
        }

        let columns = paintedRange(
            through: seedColumn,
            limit: pixels.width,
            isPainted: { column in
                !pixels.color(atColumn: column, row: seedRow).isCanvas(
                    comparedTo: canvas
                )
            }
        )
        let rows = paintedRange(
            through: seedRow,
            limit: pixels.height,
            isPainted: { row in
                !pixels.color(atColumn: seedColumn, row: row).isCanvas(
                    comparedTo: canvas
                )
            }
        )
        return frame(
            columns: columns,
            rows: rows,
            screenshotSize: image.size,
            imageSize: CGSize(width: pixels.width, height: pixels.height)
        )
    }

    private static func paintedRange(
        through seed: Int,
        limit: Int,
        isPainted: (Int) -> Bool
    ) -> Range<Int> {
        var lowerBound = seed
        while lowerBound > 0, isPainted(lowerBound - 1) {
            lowerBound -= 1
        }
        var upperBound = seed + 1
        while upperBound < limit, isPainted(upperBound) {
            upperBound += 1
        }
        return lowerBound ..< upperBound
    }

    private static func frame(
        columns: Range<Int>,
        rows: Range<Int>,
        screenshotSize: CGSize,
        imageSize: CGSize
    ) -> CGRect? {
        guard columns.count > 0,
              rows.count > 0,
              screenshotSize.width > 0,
              screenshotSize.height > 0
        else {
            return nil
        }
        let scaleX = imageSize.width / screenshotSize.width
        let scaleY = imageSize.height / screenshotSize.height
        return CGRect(
            x: CGFloat(columns.lowerBound) / scaleX,
            y: CGFloat(rows.lowerBound) / scaleY,
            width: CGFloat(columns.count) / scaleX,
            height: CGFloat(rows.count) / scaleY
        )
    }
}

private struct RGBAPixels {
    let width: Int
    let height: Int
    let bytes: [UInt8]

    init?(image: CGImage) {
        width = image.width
        height = image.height
        var values = [UInt8](repeating: 0, count: width * height * 4)
        let bitmapInfo = CGBitmapInfo.byteOrder32Big.rawValue
            | CGImageAlphaInfo.premultipliedLast.rawValue
        guard let context = CGContext(
            data: &values,
            width: width,
            height: height,
            bitsPerComponent: 8,
            bytesPerRow: width * 4,
            space: CGColorSpaceCreateDeviceRGB(),
            bitmapInfo: bitmapInfo
        ) else {
            return nil
        }
        context.draw(image, in: CGRect(x: 0, y: 0, width: width, height: height))
        bytes = values
    }

    func color(atColumn column: Int, row: Int) -> RGBColor {
        let offset = (row * width + column) * 4
        return RGBColor(
            red: Int(bytes[offset]),
            green: Int(bytes[offset + 1]),
            blue: Int(bytes[offset + 2])
        )
    }

    func longestWoodRun() -> PixelRun? {
        var longest: PixelRun?
        for row in 0 ..< height {
            var runStart: Int?
            for column in 0 ... width {
                let isWood = column < width && color(
                    atColumn: column,
                    row: row
                ).isWarmWood
                if isWood, runStart == nil {
                    runStart = column
                } else if !isWood, let existingRunStart = runStart {
                    let run = PixelRun(
                        start: existingRunStart,
                        length: column - existingRunStart,
                        row: row
                    )
                    if run.length > (longest?.length ?? 0) {
                        longest = run
                    }
                    runStart = nil
                }
            }
        }
        return longest
    }
}

private struct PixelRun {
    let start: Int
    let length: Int
    let row: Int
}

private struct RGBColor {
    let red: Int
    let green: Int
    let blue: Int

    var isWarmWood: Bool {
        red > green + 8
            && green > blue + 8
            && green < 200
            && blue < 220
    }

    func isCanvas(comparedTo canvas: RGBColor) -> Bool {
        abs(red - canvas.red) <= 4
            && abs(green - canvas.green) <= 4
            && abs(blue - canvas.blue) <= 4
    }
}
