import AuthenticationServices
import PlanteriorDesignSystem
import SwiftUI

struct LoginSheet: View {
    @ObservedObject var auth: AuthRuntime
    let onDismiss: () -> Void

    var body: some View {
        PlanteriorSheet(dismissIdentifier: "auth.cancel", onDismiss: onDismiss) {
            ScrollView {
                VStack(spacing: 0) {
                    Text("로그인")
                        .font(PlanteriorTypography.pageTitle)
                        .foregroundStyle(PlanteriorPalette.textPrimary.color)
                        .accessibilityIdentifier("auth.title")
                        .accessibilityAddTraits(.isHeader)

                    Text("소셜 계정으로 간편하게 시작하세요")
                        .font(PlanteriorTypography.body)
                        .foregroundStyle(PlanteriorPalette.textSecondary.color)
                        .multilineTextAlignment(.center)
                        .padding(.top, PlanteriorSpacing.large)
                        .accessibilityIdentifier("auth.subtitle")

                    // Figma provider order is Google above Apple. Invocation
                    // remains native (Google SDK / ASAuthorization).
                    Button {
                        guard let controller = UIApplication.shared.planteriorTopViewController else {
                            return
                        }
                        Task {
                            await auth.signInWithGoogle(presenting: controller)
                            if auth.isSignedIn {
                                onDismiss()
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
                    .padding(.top, PlanteriorSpacing.large)
                    .accessibilityIdentifier("auth.google")

                    SignInWithAppleButton(.continue) { request in
                        auth.beginApple(request)
                    } onCompletion: { result in
                        guard case let .success(authorization) = result else {
                            auth.cancelApple()
                            if case let .failure(error) = result,
                               (error as? ASAuthorizationError)?.code != .canceled {
                                auth.reportAppleFailure()
                            }
                            return
                        }
                        Task {
                            await auth.completeApple(authorization)
                            if auth.isSignedIn {
                                onDismiss()
                            }
                        }
                    }
                    .signInWithAppleButtonStyle(.black)
                    .frame(minHeight: PlanteriorControl.primaryButtonHeight)
                    .clipShape(Capsule())
                    .padding(.top, PlanteriorSpacing.small)
                    .accessibilityIdentifier("auth.apple")

                    Text("로그인 시 서비스 이용 약관 및 개인정보 처리방침에 동의하는 것으로 간주됩니다.")
                        .font(PlanteriorTypography.caption)
                        .foregroundStyle(PlanteriorPalette.textTertiary.color)
                        .multilineTextAlignment(.center)
                        .padding(.top, PlanteriorSpacing.large)

                    if let errorMessage = auth.errorMessage {
                        Text(errorMessage)
                            .foregroundStyle(.red)
                            .padding(.top, PlanteriorSpacing.small)
                            .accessibilityIdentifier("auth.error")
                    }
                }
                .padding(.horizontal, PlanteriorSpacing.section)
                .padding(.top, PlanteriorSpacing.small)
            }
            .scrollIndicators(.hidden)
            .scrollBounceBehavior(.basedOnSize)
        }
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
