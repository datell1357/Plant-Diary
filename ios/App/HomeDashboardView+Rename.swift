import PlanteriorDesignSystem
import PlanteriorDomain
import SwiftUI

extension HomeDashboardView {
    /// Figma `modal-overlay` §6.9. Free and paid share every dimension; only the
    /// trailing cost affordance on the save button changes.
    @ViewBuilder
    var renameDialog: some View {
        if isRenamePresented {
            ZStack {
                Color.black.opacity(0.45)
                    .ignoresSafeArea()
                    .onTapGesture(perform: dismissRename)
                    .accessibilityHidden(true)
                dialogCard
            }
            .transition(
                effectiveReduceMotion ? .identity : .opacity
            )
        }
    }

    private var dialogCard: some View {
        VStack(alignment: .leading, spacing: PlanteriorSpacing.large) {
            header
            input
            saveButton
            if case let .insufficient(cost, balance) = renameQuote {
                Text("크레딧이 부족해요 · \(cost) 필요 / 보유 \(balance)")
                    .font(PlanteriorTypography.caption)
                    .foregroundStyle(PlanteriorPalette.textTertiary.color)
                    .fixedSize(horizontal: false, vertical: true)
                    .accessibilityIdentifier("home.rename.insufficient")
            }
        }
        .padding(PlanteriorSpacing.extraLarge)
        .frame(width: 330)
        .background(PlanteriorPalette.surface.color)
        .clipShape(RoundedRectangle(cornerRadius: PlanteriorRadius.extraLarge))
        .accessibilityElement(children: .contain)
        .accessibilityAddTraits(.isModal)
        .accessibilityIdentifier("home.rename.dialog")
        .overlay(alignment: .top) {
            // Motion-mode marker kept as its own element so it can never
            // shadow the dialog's identifier.
            Color.clear
                .frame(width: 1, height: 1)
                .accessibilityIdentifier(
                    effectiveReduceMotion
                        ? "home.rename.dialog.reduce-motion"
                        : "home.rename.dialog.animated"
                )
        }
    }

    private var header: some View {
        HStack(spacing: PlanteriorSpacing.small) {
            Text("홈피 이름 변경")
                .font(PlanteriorTypography.sectionTitle)
                .foregroundStyle(PlanteriorPalette.textPrimary.color)
                .accessibilityIdentifier("home.rename.title")
                .accessibilityAddTraits(.isHeader)
            Spacer(minLength: PlanteriorSpacing.small)
            Button(action: dismissRename) {
                Image(systemName: "xmark")
                    .font(PlanteriorTypography.caption)
                    .foregroundStyle(PlanteriorPalette.textSecondary.color)
                    .frame(
                        width: PlanteriorControl.iconWellSize,
                        height: PlanteriorControl.iconWellSize
                    )
                    .overlay {
                        Circle().stroke(
                            PlanteriorPalette.border.color,
                            lineWidth: PlanteriorControl.hairline
                        )
                    }
                    .contentShape(Circle())
            }
            .buttonStyle(.plain)
            .accessibilityLabel("닫기")
            .accessibilityIdentifier("home.rename.close")
        }
    }

    private var input: some View {
        TextField("새로운 이름을 입력하세요", text: $renameDraft)
            .textFieldStyle(.plain)
            .font(PlanteriorTypography.body)
            .padding(.horizontal, PlanteriorSpacing.large)
            .frame(height: PlanteriorControl.primaryButtonHeight)
            .background(PlanteriorPalette.surface.color)
            .clipShape(RoundedRectangle(cornerRadius: PlanteriorRadius.medium))
            .overlay {
                RoundedRectangle(cornerRadius: PlanteriorRadius.medium)
                    .stroke(
                        PlanteriorPalette.border.color,
                        lineWidth: PlanteriorControl.hairline
                    )
            }
            .focused($isRenameFieldFocused)
            .submitLabel(.done)
            .onSubmit { isRenameFieldFocused = false }
            .accessibilityIdentifier("home.rename.input")
    }

    private var saveButton: some View {
        Button(action: commitRename) {
            HStack(spacing: PlanteriorSpacing.extraSmall) {
                Text("저장")
                    .font(PlanteriorTypography.body.weight(.semibold))
                costAffordance
            }
            .frame(maxWidth: .infinity)
            .frame(height: PlanteriorControl.primaryButtonHeight)
        }
        .buttonStyle(.plain)
        .foregroundStyle(PlanteriorPalette.textOnAccent.color)
        .background(
            renameQuote.isAffordable
                ? PlanteriorPalette.accent.color
                : PlanteriorPalette.textTertiary.color
        )
        .clipShape(RoundedRectangle(cornerRadius: PlanteriorRadius.medium))
        .disabled(!renameQuote.isAffordable)
        .accessibilityIdentifier("home.rename.save")
    }

    @ViewBuilder
    private var costAffordance: some View {
        switch renameQuote {
        case .free:
            Text("(1회 무료)")
                .font(PlanteriorTypography.caption)
                .foregroundStyle(PlanteriorPalette.textOnAccent.color.opacity(0.7))
                .accessibilityIdentifier("home.rename.cost")
        case let .paid(cost, balance), let .insufficient(cost, balance):
            Image(systemName: "circlebadge.fill")
                .font(PlanteriorTypography.caption)
                .foregroundStyle(PlanteriorPalette.warning.color)
                .accessibilityIdentifier("home.rename.cost.coin")
                .accessibilityHidden(true)
            Text("\(cost)")
                .font(PlanteriorTypography.caption.weight(.semibold))
                .accessibilityIdentifier("home.rename.cost")
            Text("보유 \(balance)")
                .font(PlanteriorTypography.caption)
                .foregroundStyle(PlanteriorPalette.textOnAccent.color.opacity(0.7))
                .accessibilityIdentifier("home.rename.balance")
        }
    }
}
