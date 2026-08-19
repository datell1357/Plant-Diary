public struct ApprovedProgressionEvent: Codable, Equatable, Sendable {
    public let id: OperationID
    public let ownerID: AccountID
    public let kind: ProgressionEventKind
    public let experiencePoints: Int
    public let approvedAt: Instant

    public init(
        id: OperationID,
        ownerID: AccountID,
        kind: ProgressionEventKind,
        experiencePoints: Int,
        approvedAt: Instant
    ) throws {
        guard experiencePoints > 0 else {
            throw ProgressionValidationError.invalidXP
        }
        self.id = id
        self.ownerID = ownerID
        self.kind = kind
        self.experiencePoints = experiencePoints
        self.approvedAt = approvedAt
    }

    public var fingerprint: String {
        "\(kind.rawValue):\(experiencePoints):\(approvedAt.rawValue)"
    }
}
