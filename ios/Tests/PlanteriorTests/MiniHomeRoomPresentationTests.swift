import CoreGraphics
import Foundation
@testable import Planterior
import PlanteriorDomain
import Testing
import UIKit

@MainActor
struct MiniHomeRoomPresentationTests {
    @Test
    func homeFloatingActionsUseReferencePlacementAndUpSymbols() {
        #expect(HomeMiniRoomActionStyle.decorateSymbol == "chair.lounge.fill")
        #expect(HomeMiniRoomActionStyle.exportSymbol == "arrow.up.to.line")
    }

    @Test
    func homeRoomUsesAuthoritativeVerticalCropAndMaterial() throws {
        let room = try image(for: .homeRoom)

        #expect(room.cgImage?.width == 370)
        #expect(room.cgImage?.height == 326)
        #expect(try referenceRGB(in: room, at: CGPoint(x: 250, y: 100)) == [138, 137, 133])
        #expect(try referenceRGB(in: room, at: CGPoint(x: 185, y: 300)) == [224, 210, 192])
    }

    @Test
    func roomBaseAndPlantPropsCarryExpectedRasterAndAlphaContracts() throws {
        let base = try image(for: .roomBase)
        #expect(base.cgImage?.width == 358)
        #expect(base.cgImage?.height == 330)
        #expect(try pngData(for: .roomBase).starts(with: pngSignature))
        #expect(try referenceRGB(in: base, at: CGPoint(x: 10, y: 10)) == [163, 160, 157])
        #expect(try referenceRGB(in: base, at: CGPoint(x: 180, y: 50)) == [86, 84, 88])
        #expect(try referenceRGB(in: base, at: CGPoint(x: 50, y: 250)) == [213, 187, 157])
        #expect(try referenceRGB(in: base, at: CGPoint(x: 180, y: 300)) == [215, 195, 171])

        let propAssets = MiniRoomPlantPresentation.assets
            + MiniRoomPlantPresentation.referenceTrayAssets
        for asset in propAssets {
            let data = try pngData(for: asset)
            let prop = try image(for: asset)
            let alpha = try alphaCounts(in: prop)

            #expect(data.starts(with: pngSignature), "invalid PNG: \(asset)")
            #expect(alpha.transparent > alpha.total / 5, "boxed prop: \(asset)")
            #expect(alpha.opaque > alpha.total / 20, "empty prop: \(asset)")
            #expect(alpha.transparent + alpha.opaque <= alpha.total)
        }
    }

