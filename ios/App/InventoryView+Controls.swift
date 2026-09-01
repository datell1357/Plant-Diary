import PlanteriorDesignSystem
import PlanteriorDomain
import SwiftUI

extension InventoryView {
    var storageHeader: some View {
        let action = mode.headerAction
        return HStack(alignment: .center, spacing: PlanteriorSpacing.small) {
            Text(mode == .warehouse ? "나의 창고" : "아이템 상점")
                .font(PlanteriorTypography.pageTitle)
                .foregroundStyle(PlanteriorPalette.textPrimary.color)
                .accessibilityAddTraits(.isHeader)
                .accessibilityIdentifier(
                    mode == .warehouse ? "storage.title" : "shop.title"
                )
            Spacer(minLength: PlanteriorSpacing.small)
            Button {
                mode = mode == .warehouse ? .shop : .warehouse
                category = nil
                warehouseCategory = nil
                seasonalOnly = false
                visibleItemLimit = Self.initialVisibleItemLimit
            } label: {
                Image(systemName: action.systemImage)
                    .font(PlanteriorTypography.supporting.weight(.semibold))
                    .frame(
                        width: InventoryReferenceMetrics.headerIconSide,
                        height: InventoryReferenceMetrics.headerIconSide
                    )
                    .background(PlanteriorPalette.surface.color)
                    .clipShape(Circle())
                    .overlay {
                        Circle().stroke(
                            PlanteriorPalette.border.color,
                            lineWidth: PlanteriorControl.hairline
                        )
                    }
            }
            .buttonStyle(.plain)
            .frame(
                width: PlanteriorControl.minimumTarget,
                height: PlanteriorControl.minimumTarget
            )
            .foregroundStyle(PlanteriorPalette.textPrimary.color)
            .accessibilityLabel(action.accessibilityLabel)
            .accessibilityIdentifier(action.identifier)
        }
        .padding(.horizontal, PlanteriorSpacing.extraLarge)
        .frame(height: InventoryReferenceMetrics.headerHeight)
    }

    var shopCredit: some View {
        HStack(spacing: PlanteriorSpacing.extraSmall) {
            Text("보유 크레딧")
                .foregroundStyle(PlanteriorPalette.accent.color)
                .accessibilityIdentifier("shop.credit.label")
            storageCreditImage
                .resizable()
                .scaledToFit()
                .frame(
                    width: InventoryReferenceMetrics.creditIconSide,
                    height: InventoryReferenceMetrics.creditIconSide
                )
                .accessibilityLabel("크레딧")
                .accessibilityIdentifier("shop.credit.icon")
            Text("1,250")
                .foregroundStyle(PlanteriorPalette.warningText.color)
                .accessibilityIdentifier("shop.credit.amount")
        }
        .font(PlanteriorTypography.supporting.weight(.semibold))
        .frame(
            width: InventoryReferenceMetrics.creditWidth,
            height: InventoryReferenceMetrics.creditHeight
        )
        .background(PlanteriorPalette.warningSurface.color)
        .clipShape(Capsule())
        .overlay {
            Capsule().stroke(
                PlanteriorPalette.border.color,
                lineWidth: PlanteriorControl.hairline
            )
        }
        .padding(.top, InventoryReferenceMetrics.creditTopInset)
    }

    private var storageCreditImage: Image {
        if let image = UIImage(named: "FigmaStorageCredit") {
            return Image(uiImage: image)
        }
        return Image(systemName: "circle")
    }
}
