import Foundation
@testable import Planterior
import Testing
import UIKit

/// Typed bundle contract for the authenticated Figma display PNGs
/// (`asset-manifest.json`, C002). Every case must resolve to a real shipping
/// resource in `Bundle.main`; representative cases pin logical source geometry.
struct FigmaAssetContractTests {
    /// Logical (1x) source dimensions carried over from the Figma export evidence.
    private static let representativeSizes: [FigmaAsset: CGSize] = [
        .homeRoom: CGSize(width: 370, height: 326),
        .capturePhoto: CGSize(width: 386, height: 444),
        .collectionHero: CGSize(width: 370, height: 220),
        .roomHero: CGSize(width: 358, height: 330),
        .roomBase: CGSize(width: 358, height: 330),
        .storagePreview: CGSize(width: 362, height: 220)
    ]

    @Test
    func exposesExactlyFortySixUniqueResourceNames() {
        let cases = FigmaAsset.allCases

        #expect(cases.count == 46)
        #expect(Set(cases).count == 46)
        #expect(Set(cases.map(\.resourceName)).count == 46)
        #expect(cases.allSatisfy { !$0.resourceName.isEmpty })
    }

    @Test
    func everyCaseLoadsAValidImageFromTheMainBundle() {
        for asset in FigmaAsset.allCases {
            let image = UIImage(named: asset.resourceName, in: .main, compatibleWith: nil)

            #expect(image != nil, "missing bundled resource: \(asset.resourceName)")
            #expect((image?.size.width ?? 0) > 0, "zero width: \(asset.resourceName)")
            #expect((image?.size.height ?? 0) > 0, "zero height: \(asset.resourceName)")
        }
    }

    @Test
    func representativeAssetsMatchManifestSourceDimensions() {
        for (asset, expected) in Self.representativeSizes {
            let named = UIImage(named: asset.resourceName, in: .main, compatibleWith: nil)
            guard let image = named else {
                Issue.record("missing bundled resource: \(asset.resourceName)")
                continue
            }

            let logical = CGSize(
                width: image.size.width * image.scale,
                height: image.size.height * image.scale
            )

            #expect(logical == expected, "geometry drift: \(asset.resourceName)")
        }
    }

    @Test
    func resourceNamesAreStableRawValues() {
        #expect(FigmaAsset.homeRoom.rawValue == "FigmaHomeRoom")
        #expect(FigmaAsset.capturePhoto.rawValue == "FigmaCapturePhoto")
        #expect(FigmaAsset.collectionHero.rawValue == "FigmaCollectionHero")
        #expect(FigmaAsset.roomHero.rawValue == "FigmaRoomHero")
        #expect(FigmaAsset.roomBase.rawValue == "FigmaRoomBase")
        #expect(FigmaAsset.storagePreview.rawValue == "FigmaStoragePreview")
        #expect(FigmaAsset.allCases.allSatisfy { $0.resourceName == $0.rawValue })
        #expect(FigmaAsset.allCases.allSatisfy { $0.rawValue.hasPrefix("Figma") })
    }
}
