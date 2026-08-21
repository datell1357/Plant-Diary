import XCTest

@MainActor
extension MiniHomeFigmaUITests {
    /// Every editor category caption is a two-syllable Korean word (식물, 벽지,
    /// 바닥, 가구, 소품). At AX5 the tab strip must give each tab enough width
    /// to keep its caption on ONE line; a tab column narrower than the caption
    /// forces `lineLimit(2)` to split the word one syllable per line
    /// ("식" / "물"), which is unreadable Korean.
    ///
    /// The tabs share a row, so a wrapped caption does NOT show up as extra
    /// height - the row equalises it. It shows up as WIDTH: a tab whose column
    /// is narrower than its own caption is exactly the tab whose word gets
    /// split. Every caption here is two Korean syllables, so all five tabs
    /// need comparable width; a tab pinned far below the widest one is being
    /// starved and wraps.
    func assertCategoryCaptionsStayOnOneLine(
        in app: XCUIApplication
    ) {
        let identifiers = [
            "minihome.editor.category.plant",
            "minihome.editor.category.wall",
            "minihome.editor.category.floor",
            "minihome.editor.category.furniture",
            "minihome.editor.category.decoration"
        ]
        let widths = identifiers.map { app.buttons[$0].frame.width }
        guard let widest = widths.max() else {
            XCTFail("no category tabs rendered at AX5")
            return
        }
        for (identifier, width) in zip(identifiers, widths) {
            // All five captions are two-syllable Korean words, so once every
            // tab sizes to its content their widths land within a narrow band.
            // A tab clamped well under the widest is the starved column that
            // splits its word one syllable per line.
            XCTAssertGreaterThan(
                width,
                widest * 0.8,
                "\(identifier) is too narrow for its Korean caption at AX5; "
                    + "the word wraps one syllable per line"
            )
        }
    }
}
