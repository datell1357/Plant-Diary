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
                Text("사진 보관함은 사용자가 PhotosPicker에서 선택한 항목에만 접근합니다.")
                    .accessibilityIdentifier("privacy.disclosure.photo-access")
                Text("선택한 사진은 방향을 보정해 JPEG로 다시 만들며 위치·EXIF 등 원본 메타데이터를 제거합니다.")
                    .accessibilityIdentifier("privacy.disclosure.metadata")
                Text("확인한 사진 초안은 기기 내부의 파일 보호가 적용된 캐시에 보관합니다.")
                    .accessibilityIdentifier("privacy.disclosure.draft-cache")
                Text("대표 사진으로 저장하지 않은 초안은 생성 후 24시간이 지나면 삭제 대상이 됩니다.")
                    .accessibilityIdentifier("privacy.disclosure.retention")
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
