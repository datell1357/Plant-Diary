import AuthenticationServices
import PlanteriorDesignSystem
import SwiftUI

struct LoginSheet: View {
    @Environment(\.dismiss) private var dismiss
    @Environment(\.sizeCategory) private var sizeCategory
    @ObservedObject var auth: AuthRuntime

    var body: some View {
        ScrollView {
            VStack(spacing: 16) {
                Capsule()
                    .fill(PlanteriorPalette.border.color)
                    .frame(width: 36, height: 5)
                Text("로그인")
                    .font(PlanteriorTypography.pageTitle)
                    .foregroundStyle(PlanteriorPalette.textPrimary.color)
                    .accessibilityIdentifier("auth.title")
                    .accessibilityAddTraits(.isHeader)
                Text("소셜 계정으로 간편하게 시작하세요")
                    .font(PlanteriorTypography.body)
                    .foregroundStyle(PlanteriorPalette.textSecondary.color)
                    .multilineTextAlignment(.center)
                    .accessibilityIdentifier("auth.subtitle")

                // Figma §6.8 order: Google above Apple. The provider screens
                // themselves stay native (ASAuthorization / Google SDK).
                Button {
                    guard let controller = UIApplication.shared.planteriorTopViewController else {
                        return
                    }
                    Task {
                        await auth.signInWithGoogle(presenting: controller)
                        if auth.isSignedIn {
                            dismiss()
                        }
                    }
                } label: {
                    Label("Google로 계속하기", systemImage: "g.circle.fill")
                        .font(PlanteriorTypography.body.weight(.semibold))
                        .foregroundStyle(PlanteriorPalette.textPrimary.color)
                        .frame(maxWidth: .infinity)
                        .frame(minHeight: PlanteriorControl.primaryButtonHeight)
                }
                .buttonStyle(.plain)
                .background(PlanteriorPalette.surface.color)
                .clipShape(Capsule())
                .overlay {
                    Capsule().stroke(
                        PlanteriorPalette.border.color,
                        lineWidth: PlanteriorControl.hairline
                    )
                }
                .accessibilityIdentifier("auth.google")

                SignInWithAppleButton(.continue) { request in
                    auth.beginApple(request)
                } onCompletion: { result in
                    guard case let .success(authorization) = result else {
                        auth.cancelApple()
                        if case let .failure(error) = result {
                            if (error as? ASAuthorizationError)?.code != .canceled {
                                auth.reportAppleFailure()
                            }
                        }
                        return
                    }
                    Task {
                        await auth.completeApple(authorization)
                        if auth.isSignedIn {
                            dismiss()
                        }
                    }
                }
                .signInWithAppleButtonStyle(.black)
                .frame(minHeight: PlanteriorControl.primaryButtonHeight)
                .clipShape(Capsule())
                .accessibilityIdentifier("auth.apple")

                Text("로그인 시 서비스 이용 약관 및 개인정보 처리방침에 동의하는 것으로 간주됩니다.")
                    .font(PlanteriorTypography.caption)
                    .foregroundStyle(PlanteriorPalette.textTertiary.color)
                    .multilineTextAlignment(.center)

                if let errorMessage = auth.errorMessage {
                    Text(errorMessage)
                        .foregroundStyle(.red)
                        .accessibilityIdentifier("auth.error")
                }

                Button("취소", role: .cancel) {
                    dismiss()
                }
                .frame(minHeight: PlanteriorControl.minimumTarget)
                .accessibilityIdentifier("auth.cancel")
            }
            .padding(24)
        }
        .presentationDetents(
            sizeCategory.isAccessibilityCategory ? [.large] : [.medium]
        )
        .background(PlanteriorPalette.canvas.color)
    }
}

extension UIApplication {
    var planteriorTopViewController: UIViewController? {
        connectedScenes
            .compactMap { $0 as? UIWindowScene }
            .flatMap(\.windows)
            .first(where: \.isKeyWindow)?
            .rootViewController
    }
}
