import Foundation
import PlanteriorDomain

public enum MiniHomeGeometryError: Error, Equatable, Sendable {
    case invalidCoordinate
    case invalidRoomSize
}

public struct MiniHomePosition: Equatable, Sendable {
    public let normalizedX: Double
    public let normalizedY: Double

    public init(
        normalizedX: Double,
        normalizedY: Double
    ) throws {
        guard normalizedX.isFinite, normalizedY.isFinite,
              (0 ... 1).contains(normalizedX),
              (0 ... 1).contains(normalizedY)
        else {
            throw MiniHomeGeometryError.invalidCoordinate
        }
        self.normalizedX = normalizedX
        self.normalizedY = normalizedY
    }
}

public enum MiniHomeGeometry {
    public static func position(
        dragX: Double,
        dragY: Double,
        roomWidth: Double,
        roomHeight: Double
    ) throws -> MiniHomePosition {
        guard roomWidth.isFinite, roomHeight.isFinite,
              roomWidth > 0, roomHeight > 0
        else {
            throw MiniHomeGeometryError.invalidRoomSize
        }
        let normalizedX = min(max(dragX / roomWidth, 0), 1)
        let normalizedY = min(max(dragY / roomHeight, 0), 1)
        return try MiniHomePosition(
            normalizedX: normalizedX,
            normalizedY: normalizedY
        )
    }

    public static func ordered(
        _ placements: [MiniHomePlacement]
    ) -> [MiniHomePlacement] {
        placements.sorted {
            ($0.zIndex, $0.id.rawValue) <
                ($1.zIndex, $1.id.rawValue)
        }
    }

    public static func pixelCoordinate(
        normalized: Double,
        length: Double,
        itemRadius: Double
    ) -> Double {
        let safeLength = max(length, 0)
        let safeRadius = min(max(itemRadius, 0), safeLength / 2)
        let normalizedCenter = min(max(normalized, 0), 1)
        return safeRadius +
            normalizedCenter * (safeLength - safeRadius * 2)
    }

    public static func nextPlacementID(
        existing: [PlacementID]
    ) throws -> PlacementID {
        let rawValues = Set(existing.map(\.rawValue))
        var ordinal = 1
        while rawValues.contains("placement-\(ordinal)") {
            ordinal += 1
        }
        return try PlacementID.parse("placement-\(ordinal)")
    }
}
