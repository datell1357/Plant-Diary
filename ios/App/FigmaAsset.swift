import SwiftUI

/// Typed handle for the authenticated Figma display PNGs shipped in
/// `App/Resources/Figma`. Raw values are the bundled resource base names, so a
/// case can never drift from the file it renders. Source of truth is the C002
/// `asset-manifest.json`; add a case there first, then here.
enum FigmaAsset: String, CaseIterable, Sendable {
    case homeAvatar = "FigmaHomeAvatar"
    case homeRoom = "FigmaHomeRoom"
    case homePlantMonstera = "FigmaHomePlantMonstera"
    case homePlantSnake = "FigmaHomePlantSnake"

    case captureBlurredBackground = "FigmaCaptureBlurredBackground"
    case captureCameraSimulation = "FigmaCaptureCameraSimulation"
    case captureCandidateMonstera = "FigmaCaptureCandidateMonstera"
    case captureCandidateAlternate = "FigmaCaptureCandidateAlternate"
    case capturePhoto = "FigmaCapturePhoto"
    case capturePreview = "FigmaCapturePreview"

    case collectionHero = "FigmaCollectionHero"
    case collectionEmptyAvatar = "FigmaCollectionEmptyAvatar"
    case collectionPlantIllustration = "FigmaCollectionPlantIllustration"
    case collectionPlant01 = "FigmaCollectionPlant01"
    case collectionPlant02 = "FigmaCollectionPlant02"
    case collectionPlant03 = "FigmaCollectionPlant03"
    case collectionPlant04 = "FigmaCollectionPlant04"
    case collectionPlant05 = "FigmaCollectionPlant05"

    case roomHero = "FigmaRoomHero"
    case roomPlant01 = "FigmaRoomPlant01"
    case roomPlant02 = "FigmaRoomPlant02"
    case roomPlant03 = "FigmaRoomPlant03"
    case roomPlant04 = "FigmaRoomPlant04"
    case roomPlant05 = "FigmaRoomPlant05"

    case storageContext = "FigmaStorageContext"
    case storageItem00 = "FigmaStorageItem00"
    case storageItem01 = "FigmaStorageItem01"
    case storageItem02 = "FigmaStorageItem02"
    case storageItem03 = "FigmaStorageItem03"
    case storageItem04 = "FigmaStorageItem04"
    case storageItem05 = "FigmaStorageItem05"
    case storageItem06 = "FigmaStorageItem06"
    case storageItem07 = "FigmaStorageItem07"
    case storageItem08 = "FigmaStorageItem08"
    case storageItem09 = "FigmaStorageItem09"
    case storageItem10 = "FigmaStorageItem10"
    case storageItem11 = "FigmaStorageItem11"
    case storageItem12 = "FigmaStorageItem12"
    case storageItem13 = "FigmaStorageItem13"
    case storagePreview = "FigmaStoragePreview"

    /// Bundled resource base name, without extension.
    var resourceName: String {
        rawValue
    }
}

extension Image {
    /// Builds a SwiftUI `Image` from the typed Figma asset contract.
    ///
    /// The Figma PNGs ship as loose bundle resources rather than in an asset
    /// catalog, so they are resolved through `UIImage(named:)` instead of the
    /// catalog-only `Image(_ name:)` initializer.
    init(_ asset: FigmaAsset) {
        if let image = UIImage(named: asset.resourceName) {
            self.init(uiImage: image)
        } else {
            self.init(asset.resourceName)
        }
    }
}
