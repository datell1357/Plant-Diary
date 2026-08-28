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
