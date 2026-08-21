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

public struct PlanteriorIconWell: View {
    @Environment(\.sizeCategory) private var sizeCategory
    private let systemImage: String

    public init(systemImage: String) {
        self.systemImage = systemImage
    }

    public var body: some View {
        let side = PlanteriorControl.iconWellSize(for: sizeCategory)
        Image(systemName: systemImage)
            .font(PlanteriorTypography.supporting)
            .foregroundStyle(PlanteriorPalette.accent.color)
            .frame(width: side, height: side)
            .background(PlanteriorPalette.accentSurface.color)
            .clipShape(RoundedRectangle(cornerRadius: PlanteriorRadius.small))
            .accessibilityHidden(true)
    }
}

public extension View {
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
