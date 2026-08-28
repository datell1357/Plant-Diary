import PlanteriorData
import PlanteriorDomain
import SwiftUI

/// One source of truth for placement traversal. Every room surface calls this
/// projector so its VoiceOver and visual stacking order follows persisted z
/// order (with placement ID as the stable tie-breaker).
struct MiniRoomResolvedPlacement: Equatable {
    let placement: MiniHomePlacement
    let asset: FigmaAsset
    let visualSize: CGSize
    let position: CGPoint

    var hitFrame: CGRect {
        CGRect(
            x: position.x - MiniRoomPlacementMetrics.hitSide / 2,
            y: position.y - MiniRoomPlacementMetrics.hitSide / 2,
            width: MiniRoomPlacementMetrics.hitSide,
            height: MiniRoomPlacementMetrics.hitSide
        )
    }
}

enum MiniRoomPlacementProjector {
    static func ordered(
        placements: [MiniHomePlacement]
    ) -> [MiniHomePlacement] {
        MiniHomeGeometry.ordered(placements)
    }

    static func resolved(
        placements: [MiniHomePlacement],
        in canvas: CGSize
    ) -> [MiniRoomResolvedPlacement] {
        ordered(placements: placements).map { placement in
            MiniRoomResolvedPlacement(
                placement: placement,
                asset: MiniRoomPlacementPresentation.asset(for: placement),
                visualSize: MiniRoomPlacementMetrics.visualSize(for: placement),
                position: MiniRoomPlacementMetrics.position(placement, in: canvas)
            )
        }
    }

    /// A drag can only start inside a placement's 72pt target. When targets
    /// overlap, the topmost rendered placement owns the gesture, exactly as it
    /// owns the visible pixels. The stable projector order makes this survive a
    /// persisted array reorder; distance only breaks an impossible duplicate
    /// visual-order key rather than selecting a hidden placement.
    static func hitPlacement(
        at point: CGPoint,
        among placements: [MiniHomePlacement],
        in canvas: CGSize
    ) -> MiniHomePlacement? {
        resolved(placements: placements, in: canvas)
            .filter { $0.hitFrame.contains(point) }
            .reduce(nil as MiniRoomResolvedPlacement?) { frontmost, candidate in
                guard let frontmost else {
                    return candidate
                }
                let candidateOrder = (
                    candidate.placement.zIndex,
                    candidate.placement.id.rawValue
                )
                let frontmostOrder = (
                    frontmost.placement.zIndex,
                    frontmost.placement.id.rawValue
                )
                if candidateOrder != frontmostOrder {
                    return candidateOrder > frontmostOrder
                        ? candidate
                        : frontmost
                }
                return squaredDistance(point, candidate.position)
                    < squaredDistance(point, frontmost.position)
                    ? candidate
                    : frontmost
            }?
            .placement
    }

    private static func squaredDistance(_ lhs: CGPoint, _ rhs: CGPoint) -> CGFloat {
        let horizontal = lhs.x - rhs.x
        let vertical = lhs.y - rhs.y
        return horizontal * horizontal + vertical * vertical
    }
}

enum MiniRoomPlacementMetrics {
    static let hitSide: CGFloat = 72

    /// Plant PNGs are intentionally smaller than their 72pt interaction
    /// target. The 56pt reference height keeps the three-plant fixture within
    /// the reference rug footprint instead of inflating it by roughly 45%.
    static func visualSize(for placement: MiniHomePlacement) -> CGSize {
        if let plantID = placement.plantID {
            let referenceSize = MiniRoomPlantPresentation.referenceVisualSize(
                for: plantID
            )
            if let referenceSize {
                return referenceSize
            }
        }
        let depthScale = 0.82 + CGFloat(placement.normalizedY) * 0.28
        if placement.plantID != nil {
            return CGSize(width: 50 * depthScale, height: 56 * depthScale)
        }
        return CGSize(width: 80 * depthScale, height: 68 * depthScale)
    }

    static func visualBounds(
        for placement: MiniHomePlacement,
        in canvas: CGSize
    ) -> CGRect {
        let size = visualSize(for: placement)
        let position = position(placement, in: canvas)
        return CGRect(
            x: position.x - size.width / 2,
            y: position.y - size.height / 2,
            width: size.width,
            height: size.height
        )
    }

    static func position(
        _ placement: MiniHomePlacement,
        in size: CGSize
    ) -> CGPoint {
        CGPoint(
            x: CGFloat(MiniHomeGeometry.pixelCoordinate(
                normalized: placement.normalizedX,
                length: Double(size.width),
                itemRadius: Double(hitSide / 2)
            )),
            y: CGFloat(MiniHomeGeometry.pixelCoordinate(
                normalized: placement.normalizedY,
                length: Double(size.height),
                itemRadius: Double(hitSide / 2)
            ))
        )
    }
}
