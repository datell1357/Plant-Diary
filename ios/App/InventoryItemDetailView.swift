import Foundation
import PlanteriorData
import PlanteriorDesignSystem
import PlanteriorDomain
import SwiftUI

enum InventoryDetailFavoritePresentation {
    static func initialState(
        environment _: [String: String],
        isDebug _: Bool
    ) -> Bool {
        false
    }

    static var currentInitialState: Bool {
        #if DEBUG
            initialState(
                environment: ProcessInfo.processInfo.environment,
                isDebug: true
            )
        #else
            false
        #endif
    }
}

struct InventoryItemDetailView: View {
    @Environment(\.dismiss) var dismiss
    @State var isFavorite = InventoryDetailFavoritePresentation.currentInitialState
    let item: ShopItem
    let eligibility: InventoryAcquisitionEligibility
    let isOwned: Bool
    let isApplied: Bool
    let message: String?
    let acquire: () -> Void
    let togglePlacement: () -> Void

    var body: some View {
        VStack(spacing: 0) {
            topBar
            ScrollView {
                VStack(alignment: .leading, spacing: 0) {
                    hero
                        .padding(
                            .bottom,
                            InventoryReferenceMetrics.detailHeroToTitleSpacing
                        )
                    titleBlock
                        .padding(
                            .bottom,
                            InventoryReferenceMetrics.detailTitleToStatusSpacing
                        )
                    statusCard
                        .padding(
                            .bottom,
                            InventoryReferenceMetrics.detailStatusToActionSpacing
                        )
                    primaryAction
                        .padding(
                            .bottom,
                            InventoryReferenceMetrics.detailActionToPreviewSpacing
                        )
                    preview
                }
                .padding(.horizontal, PlanteriorSpacing.extraLarge)
            }
            .accessibilityIdentifier("storage.detail.\(item.id.rawValue)")
        }
        .padding(.top, PlanteriorControl.minimumTarget)
        .ignoresSafeArea(edges: .top)
        .background(PlanteriorPalette.canvas.color)
        .padding(.bottom, PlanteriorLayout.tabBarHeight)
        .navigationBarBackButtonHidden(true)
        .toolbar(.hidden, for: .navigationBar)
        .safeAreaInset(edge: .bottom) {
            if let message {
                Text(message)
                    .font(PlanteriorTypography.supporting)
                    .foregroundStyle(PlanteriorPalette.textPrimary.color)
                    .padding(PlanteriorSpacing.medium)
                    .frame(maxWidth: .infinity)
                    .background(PlanteriorPalette.subtle.color)
                    .accessibilityIdentifier("storage.message")
            }
        }
    }
}
