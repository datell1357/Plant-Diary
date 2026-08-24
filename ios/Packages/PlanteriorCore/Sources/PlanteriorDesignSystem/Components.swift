import SwiftUI

public struct PlanteriorCard<Content: View>: View {
    private let variant: PlanteriorCardVariant
    private let content: Content

    public init(
        variant: PlanteriorCardVariant = .standard,
        @ViewBuilder content: () -> Content
    ) {
        self.variant = variant
        self.content = content()
    }

    public var body: some View {
        content
            .padding(PlanteriorSpacing.large)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(variant.background.color)
            .clipShape(RoundedRectangle(cornerRadius: PlanteriorRadius.large))
            .overlay {
                if let border = variant.border {
                    RoundedRectangle(cornerRadius: PlanteriorRadius.large)
                        .stroke(border.color, lineWidth: PlanteriorControl.hairline)
                }
            }
    }
}

public struct PlanteriorPrimaryButton: View {
    private let title: LocalizedStringKey
    private let action: () -> Void

    public init(_ title: LocalizedStringKey, action: @escaping () -> Void) {
        self.title = title
        self.action = action
    }

    public var body: some View {
        PlanteriorActionButton(title, style: .primary, action: action)
    }
}

public struct PlanteriorSecondaryButton: View {
    private let title: LocalizedStringKey
    private let action: () -> Void

    public init(_ title: LocalizedStringKey, action: @escaping () -> Void) {
        self.title = title
        self.action = action
    }

    public var body: some View {
        PlanteriorActionButton(title, style: .secondary, action: action)
    }
}

struct PlanteriorActionButton: View {
    private let title: LocalizedStringKey
    private let style: PlanteriorActionStyle
    private let action: () -> Void

    init(
        _ title: LocalizedStringKey,
        style: PlanteriorActionStyle,
        action: @escaping () -> Void
    ) {
        self.title = title
        self.style = style
        self.action = action
    }

    var body: some View {
        Button(action: action) {
            Text(title)
                .font(PlanteriorTypography.body.weight(.semibold))
                .frame(maxWidth: .infinity)
                .frame(minHeight: style.height)
        }
        .buttonStyle(.plain)
        .foregroundStyle(style.foreground.color)
        .background(style.background.color)
        .clipShape(RoundedRectangle(cornerRadius: PlanteriorRadius.medium))
        .overlay {
            if let border = style.border {
                RoundedRectangle(cornerRadius: PlanteriorRadius.medium)
                    .stroke(border.color, lineWidth: PlanteriorControl.hairline)
            }
        }
    }
}

public struct PlanteriorStatusPill: View {
    private let title: LocalizedStringKey
    private let variant: PlanteriorStatusVariant

    public init(_ title: LocalizedStringKey, variant: PlanteriorStatusVariant) {
        self.title = title
        self.variant = variant
    }

    public var body: some View {
        Text(title)
            .font(PlanteriorTypography.microLabel)
            .lineLimit(1)
            // The pill keeps its single line, but must stay COMPRESSIBLE: at
            // accessibility sizes an unbreakable pill forces its whole row
            // wider than the screen, which pushes every sibling - including
            // the page header - past the content inset.
            .minimumScaleFactor(0.6)
            .foregroundStyle(variant.foreground.color)
            .padding(.horizontal, PlanteriorSpacing.medium)
            .padding(.vertical, PlanteriorSpacing.extraSmall)
            .background(variant.background.color)
            .clipShape(Capsule())
    }
}

public struct PlanteriorSectionHeader<Accessory: View>: View {
    private let title: LocalizedStringKey
    private let accessory: Accessory

    public init(_ title: LocalizedStringKey, @ViewBuilder accessory: () -> Accessory) {
        self.title = title
        self.accessory = accessory()
    }

    public var body: some View {
        HStack(alignment: .firstTextBaseline, spacing: PlanteriorSpacing.small) {
            Text(title)
                .font(PlanteriorTypography.sectionTitle)
                .foregroundStyle(PlanteriorPalette.textPrimary.color)
            Spacer(minLength: PlanteriorSpacing.small)
            accessory
        }
        .accessibilityElement(children: .contain)
        .accessibilityAddTraits(.isHeader)
    }
}

public extension PlanteriorSectionHeader where Accessory == EmptyView {
    init(_ title: LocalizedStringKey) {
        self.init(title) { EmptyView() }
    }
}

/// Grouped settings-style surface: one rounded container whose rows are separated by
/// inset hairlines instead of nested cards.
public struct PlanteriorGroupedSurface<Content: View>: View {
    private let content: Content

