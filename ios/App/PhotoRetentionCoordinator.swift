import Foundation

struct RetainedPhoto: Equatable {
    let id: String
    let createdAt: Date
    let isRepresentative: Bool
}

enum PhotoRetentionCoordinator {
    static let lifetime: TimeInterval = 24 * 60 * 60

    static func expired(
        _ photos: [RetainedPhoto],
        now: Date
    ) -> [RetainedPhoto] {
        photos.filter {
            !$0.isRepresentative &&
                now.timeIntervalSince($0.createdAt) >= lifetime
        }
    }

    static func cleanup(
        _ photos: [RetainedPhoto],
        now: Date,
        delete: (RetainedPhoto) throws -> Void
    ) -> [String] {
        expired(photos, now: now).compactMap { photo in
            do {
                try delete(photo)
                return nil
            } catch {
                return photo.id
            }
        }
    }
}
