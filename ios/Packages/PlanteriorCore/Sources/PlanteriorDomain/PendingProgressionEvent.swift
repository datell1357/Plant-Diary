public struct PendingProgressionEvent: Codable, Equatable, Sendable {
    public let id: OperationID
    public let kind: ProgressionEventKind

    public init(id: OperationID, kind: ProgressionEventKind) {
        self.id = id
        self.kind = kind
    }
}
