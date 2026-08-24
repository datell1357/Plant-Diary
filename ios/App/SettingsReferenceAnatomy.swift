import PlanteriorDesignSystem
import SwiftUI

enum SettingsIcon: Hashable {
    case location
    case system(String)
}

enum SettingsReferencePalette {
    static let warningBackground = PlanteriorColorToken(hex: "#FEF3C7")
    static let warningBorder = PlanteriorColorToken(hex: "#FDE68A")
    static let warningForeground = PlanteriorColorToken(hex: "#D97706")
}

struct SettingsLayoutFrame: View {
    let identifier: String

    var body: some View {
        Rectangle()
            .fill(PlanteriorPalette.surface.color.opacity(0.001))
            .accessibilityElement()
            .accessibilityIdentifier(identifier)
    }
}

struct SettingsIconWell: View {
    let icon: SettingsIcon

    var body: some View {
        Group {
            switch icon {
            case .location:
                SettingsLocationGlyph(identifier: "settings.location-glyph")
                    .padding(.bottom, 2)
            case let .system(name):
                Image(systemName: name)
                    .font(PlanteriorTypography.supporting)
                    .foregroundStyle(PlanteriorPalette.accent.color)
                    .accessibilityHidden(true)
            }
        }
        .frame(
            width: PlanteriorControl.iconWellSize,
            height: PlanteriorControl.iconWellSize
        )
        .background(PlanteriorPalette.canvas.color)
        .clipShape(RoundedRectangle(cornerRadius: PlanteriorRadius.small))
    }
}

struct SettingsLocationGlyph: View {
    let identifier: String
    let size: CGSize
    let translation: CGSize
    let tailInset: CGFloat

    init(
        identifier: String,
        size: CGSize = SettingsReferenceMetrics.locationGlyphSize,
        translation: CGSize = CGSize(width: 0, height: 1),
        tailInset: CGFloat = 2.5
    ) {
        self.identifier = identifier
        self.size = size
        self.translation = translation
        self.tailInset = tailInset
    }

    var body: some View {
        LocationPinShape(
            translation: translation,
            tailInset: tailInset
        )
        .stroke(
            PlanteriorPalette.accent.color,
            style: StrokeStyle(lineWidth: 1.5, lineCap: .round, lineJoin: .round)
        )
        .frame(width: size.width, height: size.height)
        .accessibilityHidden(true)
        .background {
            SettingsLayoutFrame(identifier: identifier)
        }
    }
}

private struct LocationPinShape: Shape {
    let translation: CGSize
    let tailInset: CGFloat

    func path(in rect: CGRect) -> Path {
        let centerX = rect.midX
        let circleCenterY = centerX - 1
        var path = Path()
        path.move(to: CGPoint(x: centerX, y: rect.maxY - tailInset))
        path.addCurve(
            to: CGPoint(x: 1, y: circleCenterY),
            control1: CGPoint(x: centerX - 1.8, y: rect.height * 0.82),
            control2: CGPoint(x: 1, y: rect.height * 0.62)
        )
        path.addArc(
            center: CGPoint(x: centerX, y: circleCenterY),
            radius: centerX - 1,
            startAngle: .degrees(180),
            endAngle: .degrees(0),
            clockwise: false
        )
        path.addCurve(
            to: CGPoint(x: centerX, y: rect.maxY - tailInset),
            control1: CGPoint(x: rect.maxX - 1, y: rect.height * 0.62),
            control2: CGPoint(x: centerX + 1.8, y: rect.height * 0.82)
        )
        path.addEllipse(
            in: CGRect(
                x: centerX - 1.7,
                y: circleCenterY - 1.7,
                width: 3.4,
                height: 3.4
            )
        )
        return path.applying(
            CGAffineTransform(
                translationX: translation.width,
                y: translation.height
            )
        )
    }
}

struct SettingsToggle: View {
    let title: String
    @Binding var isOn: Bool
    let identifier: String

    var body: some View {
        HStack(spacing: PlanteriorSpacing.small) {
            Text(title)
            Spacer(minLength: PlanteriorSpacing.small)
            ZStack {
                Toggle("", isOn: $isOn)
                    .labelsHidden()
                    .opacity(0.001)
                    .frame(
                        width: PlanteriorControl.minimumTarget,
                        height: PlanteriorControl.minimumTarget
                    )
                    .clipped()
                    .allowsHitTesting(false)
                    .accessibilityLabel(title)
                    .accessibilityIdentifier(identifier)
                SettingsToggleIndicator(
                    isOn: isOn,
                    identifier: "\(identifier).visual"
                )
                .allowsHitTesting(false)
            }
            .frame(
                width: PlanteriorControl.minimumTarget,
                height: PlanteriorControl.minimumTarget
            )
            .contentShape(Rectangle())
            .onTapGesture {
                isOn.toggle()
            }
        }
    }
}

private struct SettingsToggleIndicator: View {
    let isOn: Bool
    let identifier: String

    var body: some View {
        Capsule()
            .fill(
                isOn
                    ? PlanteriorPalette.accent.color
                    : PlanteriorPalette.border.color
            )
            .frame(
                width: SettingsReferenceMetrics.toggleWidth,
                height: SettingsReferenceMetrics.toggleHeight
            )
            .overlay(alignment: isOn ? .trailing : .leading) {
                Circle()
                    .fill(PlanteriorPalette.surface.color)
                    .frame(
                        width: SettingsReferenceMetrics.toggleThumbSize,
                        height: SettingsReferenceMetrics.toggleThumbSize
                    )
                    .padding(SettingsReferenceMetrics.toggleThumbInset)
            }
            .accessibilityElement()
            .accessibilityIdentifier(identifier)
    }
}
