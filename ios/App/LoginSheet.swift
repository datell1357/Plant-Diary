import AuthenticationServices
import PlanteriorDesignSystem
import SwiftUI

struct LoginSheet: View {
    @Environment(\.dismiss) private var dismiss
    @ObservedObject var auth: AuthRuntime

    var body: some View {
        VStack(spacing: 16) {
            Capsule()
                .fill(PlanteriorPalette.border.color)
                .frame(width: 36, height: 5)
            Text("계정으로 시작하기")
                .font(PlanteriorTypography.screenTitle)
            Text("내 식물과 관리 기록을 안전하게 동기화해요.")
                .font(PlanteriorTypography.body)
                .foregroundStyle(PlanteriorPalette.textSecondary.color)
                .multilineTextAlignment(.center)

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
            .frame(height: PlanteriorControl.minimumTarget)
            .accessibilityIdentifier("auth.apple")

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
                    .frame(maxWidth: .infinity)
                    .frame(minHeight: PlanteriorControl.minimumTarget)
            }
            .buttonStyle(.bordered)
            .accessibilityIdentifier("auth.google")

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
        .presentationDetents([.medium])
        .background(PlanteriorPalette.canvas.color)
    }
}

private extension UIApplication {
    var planteriorTopViewController: UIViewController? {
        connectedScenes
            .compactMap { $0 as? UIWindowScene }
            .flatMap(\.windows)
            .first(where: \.isKeyWindow)?
            .rootViewController
    }
}
