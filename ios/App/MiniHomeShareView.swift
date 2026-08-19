import Foundation
import PlanteriorData
import PlanteriorDesignSystem
import PlanteriorDomain
import SwiftUI

struct MiniHomeShareView: View {
    let room: MiniHome
    @Environment(\.dismiss) var dismiss
    @StateObject var repository: ShareRepository
    @State var renderResult: MiniHomeShareRenderResult?
    @State var showsShareSheet = false
    @State var activeLink: ProvisionalShareLink?
    @State var status = "공유 준비 중"

    init(room: MiniHome) {
        self.room = room
        _repository = StateObject(
            wrappedValue: ShareRepository(
                allowsProvisionalLinks: Self.allowsProvisionalLinks,
                now: Self.runtimeNow,
                randomBytes: Self.qaRandomBytes
            )
        )
    }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                preview
                Text("저장된 \(room.revision.rawValue)판")
                    .accessibilityIdentifier("minihome.share.revision")
                if let digest = renderResult?.digest {
                    Text("이미지 확인 코드 \(digest.prefix(12))")
                        .font(.caption)
                        .foregroundStyle(
                            PlanteriorPalette.textSecondary.color
                        )
                        .accessibilityIdentifier("minihome.share.digest")
                        .accessibilityValue(digest)
                }
                Text(status)
                    .accessibilityIdentifier("minihome.share.state")
                PlanteriorPrimaryButton("이미지 공유") {
                    shareImage()
                }
                .accessibilityIdentifier("minihome.share.image")
                PlanteriorPrimaryButton("공유 링크 만들기") {
                    createLink()
                }
                .accessibilityIdentifier("minihome.share.link")
                if activeLink?.revokedAt == nil, activeLink != nil {
                    PlanteriorPrimaryButton("링크 해제") {
                        revokeLink()
                    }
                    .accessibilityIdentifier("minihome.share.revoke")
                }
            }
            .padding(20)
            .accessibilityElement(children: .contain)
            .accessibilityIdentifier("minihome.share.screen")
        }
        .background(PlanteriorPalette.canvas.color)
        .navigationTitle("미니홈 공유")
        .toolbar {
            ToolbarItem(placement: .cancellationAction) {
                Button("닫기") { dismiss() }
            }
        }
        .task {
            renderResult = MiniHomeShareRenderer().render(room: room)
            status = renderResult == nil
                ? "이미지를 만들 수 없음"
                : "저장된 미니홈 이미지 준비됨"
        }
        .sheet(isPresented: $showsShareSheet) {
            if let image = renderResult?.image {
                ShareSheet(items: [image]) { result in
                    showsShareSheet = false
                    status = result == .cancelled
                        ? "공유 취소됨 · 오류 없음"
                        : "이미지 공유 완료"
                }
                .accessibilityIdentifier("minihome.share.sheet")
            }
        }
    }

    private var preview: some View {
        Group {
            if let image = renderResult?.image {
                Image(uiImage: image)
                    .resizable()
                    .scaledToFit()
                    .clipShape(RoundedRectangle(cornerRadius: 16))
            } else {
                ProgressView()
            }
        }
        .frame(maxWidth: .infinity, minHeight: 220)
        .accessibilityLabel("저장된 미니홈 공유 이미지")
        .accessibilityIdentifier("minihome.share.preview")
    }
}
