import CoreGraphics
import Foundation
import ImageIO
import UniformTypeIdentifiers

public struct NormalizedPhoto: Codable, Equatable, Sendable {
    public let data: Data
    public let pixelWidth: Int
    public let pixelHeight: Int
    public let contentType: String

    public init(
        data: Data,
        pixelWidth: Int,
        pixelHeight: Int,
        contentType: String
    ) {
        self.data = data
        self.pixelWidth = pixelWidth
        self.pixelHeight = pixelHeight
        self.contentType = contentType
    }
}

public enum PhotoInputError: Error, Equatable, Sendable {
    case emptyAsset
    case assetTooLarge
    case corruptAsset
    case invalidDimensions
    case normalizationFailed
}

public struct PhotoImagePipeline: Sendable {
    public static let maximumBytes = 20 * 1024 * 1024
    public static let pixelRange = 256 ... 8192

    public init() {}

    public func normalize(_ data: Data) throws -> NormalizedPhoto {
        guard !data.isEmpty else {
            throw PhotoInputError.emptyAsset
        }
        guard data.count <= Self.maximumBytes else {
            throw PhotoInputError.assetTooLarge
        }
        guard let source = CGImageSourceCreateWithData(data as CFData, nil),
              let properties = CGImageSourceCopyPropertiesAtIndex(source, 0, nil)
              as? [CFString: Any]
        else {
            throw PhotoInputError.corruptAsset
        }
        return try normalize(source: source, properties: properties)
    }

    private func normalize(
        source: CGImageSource,
        properties: [CFString: Any]
    ) throws -> NormalizedPhoto {
        guard let width = properties[kCGImagePropertyPixelWidth] as? Int,
              let height = properties[kCGImagePropertyPixelHeight] as? Int
        else {
            throw PhotoInputError.corruptAsset
        }
        let orientation = properties[kCGImagePropertyOrientation] as? Int ?? 1
        let swapsAxes = [5, 6, 7, 8].contains(orientation)
        let outputWidth = swapsAxes ? height : width
        let outputHeight = swapsAxes ? width : height
        guard Self.pixelRange.contains(outputWidth),
              Self.pixelRange.contains(outputHeight)
        else {
            throw PhotoInputError.invalidDimensions
        }
        let image = try makeImage(source)
        return try encode(image)
    }

    private func makeImage(_ source: CGImageSource) throws -> CGImage {
        let options: [CFString: Any] = [
            kCGImageSourceCreateThumbnailFromImageAlways: true,
            kCGImageSourceCreateThumbnailWithTransform: true,
            kCGImageSourceThumbnailMaxPixelSize: Self.pixelRange.upperBound
        ]
        guard let image = CGImageSourceCreateThumbnailAtIndex(
            source,
            0,
            options as CFDictionary
        ) else {
            throw PhotoInputError.normalizationFailed
        }
        return image
    }

    private func encode(_ image: CGImage) throws -> NormalizedPhoto {
        let output = NSMutableData()
        guard let destination = CGImageDestinationCreateWithData(
            output,
            UTType.jpeg.identifier as CFString,
            1,
            nil
        ) else {
            throw PhotoInputError.normalizationFailed
        }
        CGImageDestinationAddImage(
            destination,
            image,
            [kCGImageDestinationLossyCompressionQuality: 0.9] as CFDictionary
        )
        guard CGImageDestinationFinalize(destination) else {
            throw PhotoInputError.normalizationFailed
        }
        guard output.length <= Self.maximumBytes else {
            throw PhotoInputError.assetTooLarge
        }
        return NormalizedPhoto(
            data: output as Data,
            pixelWidth: image.width,
            pixelHeight: image.height,
            contentType: "image/jpeg"
        )
    }
}

public protocol PhotoTransferRequesting: Sendable {
    func transfer(_ photo: NormalizedPhoto) async
}

public actor PhotoConsentCoordinator {
    private let transfer: any PhotoTransferRequesting
    private var draft: NormalizedPhoto?

    public init(transfer: any PhotoTransferRequesting) {
        self.transfer = transfer
    }

    public func review(_ photo: NormalizedPhoto) {
        draft = photo
    }

    public func cancelSelection() {
        draft = nil
    }

    public func declineAcknowledgement() {
        // The reviewed draft remains available when the user dismisses the notice.
    }

    public func acknowledgeAndTransfer() async {
        guard let draft else {
            return
        }
        await transfer.transfer(draft)
    }

    public func hasDraft() -> Bool {
        draft != nil
    }
}

public actor IdentificationDraftStore: PhotoTransferRequesting {
    public static let shared = IdentificationDraftStore()
    private var draft: NormalizedPhoto?
    private let defaults: UserDefaults
    private let storageKey = "identification.draft"

    public init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
        if let data = defaults.data(forKey: storageKey) {
            draft = try? JSONDecoder().decode(NormalizedPhoto.self, from: data)
        }
    }

    public init(suiteName: String) {
        let defaults = UserDefaults(suiteName: suiteName) ?? .standard
        self.defaults = defaults
        if let data = defaults.data(forKey: storageKey) {
            draft = try? JSONDecoder().decode(NormalizedPhoto.self, from: data)
        }
    }

    public func transfer(_ photo: NormalizedPhoto) {
        draft = photo
        defaults.set(try? JSONEncoder().encode(photo), forKey: storageKey)
    }

    public func load() -> NormalizedPhoto? {
        draft
    }

    public func clear() {
        draft = nil
        defaults.removeObject(forKey: storageKey)
    }
}
