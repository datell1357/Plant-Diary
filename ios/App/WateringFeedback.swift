enum WateringFeedback {
    case recorded
    case alreadyRecorded
    case failed
    case unavailableDate

    var title: String {
        switch self {
        case .recorded: "물 주기 완료를 기록했어요."
        case .alreadyRecorded: "오늘 물 주기는 이미 기록했어요."
        case .failed: "물 주기 완료를 기록하지 못했어요."
        case .unavailableDate: "현재 날짜를 확인하지 못했어요."
        }
    }

    var isFailure: Bool {
        switch self {
        case .recorded, .alreadyRecorded: false
        case .failed, .unavailableDate: true
        }
    }
}
