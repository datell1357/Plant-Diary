import PlanteriorDomain

extension MilestoneProgressView {
    func queueOffline() {
        queue("todo16-offline-1", kind: .watering)
    }

    func submit(_ rawID: String, kind: ProgressionEventKind) {
        guard let eventID = try? OperationID.parse(rawID) else {
            return
        }
        switch repository.submit(eventID: eventID, kind: kind) {
        case .applied: lastOutcome = "승인된 이벤트가 반영됨"
        case .duplicate: lastOutcome = "중복 이벤트 · 추가 경험치 없음"
        case let .denied(reason): lastOutcome = "거부됨 · \(reason)"
        case .unavailable: lastOutcome = "진행 연동을 사용할 수 없음"
        default: break
        }
    }

    func queue(_ rawID: String, kind: ProgressionEventKind) {
        guard let eventID = try? OperationID.parse(rawID) else {
            return
        }
        if repository.queue(eventID: eventID, kind: kind) == .queued {
            lastOutcome = "오프라인 이벤트 대기 중"
        }
    }

    func claim(_ milestoneID: MilestoneID) {
        switch repository.claim(milestoneID) {
        case .claimed: lastOutcome = "보상을 받음"
        case .alreadyClaimed: lastOutcome = "이미 받은 보상"
        case let .denied(reason): lastOutcome = "보상 거부됨 · \(reason)"
        case .unavailable: lastOutcome = "보상 연동을 사용할 수 없음"
        default: break
        }
    }
}
