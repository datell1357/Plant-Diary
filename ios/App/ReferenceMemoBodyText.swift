import PlanteriorDesignSystem
import SwiftUI
import UIKit

struct ReferenceMemoBodyText: UIViewRepresentable {
    let text: String

    func makeUIView(context: Context) -> ReferenceMemoLabel {
        let label = ReferenceMemoLabel()
        label.adjustsFontForContentSizeCategory = true
        label.font = .preferredFont(forTextStyle: .subheadline)
        label.numberOfLines = 0
        label.lineBreakMode = .byWordWrapping
        label.lineBreakStrategy = .hangulWordPriority
        label.isAccessibilityElement = true
        label.accessibilityIdentifier = "plant.detail.memo.body"
        label.accessibilityTraits = .staticText
        return label
    }

    func updateUIView(_ label: ReferenceMemoLabel, context: Context) {
        label.sourceText = KoreanTypography.binding(
            text,
            phrases: PlantCareKoreanPhrases.memo
        )
        label.atomicEnding = KoreanTypography.atomic(
            PlantCareKoreanPhrases.memoEnding
        )
        label.font = .preferredFont(forTextStyle: .subheadline)
        label.textColor = UIColor(PlanteriorPalette.textPrimary.color)
        label.accessibilityLabel = text
    }

    func sizeThatFits(
        _ proposal: ProposedViewSize,
        uiView label: ReferenceMemoLabel,
        context: Context
    ) -> CGSize? {
        guard let proposedWidth = proposal.width else {
            return nil
        }
        let width = min(
            proposedWidth,
            PlantCareReferenceMetrics.memoBodyReferenceWidth
        )
        let fittingSize = label.sizeThatFits(
            CGSize(width: width, height: .greatestFiniteMagnitude)
        )
        return CGSize(
            width: width,
            height: max(
                fittingSize.height,
                PlantCareReferenceMetrics.memoBodyMinimumHeight
            )
        )
    }
}

final class ReferenceMemoLabel: UILabel {
    var sourceText = ""
    var atomicEnding = ""

    override var intrinsicContentSize: CGSize {
        CGSize(
            width: UIView.noIntrinsicMetric,
            height: super.intrinsicContentSize.height
        )
    }

    override func layoutSubviews() {
        if bounds.width > 0 {
            let displayedText = sourceText.replacingOccurrences(
                of: " \(atomicEnding)",
                with: "\n\(atomicEnding)"
            )
            if text != displayedText {
                text = displayedText
            }
        }
        super.layoutSubviews()
        #if DEBUG
            guard ProcessInfo.processInfo.environment[
                "QA_COLLECTION_FIGMA_FIXTURE"
            ] == "1", bounds.width > 0 else {
                return
            }
            let lines = KoreanTypography.visualLines(
                in: text ?? "",
                font: font,
                width: bounds.width
            )
            let endingState = lines.contains {
                $0.contains(PlantCareKoreanPhrases.memoEnding)
            } ? "preserved" : "split"
            accessibilityValue = "lines=\(lines.count);ending=\(endingState)"
        #endif
    }

    override func drawText(in rect: CGRect) {
        let fittingRect = textRect(
            forBounds: rect,
            limitedToNumberOfLines: numberOfLines
        )
        super.drawText(
            in: CGRect(
                origin: rect.origin,
                size: CGSize(width: rect.width, height: fittingRect.height)
            )
        )
    }
}
