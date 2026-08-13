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

    @Test
    func cancellationAndDeclinedAcknowledgementPerformNoTransfer() async throws {
        let transfer = PhotoTransferFake()
        let coordinator = PhotoConsentCoordinator(transfer: transfer)
        let photo = try PhotoImagePipeline().normalize(
            fixture(width: 300, height: 300)
        )

        await coordinator.review(photo)
        await coordinator.cancelSelection()
        #expect(await coordinator.hasDraft() == false)
        await coordinator.review(photo)
        await coordinator.declineAcknowledgement()
        #expect(await coordinator.hasDraft())

        #expect(await transfer.requestCount == 0)
    }

    @Test
    func acknowledgementTransfersReviewedPhotoExactlyOnce() async throws {
        let transfer = PhotoTransferFake()
        let coordinator = PhotoConsentCoordinator(transfer: transfer)
        let photo = try PhotoImagePipeline().normalize(
            fixture(width: 300, height: 300)
        )

        await coordinator.review(photo)
        await coordinator.acknowledgeAndTransfer()

        #expect(await transfer.requestCount == 1)
    }

    private func fixture(
        width: Int,
        height: Int,
        orientation: Int = 1
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
        CGImageDestinationAddImage(
            destination,
            image,
            [kCGImagePropertyOrientation: orientation] as CFDictionary
        )
        #expect(CGImageDestinationFinalize(destination))
        return data as Data
    }
}

private actor PhotoTransferFake: PhotoTransferRequesting {
    private(set) var requestCount = 0

    func transfer(_ photo: NormalizedPhoto) async {
        requestCount += 1
    }
}
