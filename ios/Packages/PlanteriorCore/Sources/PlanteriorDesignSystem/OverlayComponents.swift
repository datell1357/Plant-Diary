import SwiftUI

/// App-owned bottom panel. The fixed reference height avoids OS-version detent
/// changes while keeping overflowing Dynamic Type content scrollable by callers.
public struct PlanteriorSheet<Content: View>: View {
    @GestureState private var dragOffset: CGFloat = 0
    private let contentHeight: CGFloat
    private let totalHeight: CGFloat
    private let dismissLabel: LocalizedStringKey
    private let dismissIdentifier: String
    private let onDismiss: () -> Void
    private let content: Content

    public init(
        contentHeight: CGFloat = PlanteriorLayout.bottomPanelContentHeight,
        totalHeight: CGFloat = PlanteriorLayout.bottomPanelTotalHeight,
        dismissLabel: LocalizedStringKey = "닫기",
        dismissIdentifier: String = "sheet.dismiss",
        onDismiss: @escaping () -> Void,
        @ViewBuilder content: () -> Content
    ) {
        self.contentHeight = contentHeight
        self.totalHeight = totalHeight
        self.dismissLabel = dismissLabel
        self.dismissIdentifier = dismissIdentifier
        self.onDismiss = onDismiss
        self.content = content()
    }

    public var body: some View {
        ZStack(alignment: .bottom) {
            Button(action: onDismiss) {
                ZStack(alignment: .top) {
                    Color.clear
                    PlanteriorPalette.mediaScrim.color
                        .opacity(PlanteriorOpacity.dimmer)
                        .padding(.bottom, totalHeight)
                }
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .accessibilityHidden(true)

            VStack(spacing: 0) {
                Button(action: onDismiss) {
                    Capsule()
                        .fill(PlanteriorPalette.border.color)
                        .frame(width: 36, height: 4)
                        .frame(maxWidth: .infinity, minHeight: 28)
                        .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .accessibilityLabel(Text(dismissLabel))
                .accessibilityIdentifier(dismissIdentifier)
                .gesture(
                    DragGesture(minimumDistance: PlanteriorSpacing.small)
                        .updating($dragOffset) { value, state, _ in
                            state = max(0, value.translation.height)
                        }
                        .onEnded { value in
                            if value.translation.height > PlanteriorControl.minimumTarget {
                                onDismiss()
                            }
                        }
                )

                content
            }
            .frame(maxWidth: .infinity)
            .frame(height: contentHeight, alignment: .top)
            .background(PlanteriorPalette.surface.color)
            .clipShape(
                UnevenRoundedRectangle(
                    topLeadingRadius: PlanteriorRadius.sheet,
                    topTrailingRadius: PlanteriorRadius.sheet
                )
            )
            .padding(.bottom, totalHeight - contentHeight)
            .offset(y: dragOffset)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .ignoresSafeArea()
    }
}

/// Centered app-owned dialog and dimmer. Feature-specific contents own only
/// their internal spacing; width, radius, and dismissal behavior stay shared.
public struct PlanteriorModal<Content: View>: View {
    private let dismissLabel: LocalizedStringKey
    private let onDismiss: () -> Void
    private let content: Content

    public init(
        dismissLabel: LocalizedStringKey = "닫기",
        onDismiss: @escaping () -> Void,
        @ViewBuilder content: () -> Content
    ) {
        self.dismissLabel = dismissLabel
        self.onDismiss = onDismiss
        self.content = content()
    }

    public var body: some View {
        ZStack {
            Button(action: onDismiss) {
                PlanteriorPalette.mediaScrim.color
                    .opacity(PlanteriorOpacity.dimmer)
                    .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .accessibilityLabel(Text(dismissLabel))

            content
                .padding(PlanteriorSpacing.extraLarge)
                .frame(maxWidth: PlanteriorLayout.modalWidth)
                .background(PlanteriorPalette.surface.color)
                .clipShape(RoundedRectangle(cornerRadius: PlanteriorRadius.extraLarge))
        }
        .padding(.horizontal, PlanteriorLayout.contentGutter)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .ignoresSafeArea()
    }
}
