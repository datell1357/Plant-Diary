import SwiftUI

/// Clipped reusable media frame. Images supplied by callers should be resizable
/// and use their desired fill/focus behavior before entering this container.
public struct PlanteriorMedia<Content: View>: View {
    private let aspectRatio: CGFloat
    private let radius: CGFloat
    private let content: Content

    public init(
        aspectRatio: CGFloat = PlanteriorLayout.heroAspectRatio,
        radius: CGFloat = PlanteriorRadius.extraLarge,
        @ViewBuilder content: () -> Content
    ) {
        self.aspectRatio = aspectRatio
        self.radius = radius
        self.content = content()
    }

    public var body: some View {
        Color.clear
            .aspectRatio(aspectRatio, contentMode: .fit)
            .overlay {
                content
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
                    .clipped()
            }
            .clipShape(RoundedRectangle(cornerRadius: radius))
    }
}

public struct PlanteriorFilterChip: View {
    private let title: LocalizedStringKey
    private let style: PlanteriorFilterStyle
    private let action: () -> Void

    public init(
        _ title: LocalizedStringKey,
        isSelected: Bool,
        action: @escaping () -> Void
    ) {
        self.title = title
        style = isSelected ? .selected : .unselected
        self.action = action
    }

    public var body: some View {
        Button(action: action) {
            Text(title)
                .font(PlanteriorTypography.caption.weight(.semibold))
                .lineLimit(1)
                .padding(.horizontal, PlanteriorSpacing.large)
                .frame(minHeight: PlanteriorControl.minimumTarget)
        }
        .buttonStyle(.plain)
        .foregroundStyle(style.foreground.color)
        .background(style.background.color)
        .clipShape(Capsule())
        .overlay {
            if let border = style.border {
                Capsule().stroke(border.color, lineWidth: PlanteriorControl.hairline)
            }
        }
    }
}

public struct PlanteriorFloatingActionButton: View {
    private let systemImage: String
    private let accessibilityLabel: LocalizedStringKey
    private let action: () -> Void

    public init(
        systemImage: String = "plus",
        accessibilityLabel: LocalizedStringKey,
        action: @escaping () -> Void
    ) {
        self.systemImage = systemImage
        self.accessibilityLabel = accessibilityLabel
        self.action = action
    }

    public var body: some View {
        Button(action: action) {
            Image(systemName: systemImage)
                .font(PlanteriorTypography.floatingActionGlyph)
                .foregroundStyle(PlanteriorPalette.textOnAccent.color)
                .frame(
                    width: PlanteriorLayout.floatingActionSize,
                    height: PlanteriorLayout.floatingActionSize
                )
                .background(Circle().fill(PlanteriorPalette.accent.color))
                .contentShape(Circle())
        }
        .buttonStyle(.plain)
        .accessibilityLabel(Text(accessibilityLabel))
    }
}

public extension View {
    func planteriorShadow(_ token: PlanteriorShadowToken) -> some View {
        shadow(
            color: token.color.color.opacity(token.opacity),
            radius: token.radius,
            x: token.offsetX,
            y: token.offsetY
        )
    }

    func planteriorFloatingActionPlacement() -> some View {
        frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .bottomTrailing)
            .padding(PlanteriorLayout.floatingActionInset)
    }

    @ViewBuilder
    func planteriorInlineNavigationChrome() -> some View {
        #if os(iOS)
            toolbarBackground(PlanteriorPalette.canvas.color, for: .navigationBar)
                .toolbarBackground(.visible, for: .navigationBar)
        #else
            self
        #endif
    }
}
