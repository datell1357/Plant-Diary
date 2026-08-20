import PlanteriorDomain

/// Figma `category-tab-bar` (`myroom-editor` `35:4`). The five tabs and their
/// order are the frame's contract; the icons are the SF Symbol equivalents of
/// the exported Lucide glyphs (`grid`/`brush`/`layers`/`sofa`/`lamp`).
enum MiniRoomCategory: String, CaseIterable, Identifiable, Sendable {
    case plant
    case wall
    case floor
    case furniture
    case decoration

    var id: String {
        rawValue
    }

    var title: String {
        switch self {
        case .plant: "식물"
        case .wall: "벽지"
        case .floor: "바닥"
        case .furniture: "가구"
        case .decoration: "소품"
        }
    }

    var systemImage: String {
        switch self {
        case .plant: "circle.grid.2x2.fill"
        case .wall: "paintbrush"
        case .floor: "square.3.layers.3d"
        case .furniture: "sofa"
        case .decoration: "lamp.table"
        }
    }

    /// Placement target kind. Only `plant` places a registered personal plant;
    /// every other category places a room item.
    var placesRegisteredPlant: Bool {
        self == .plant
    }

    var itemCategory: ItemCategory? {
        switch self {
        case .plant: nil
        case .wall, .floor: .background
        case .furniture: .furniture
        case .decoration: .decoration
        }
    }
}
