import Foundation

/// One row of a non-scrolling wrapped control strip.
///
/// At the accessibility sizes the room editor's category strip and tray drop
/// their horizontal scrollers: a scroller made each strip its own assistive
/// container, which VoiceOver traversed after the plain footer instead of in
/// source order. Chunking the elements into fixed-width rows keeps every
/// control a direct child painted top-to-bottom in reading order.
struct MiniRoomWrappedRow<Element>: Identifiable {
    let index: Int
    let elements: [Element]
    /// Empty columns that keep the final row's cells on the shared grid.
    let trailingGaps: Int

    var id: Int {
        index
    }

    /// Chunks `elements` into rows of at most `columns` entries, preserving
    /// source order.
    static func rows(
        of elements: [Element],
        columns: Int = MiniRoomReferenceMetrics.accessibilityColumnCount
    ) -> [MiniRoomWrappedRow<Element>] {
        guard columns > 0 else {
            return []
        }
        return stride(from: 0, to: elements.count, by: columns)
            .enumerated()
            .map { position, start in
                let slice = Array(
                    elements[start ..< min(start + columns, elements.count)]
                )
                return MiniRoomWrappedRow(
                    index: position,
                    elements: slice,
                    trailingGaps: columns - slice.count
                )
            }
    }
}
