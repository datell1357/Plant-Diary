import Foundation
import UIKit

enum KoreanTypography {
    static let wordJoiner = "\u{2060}"

    static func atomic(_ value: String) -> String {
        var result = ""
        var previousWasNonWhitespace = false
        for character in value {
            if previousWasNonWhitespace, !character.isWhitespace {
                result += wordJoiner
            }
            result.append(character)
            previousWasNonWhitespace = !character.isWhitespace
        }
        return result
    }

    static func binding(_ value: String, phrases: [String]) -> String {
        phrases.reduce(value) { result, phrase in
            result.replacingOccurrences(of: phrase, with: atomic(phrase))
        }
    }

    static func visualLines(
        in value: String,
        font: UIFont,
        width: CGFloat
    ) -> [String] {
        let storage = NSTextStorage(
            attributedString: NSAttributedString(
                string: value,
                attributes: [.font: font]
            )
        )
        let layout = NSLayoutManager()
        let container = NSTextContainer(
            size: CGSize(width: width, height: .greatestFiniteMagnitude)
        )
        container.lineFragmentPadding = 0
        container.lineBreakMode = .byWordWrapping
        layout.addTextContainer(container)
        storage.addLayoutManager(layout)
        layout.ensureLayout(for: container)

        var lines: [String] = []
        layout.enumerateLineFragments(
            forGlyphRange: NSRange(location: 0, length: layout.numberOfGlyphs)
        ) { _, _, _, glyphRange, _ in
            let range = layout.characterRange(
                forGlyphRange: glyphRange,
                actualGlyphRange: nil
            )
            lines.append(
                (value as NSString)
                    .substring(with: range)
                    .replacingOccurrences(of: wordJoiner, with: "")
            )
        }
        return lines
    }

    static func atomicParentheticalSpecies(in value: String) -> String {
        guard let parts = parentheticalSpeciesParts(in: value) else {
            return value
        }
        let species = parts.parenthetical.dropFirst().dropLast()
        return parts.leading + " (" + atomic(String(species)) + ")"
    }

    static func parentheticalSpeciesParts(
        in value: String
    ) -> (leading: String, parenthetical: String)? {
        guard let opening = value.lastIndex(of: "("),
              let closing = value.lastIndex(of: ")"),
              opening < closing
        else {
            return nil
        }
        return (
            leading: value[..<opening].trimmingCharacters(in: .whitespaces),
            parenthetical: String(value[opening ... closing])
        )
    }
}

enum PlantCareKoreanPhrases {
    static let memoEnding = "있습니다."
    static let memo = [memoEnding]
    static let remedy = ["보충합니다."]
}
