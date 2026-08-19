import Foundation
import PlanteriorDomain

extension MiniHomeShareView {
    func shareImage() {
        guard renderResult != nil else {
            status = "이미지를 만들 수 없음"
            return
        }
        #if DEBUG
            if ProcessInfo.processInfo.environment[
                "QA_SHARE_SHEET_RESULT"
            ] == "cancelled" {
                status = "공유 취소됨 · 오류 없음"
                return
            }
        #endif
        showsShareSheet = true
    }

    func createLink() {
        guard let renderResult else {
            status = "이미지를 만들 수 없음"
            return
        }
        let outcome = repository.createLink(
            snapshot: renderResult.snapshot,
            digest: renderResult.digest,
            online: Self.isOnline
        )
        switch outcome {
        case let .created(link):
            activeLink = link
            status = "30일 공유 링크 생성됨"
        case .offline:
            status = "오프라인 · 이미지 공유만 가능"
        case .unavailable:
            status = "링크 연동 준비 중"
        default:
            status = "링크를 만들 수 없음"
        }
    }

    func revokeLink() {
        guard let activeLink else { return }
        switch repository.revoke(activeLink.id) {
        case let .revoked(link), let .alreadyRevoked(link):
            self.activeLink = link
            status = "공유 링크 해제됨"
        default:
            status = "링크를 해제할 수 없음"
        }
    }
}
