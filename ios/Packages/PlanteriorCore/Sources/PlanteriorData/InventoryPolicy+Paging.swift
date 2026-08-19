public extension InventoryCatalogPolicy {
    static func page(
        entries: [InventoryCatalogEntry],
        after cursor: String?,
        limit: Int
    ) -> InventoryCatalogPage {
        let cursorIndex = cursor.flatMap { value in
            entries.firstIndex {
                $0.item.id.rawValue == value
            }
        }
        let startIndex = cursorIndex.map {
            entries.index(after: $0)
        } ?? entries.startIndex
        let pageLimit = max(limit, 1)
        let endIndex = min(startIndex + pageLimit, entries.endIndex)
        let pageEntries = Array(entries[startIndex ..< endIndex])
        let nextCursor: String? = if endIndex < entries.endIndex {
            pageEntries.last?.item.id.rawValue
        } else {
            nil
        }
        return InventoryCatalogPage(
            entries: pageEntries,
            nextCursor: nextCursor
        )
    }
}