    @Test
    func persistedIDsResolveToTheSameVisualIdentityAfterAuthoritativeRemount() async throws {
        let fixture = try MiniHomeStoreFixture()
        let now = try Instant.parse("2026-08-11T01:00:00Z")
        let plantID = try PersonalPlantID.parse("figma-room-plant-0")
        let itemID = try ItemID.parse("item-mini-shelf")
        let placements = try [
            placement(id: "visual-plant", plantID: plantID, itemID: nil),
            placement(id: "visual-item", plantID: nil, itemID: itemID)
        ]
        let room = try MiniHome(
            id: MiniHomeID.parse("visual-room"),
            name: "visual-room",
            placements: placements,
            revision: .zero,
            updatedAt: now
        )
        let canonical = MiniHomeCanonicalEncoding.sortedPlacements(placements)
        let expectedAssets = canonical.map(MiniRoomPlacementPresentation.asset)
        let service = MiniHomeStoreServiceFake()
        let firstMount = fixture.store(service: service, operationIDs: ["visual-save"])
        await firstMount.mount(accountID: fixture.accountA, defaultDraft: room)
        await firstMount.save()

        let remounted = fixture.store(service: service, operationIDs: [])
        await remounted.mount(accountID: fixture.accountA, defaultDraft: nil)
        let restored = try #require(remounted.committed)
        #expect(restored.placements.map(\.id) == canonical.map(\.id))
        #expect(
            restored.placements.map(MiniRoomPlacementPresentation.asset)
                == expectedAssets
        )
        #expect(Set(expectedAssets) == [.roomPlant01, .storageItem05])
    }

    @Test
    func depthSizingAndHitTargetsAreIndependentFromVisibleAlpha() throws {
        let near = try placement(
            id: "near",
            plantID: PersonalPlantID.parse("near-plant"),
            itemID: nil,
            normalizedY: 0.9
        )
        let far = try placement(
            id: "far",
            plantID: PersonalPlantID.parse("far-plant"),
            itemID: nil,
            normalizedY: 0.2
        )

        let nearSize = MiniRoomPlacementMetrics.visualSize(for: near)
        let farSize = MiniRoomPlacementMetrics.visualSize(for: far)
        #expect(nearSize.width > farSize.width)
        #expect(nearSize.height > farSize.height)
        #expect(MiniRoomPlacementMetrics.hitSide >= 44)
        #expect(MiniRoomPlacementMetrics.hitSide > nearSize.height)
        #expect(MiniRoomPlacementMetrics.hitSide > farSize.height)
    }

    @Test
    func referencePlantFramesMatchTheRugScaleWithoutOpaqueCards() throws {
        let placements = try MiniHomeView.figmaReferencePlacements
        let canvas = CGSize(width: 358, height: 330)
        let bounds = placements.map {
            MiniRoomPlacementMetrics.visualBounds(for: $0, in: canvas)
        }
        let footprint = bounds.dropFirst().reduce(bounds[0]) { $0.union($1) }

        #expect(abs(footprint.width - 100.558) < 0.01)
        #expect(abs(footprint.height - 86.526) < 0.01)
        #expect(bounds.map(\.size) == [
            CGSize(width: 50, height: 72),
            CGSize(width: 36, height: 67),
            CGSize(width: 35, height: 56)
        ])
        #expect(bounds.allSatisfy { MiniRoomPlacementMetrics.hitSide >= $0.height })

        for asset in placements.map(MiniRoomPlacementPresentation.asset) {
            let alpha = try alphaCounts(in: image(for: asset))
            #expect(alpha.transparent > alpha.total / 5, "white card: \(asset)")
        }
    }

    private func placement(
        id: String,
        plantID: PersonalPlantID?,
        itemID: ItemID?,
        normalizedX: Double = 0.5,
        normalizedY: Double = 0.6,
        zIndex: Int = 0
    ) throws -> MiniHomePlacement {
        try MiniHomePlacement(
            id: PlacementID.parse(id),
            plantID: plantID,
            itemID: itemID,
            normalizedX: normalizedX,
            normalizedY: normalizedY,
            zIndex: zIndex
        )
    }

    private func image(for asset: FigmaAsset) throws -> UIImage {
        try #require(
            UIImage(named: asset.resourceName, in: .main, compatibleWith: nil)
        )
    }

    private func pngData(for asset: FigmaAsset) throws -> Data {
        let url = try #require(
            Bundle.main.url(
                forResource: asset.resourceName,
                withExtension: "png"
            )
        )
        return try Data(contentsOf: url)
    }

    private func alphaCounts(
        in image: UIImage
    ) throws -> AlphaCounts {
        let cgImage = try #require(image.cgImage)
        let width = cgImage.width
        let height = cgImage.height
        var bytes = [UInt8](repeating: 0, count: width * height * 4)
        let context = try #require(
            CGContext(
                data: &bytes,
                width: width,
                height: height,
                bitsPerComponent: 8,
                bytesPerRow: width * 4,
                space: CGColorSpaceCreateDeviceRGB(),
                bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue
            )
        )
        context.draw(cgImage, in: CGRect(x: 0, y: 0, width: width, height: height))
        let alpha = stride(from: 3, to: bytes.count, by: 4).map { bytes[$0] }
        return AlphaCounts(
            transparent: alpha.count { $0 == 0 },
            opaque: alpha.count { $0 == 255 },
            total: alpha.count
        )
    }

    private let pngSignature = Data([137, 80, 78, 71, 13, 10, 26, 10])
}
