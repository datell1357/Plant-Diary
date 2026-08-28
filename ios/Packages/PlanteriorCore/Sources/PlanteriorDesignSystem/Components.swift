import SwiftUI

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
            .background(PlanteriorPalette.iconWellSurface.color)
            .clipShape(
                RoundedRectangle(
                    cornerRadius: PlanteriorControl.iconWellCornerRadius
                )
            )
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
