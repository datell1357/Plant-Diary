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

    var systemImage: String {
        switch self {
        case .home: "house"
        case .collection: "leaf"
        case .storage: "shippingbox"
        case .settings: "gearshape"
        }
    }
}
