public struct NotificationDelivery: Codable, Equatable, Sendable {
    public let id: NotificationDeliveryID
    public let plantID: PersonalPlantID?
    public let scheduledFor: Instant
    public let deliveredAt: Instant?
    public let status: DeliveryStatus
    public let deduplicationKey: String
    public let revision: Revision
}

public struct ContentAudit: Codable, Equatable, Sendable {
    public let id: String
    public let contentID: String
    public let actorID: String
    public let action: String
    public let changedAt: Instant
}

public struct IdentificationRequest: Codable, Equatable, Sendable {
    public let id: IdentificationRequestID
    public let temporaryOriginalPath: String
    public let createdAt: Instant
    public let expiresAt: Instant
    public let revision: Revision
}
