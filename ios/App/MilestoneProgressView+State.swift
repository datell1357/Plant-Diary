import Foundation
import PlanteriorData
import PlanteriorDomain
import SwiftUI

extension MilestoneProgressView {
    static var runtimeNow: Instant? {
        #if DEBUG
            if let value = ProcessInfo.processInfo.environment[
                "QA_PROGRESS_NOW"
            ] {
                return try? Instant.parse(value)
            }
        #endif
        return try? Instant.parse("2026-08-11T00:00:00Z")
    }

    static var allowsLocalAuthoritativeService: Bool {
        #if DEBUG
            return ProcessInfo.processInfo.environment[
                "QA_PROGRESS_FIXTURE"
            ] == "1"
        #else
            return false
        #endif
    }

    static var showsQAControls: Bool {
        #if DEBUG
            return ProcessInfo.processInfo.environment[
                "QA_PROGRESS_SHOW_CONTROLS"
            ] != "0"
        #else
            return false
        #endif
    }

    var effectiveSizeCategory: ContentSizeCategory {
        #if DEBUG
            if Self.requestsAX5 {
                return .accessibilityExtraExtraExtraLarge
            }
        #endif
        return sizeCategory
    }

    private static var requestsAX5: Bool {
        ProcessInfo.processInfo.environment[
            "QA_PROGRESS_SIZE_CATEGORY"
        ] == "AX5"
    }

    var accountID: AccountID? {
        #if DEBUG
            if let raw = ProcessInfo.processInfo.environment[
                "QA_PROGRESS_ACCOUNT_ID"
            ] {
                return try? AccountID.parse(raw)
            }
        #endif
        return auth.accountID
    }

    var publicDefinitions: [MilestoneDefinition] {
        repository.definitions
            .filter { $0.publicationState == .public }
            .sorted { $0.thresholdXP < $1.thresholdXP }
    }

    var syncText: String {
        guard repository.snapshot != nil else {
            return "진행 연동 준비 중"
        }
        return repository.pendingEvents.isEmpty
            ? "서버와 동기화됨"
            : "오프라인 변경 대기"
    }

    func state(for milestoneID: MilestoneID) -> MilestoneState {
        guard let snapshot = repository.snapshot else {
            return .current
        }
        return ProgressionCoordinator.state(
            milestoneID: milestoneID,
            snapshot: snapshot
        )
    }

    func title(for milestoneID: MilestoneID) -> String {
        switch milestoneID.rawValue {
        case "registration-1": "첫 식물 등록"
        case "watering-1": "물주기 루틴"
        case "minihome-1": "미니홈 꾸미기"
        case "sharing-1": "첫 공유"
        default: "꾸미기 마일스톤"
        }
    }

    func stateText(_ state: MilestoneState) -> String {
        switch state {
        case .current: "진행 중"
        case .earned: "달성 · 보상 받기 가능"
        case .claimed: "보상 받음"
        }
    }
}