    public init(@ViewBuilder content: () -> Content) {
        self.content = content()
    }

    public var body: some View {
        VStack(spacing: 0) {
            content
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(PlanteriorPalette.surface.color)
        .clipShape(RoundedRectangle(cornerRadius: PlanteriorRadius.large))
        .overlay {
            RoundedRectangle(cornerRadius: PlanteriorRadius.large)
                .stroke(PlanteriorPalette.border.color, lineWidth: PlanteriorControl.hairline)
        }
    }
}

public struct PlanteriorIconWell<Content: View>: View {
    @Environment(\.sizeCategory) private var sizeCategory
    private let content: Content

    public init(@ViewBuilder content: () -> Content) {
        self.content = content()
    }

    public init(systemImage: String) where Content == Image {
        self.init {
            Image(systemName: systemImage)
        }
    }

    public var body: some View {
        let side = PlanteriorControl.iconWellSize(for: sizeCategory)
        content
            .font(PlanteriorTypography.supporting)
            .foregroundStyle(PlanteriorPalette.accent.color)
            .frame(width: side, height: side)
            .background(PlanteriorPalette.accentSurface.color)
            .clipShape(RoundedRectangle(cornerRadius: PlanteriorRadius.small))
            .accessibilityHidden(true)
    }
}

/// App-owned navigation chrome with a centered title and stable 44pt action slots.
public struct PlanteriorTopBar<Leading: View, Trailing: View>: View {
    @Environment(\.sizeCategory) private var sizeCategory
    private let title: LocalizedStringKey
    private let leading: Leading
    private let trailing: Trailing

    public init(
        _ title: LocalizedStringKey,
        @ViewBuilder leading: () -> Leading,
        @ViewBuilder trailing: () -> Trailing
    ) {
        self.title = title
        self.leading = leading()
        self.trailing = trailing()
    }

    public var body: some View {
        ZStack {
            Text(title)
                .font(PlanteriorTypography.screenTitle)
                .foregroundStyle(PlanteriorPalette.textPrimary.color)
                .lineLimit(sizeCategory.isAccessibilityCategory ? 2 : 1)
                .multilineTextAlignment(.center)
                .fixedSize(horizontal: false, vertical: true)
                .padding(.horizontal, PlanteriorControl.minimumTarget)
                .accessibilityAddTraits(.isHeader)

            HStack(spacing: 0) {
                leading
                    .frame(
                        minWidth: PlanteriorControl.minimumTarget,
                        minHeight: PlanteriorControl.minimumTarget,
                        alignment: .leading
                    )
                Spacer(minLength: PlanteriorSpacing.small)
                trailing
                    .frame(
                        minWidth: PlanteriorControl.minimumTarget,
                        minHeight: PlanteriorControl.minimumTarget,
                        alignment: .trailing
                    )
            }
        }
        .padding(.horizontal, PlanteriorLayout.contentGutter)
        .frame(minHeight: PlanteriorLayout.topBarHeight)
        .background(PlanteriorPalette.canvas.color)
    }
}

public extension PlanteriorTopBar where Leading == EmptyView, Trailing == EmptyView {
    init(_ title: LocalizedStringKey) {
        self.init(title, leading: { EmptyView() }, trailing: { EmptyView() })
    }
}

public extension PlanteriorTopBar where Trailing == EmptyView {
    init(_ title: LocalizedStringKey, @ViewBuilder leading: () -> Leading) {
        self.init(title, leading: leading, trailing: { EmptyView() })
    }
}

public extension PlanteriorTopBar where Leading == EmptyView {
    init(_ title: LocalizedStringKey, @ViewBuilder trailing: () -> Trailing) {
        self.init(title, leading: { EmptyView() }, trailing: trailing)
    }
}

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
                Color.black.opacity(PlanteriorOpacity.dimmer)
                    .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .accessibilityHidden(true)

            VStack(spacing: 0) {
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
                .frame(height: contentHeight, alignment: .top)

                Spacer(minLength: 0)
            }
            .frame(maxWidth: .infinity)
            .frame(height: totalHeight, alignment: .top)
            .background(PlanteriorPalette.surface.color)
            .clipShape(
                UnevenRoundedRectangle(
                    topLeadingRadius: PlanteriorRadius.sheet,
                    topTrailingRadius: PlanteriorRadius.sheet
                )
            )
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
                Color.black.opacity(PlanteriorOpacity.dimmer)
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
                .font(.system(size: 24, weight: .semibold))
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
