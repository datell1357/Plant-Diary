enum AppTab: String, CaseIterable, Hashable, Sendable {
    case home
    case collection
    case storage
    case settings

    var title: String {
        switch self {
        case .home: "홈"
        case .collection: "도감"
        case .storage: "창고"
        case .settings: "설정"
        }
    }

    /// SF Symbol counterparts of the Figma tab glyphs (`house`, `book-open`,
    /// `package`, `cog`) from figma-analysis §6.1.
    var systemImage: String {
        switch self {
        case .home: "house"
        case .collection: "book"
        case .storage: "shippingbox"
        case .settings: "gearshape"
        }
    }
}
