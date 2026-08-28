@testable import Planterior
import Testing
import UIKit
import XCTest

@MainActor
struct MiniHomeLiveCompositionTests {
    @Test
    func editorCanvasGutterCentersWithoutClippingAtSupportedWidths() {
        #expect(MiniRoomReferenceMetrics.canvasGutter(availableWidth: 402) == 22)
        #expect(MiniRoomReferenceMetrics.canvasGutter(availableWidth: 390) == 16)
        #expect(MiniRoomReferenceMetrics.canvasGutter(availableWidth: 358) == 0)
    }

    @Test
    func trayAssetsNeedNoRuntimeBlackPixelMask() throws {
        for asset in MiniRoomPlantPresentation.referenceTrayAssets {
            let image = try #require(UIImage(named: asset.resourceName)?.cgImage)
            #expect(try opaqueBlackPixelCount(in: image) == 0)
        }
    }

    @Test
    func editorUsesPlantFreeRoomBaseAndCanonicalLivePlantsOnlyForFigmaQA() throws {
        #expect(MiniHomeEditorCanvas.baseAsset == .roomBase)
        #expect(MiniHomeEditorCanvas.canvasSize == CGSize(width: 358, height: 330))
        let roomBase = try #require(
            UIImage(named: MiniHomeEditorCanvas.baseAsset.resourceName)
        )
        let pixels = try #require(roomBase.cgImage)
        #expect(pixels.width == 358)
        #expect(pixels.height == 330)
        let canonical = try MiniHomeView.initialPlacements(
            environment: ["QA_MINIHOME_FIGMA_FIXTURE": "1"]
        )
        let referencePlacements = try MiniHomeView.figmaReferencePlacements
        #expect(canonical == referencePlacements)
        #expect(canonical.map(\.id.rawValue) == [
            "figma-room-placement-1",
            "figma-room-placement-2",
            "figma-room-placement-3"
        ])
        #expect(Set(canonical.map(\.id)).count == 3)
        #expect(canonical.allSatisfy { $0.plantID != nil && $0.itemID == nil })
        #expect(canonical.map(MiniRoomPlacementPresentation.asset) == [
            .roomPlant01, .roomPlant02, .roomPlant03
        ])
        let resolved = MiniRoomPlacementProjector.resolved(
            placements: canonical,
            in: MiniHomeEditorCanvas.canvasSize
        )
        #expect(resolved.map(\.visualSize) == [
            CGSize(width: 36, height: 67),
            CGSize(width: 50, height: 72),
            CGSize(width: 35, height: 56)
        ])
        #expect(
            MiniRoomPlantPresentation.referenceTrayAssets == [
                .roomTrayPlant03, .roomTrayPlant05, .roomTrayPlant04,
                .roomTrayPlant02, .roomTrayPlant01
            ]
        )
        let defaultPlacements = try MiniHomeView.initialPlacements(environment: [:])
        #expect(defaultPlacements.isEmpty)
    }

    private func opaqueBlackPixelCount(in image: CGImage) throws -> Int {
        var bytes = [UInt8](repeating: 0, count: image.width * image.height * 4)
        let context = try #require(CGContext(
            data: &bytes,
            width: image.width,
            height: image.height,
            bitsPerComponent: 8,
            bytesPerRow: image.width * 4,
            space: CGColorSpaceCreateDeviceRGB(),
            bitmapInfo: CGBitmapInfo.byteOrder32Big.rawValue
                | CGImageAlphaInfo.premultipliedLast.rawValue
        ))
        context.draw(
            image,
            in: CGRect(x: 0, y: 0, width: image.width, height: image.height)
        )
        var count = 0
        for offset in stride(from: 0, to: bytes.count, by: 4) {
            let isOpaqueBlack = bytes[offset] == 0
                && bytes[offset + 1] == 0
                && bytes[offset + 2] == 0
                && bytes[offset + 3] == 255
            if isOpaqueBlack {
                count += 1
            }
        }
        return count
    }
}
