import CoreGraphics
import Foundation
import ImageIO
@testable import PlanteriorData
import Testing
import UniformTypeIdentifiers

struct PhotoInputTests {
    @Test
    func normalizesOrientationAndValidatesDimensions() throws {
        let pipeline = PhotoImagePipeline()
        let rotated = try fixture(width: 400, height: 300, orientation: 6)
        let output = try pipeline.normalize(rotated)

        #expect(output.pixelWidth == 300)
        #expect(output.pixelHeight == 400)
        #expect(output.data.count > 0)
        #expect(output.contentType == "image/jpeg")
    }

    @Test
    func normalizedJPEGContainsNoPrivateMetadata() throws {
        let input = try fixture(
            width: 400,
            height: 300,
            metadata: [
                kCGImagePropertyExifDictionary: [
                    kCGImagePropertyExifUserComment: "private-note"
                ],
                kCGImagePropertyGPSDictionary: [
                    kCGImagePropertyGPSLatitude: 37.5665,
                    kCGImagePropertyGPSLongitude: 126.9780
                ]
            ]
        )

        let output = try PhotoImagePipeline().normalize(input)

        let source = try #require(
            CGImageSourceCreateWithData(output.data as CFData, nil)
        )
        let properties = try #require(
            CGImageSourceCopyPropertiesAtIndex(source, 0, nil)
                as? [CFString: Any]
        )
        let exif = properties[kCGImagePropertyExifDictionary]
            as? [CFString: Any]
        let tiff = properties[kCGImagePropertyTIFFDictionary]
            as? [CFString: Any]
        #expect(exif?[kCGImagePropertyExifUserComment] == nil)
        #expect(properties[kCGImagePropertyGPSDictionary] == nil)
        #expect(properties[kCGImagePropertyIPTCDictionary] == nil)
        #expect(tiff?[kCGImagePropertyTIFFArtist] == nil)
        #expect(tiff?[kCGImagePropertyTIFFImageDescription] == nil)
    }

    @Test
    func normalizedJPEGFitsProxyPayloadLimit() throws {
        let input = try checkerboardFixture(width: 5000, height: 5000)
        #expect(input.count <= PhotoImagePipeline.maximumBytes)

        do {
            let output = try PhotoImagePipeline().normalize(input)
            #expect(output.data.count <= 4 * 1024 * 1024)
            #expect(max(output.pixelWidth, output.pixelHeight) <= 1600)
        } catch {
            #expect(error as? PhotoInputError == .assetTooLarge)
        }
    }

    @Test(arguments: [(255, 300), (300, 255), (8193, 300), (300, 8193)])
    func rejectsOutOfRangeDimensions(width: Int, height: Int) throws {
        let data = try fixture(width: width, height: height)
        #expect(throws: PhotoInputError.invalidDimensions) {
            try PhotoImagePipeline().normalize(data)
        }
    }

    @Test
    func rejectsEmptyCorruptAndOversizedAssets() {
        let pipeline = PhotoImagePipeline()
        #expect(throws: PhotoInputError.emptyAsset) {
            try pipeline.normalize(Data())
        }
        #expect(throws: PhotoInputError.corruptAsset) {
            try pipeline.normalize(Data("not-an-image".utf8))
        }
        #expect(throws: PhotoInputError.assetTooLarge) {
            try pipeline.normalize(Data(count: 20 * 1024 * 1024 + 1))
        }
    }

    func fixture(
        width: Int,
        height: Int,
        orientation: Int = 1,
        metadata: [CFString: Any] = [:]
    ) throws -> Data {
        let colorSpace = CGColorSpaceCreateDeviceRGB()
        let context = try #require(
            CGContext(
                data: nil,
                width: width,
                height: height,
                bitsPerComponent: 8,
                bytesPerRow: 0,
                space: colorSpace,
                bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue
            )
        )
        let image = try #require(context.makeImage())
        let data = NSMutableData()
        let destination = try #require(
            CGImageDestinationCreateWithData(
                data,
                UTType.jpeg.identifier as CFString,
                1,
                nil
            )
        )
        var properties = metadata
        properties[kCGImagePropertyOrientation] = orientation
        CGImageDestinationAddImage(destination, image, properties as CFDictionary)
        #expect(CGImageDestinationFinalize(destination))
        return data as Data
    }

    private func checkerboardFixture(width: Int, height: Int) throws -> Data {
        var pixels = Data(count: width * height * 4)
        pixels.withUnsafeMutableBytes { buffer in
            guard let bytes = buffer.bindMemory(to: UInt8.self).baseAddress else {
                return
            }
            for rowIndex in 0 ..< height {
                for columnIndex in 0 ..< width {
                    let offset = (rowIndex * width + columnIndex) * 4
                    let value: UInt8 = (columnIndex + rowIndex).isMultiple(of: 2) ? 0 : 255
                    bytes[offset] = value
                    bytes[offset + 1] = value
                    bytes[offset + 2] = value
                    bytes[offset + 3] = 255
                }
            }
        }
        let provider = try #require(CGDataProvider(data: pixels as CFData))
        let image = try #require(
            CGImage(
                width: width,
                height: height,
                bitsPerComponent: 8,
                bitsPerPixel: 32,
                bytesPerRow: width * 4,
                space: CGColorSpaceCreateDeviceRGB(),
                bitmapInfo: CGBitmapInfo(
                    rawValue: CGImageAlphaInfo.premultipliedLast.rawValue
                ),
                provider: provider,
                decode: nil,
                shouldInterpolate: false,
                intent: .defaultIntent
            )
        )
        let data = NSMutableData()
        let destination = try #require(
            CGImageDestinationCreateWithData(
                data,
                UTType.png.identifier as CFString,
                1,
                nil
            )
        )
        CGImageDestinationAddImage(destination, image, nil)
        #expect(CGImageDestinationFinalize(destination))
        return data as Data
    }
}
