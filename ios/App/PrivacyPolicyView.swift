import PlanteriorDesignSystem
import SwiftUI

struct PrivacyPolicyView: View {
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                Text("개인정보 처리방침")
                    .font(PlanteriorTypography.screenTitle)
                Text("사진은 식물 식별을 확인한 뒤에만 전송됩니다.")
                    .accessibilityIdentifier("privacy.disclosure.photo")
                Text("위치는 날씨 지역을 선택할 때만 사용합니다.")
                    .accessibilityIdentifier("privacy.disclosure.location")
                Text("삭제 요청은 서버가 계산한 범위와 7일 유예 기간을 따릅니다.")
            }
            .padding(20)
        }
        .background(PlanteriorPalette.canvas.color)
        .navigationTitle("개인정보")
        .toolbar {
            ToolbarItem(placement: .cancellationAction) {
                Button("닫기") { dismiss() }
            }
        }
        .accessibilityIdentifier("privacy.screen")
    }
}
