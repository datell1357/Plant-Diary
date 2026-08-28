import PlanteriorDesignSystem
import SwiftUI

enum SettingsIcon: Hashable {
    case location
    case system(String)
}

struct SettingsIconWell: View {
    let icon: SettingsIcon

    var body: some View {
        switch icon {
        case .location:
            PlanteriorIconWell {
                SettingsLocationGlyph()
                    .padding(.bottom, 2)
            }
        case let .system(name):
            PlanteriorIconWell(systemImage: name)
        }
    }
}

struct SettingsLocationGlyph: View {
    let size: CGSize
    let translation: CGSize
    let tailInset: CGFloat

    init(
        size: CGSize = SettingsReferenceMetrics.locationGlyphSize,
        translation: CGSize = CGSize(width: 0, height: 1),
        tailInset: CGFloat = 2.5
    ) {
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
    let icon: SettingsIcon
    @Binding var isOn: Bool
    let identifier: String

    var body: some View {
        Toggle(isOn: $isOn) {
            HStack(spacing: PlanteriorSpacing.medium) {
                SettingsIconWell(icon: icon)
                Text(title)
                    .fixedSize(horizontal: false, vertical: true)
            }
            .frame(
                maxWidth: .infinity,
                minHeight: PlanteriorControl.minimumTarget,
                alignment: .leading
            )
            .contentShape(Rectangle())
        }
        .toggleStyle(.switch)
        .tint(PlanteriorPalette.accent.color)
        .foregroundStyle(PlanteriorPalette.textPrimary.color)
        .frame(
            maxWidth: .infinity,
            minHeight: PlanteriorControl.minimumTarget,
            alignment: .leading
        )
        .contentShape(Rectangle())
        .accessibilityIdentifier(identifier)
    }
}
