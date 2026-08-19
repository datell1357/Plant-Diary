public struct ProgressionReceipt: Codable, Equatable, Sendable {
    public let eventID: OperationID
    public let fingerprint: String

    public init(eventID: OperationID, fingerprint: String) {
        self.eventID = eventID
        self.fingerprint = fingerprint
    }
}
