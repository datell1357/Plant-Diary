import CryptoKit
import PlanteriorData
import PlanteriorDesignSystem
import PlanteriorDomain
import SwiftUI
import UIKit

@MainActor
struct MiniHomeShareRenderResult {
    let image: UIImage
    let pngData: Data
    let digest: String
    let snapshot: MiniHomeShareSnapshot
    /// The exact room projection recorded in the export, retained for callers
    /// and tests that need to compare editor, committed, and share output.
    let placementProjection: [MiniRoomResolvedPlacement]
}

@MainActor
struct MiniHomeShareRenderer {
    func render(room: MiniHome) -> MiniHomeShareRenderResult? {
        let snapshot = ShareSnapshotPolicy.snapshot(committed: room)
        let placementProjection = MiniRoomPlacementProjector.resolved(
            placements: room.placements,
            in: MiniHomeEditorCanvas.canvasSize
        )
        let content = MiniHomeShareCanvas(room: room)
            .frame(
                width: CGFloat(ShareSnapshotPolicy.imageWidth),
                height: CGFloat(ShareSnapshotPolicy.imageHeight)
            )
        let renderer = ImageRenderer(content: content)
        renderer.scale = 1
        guard let image = renderer.uiImage,
              let pngData = image.pngData()
        else {
            return nil
        }
        let digest = SHA256.hash(data: pngData)
            .map { String(format: "%02x", $0) }
            .joined()
        return MiniHomeShareRenderResult(
            image: image,
            pngData: pngData,
            digest: digest,
            snapshot: snapshot,
            placementProjection: placementProjection
        )
    }
}

private struct MiniHomeShareCanvas: View {
    let room: MiniHome

    private static let exportSize = CGFloat(ShareSnapshotPolicy.imageWidth)
    private static let roomScale = exportSize / MiniHomeEditorCanvas.canvasSize.width

    var body: some View {
        ZStack {
            PlanteriorPalette.canvas.color
            MiniHomeRoomComposition(
                room: room,
                background: MiniHomeEditorCanvas.baseAsset,
                roomIdentifier: "minihome.share.room",
                roomLabel: "저장된 미니홈 공유 이미지"
            )
            .frame(
                width: MiniHomeEditorCanvas.canvasSize.width,
                height: MiniHomeEditorCanvas.canvasSize.height
            )
            .scaleEffect(Self.roomScale)
            .accessibilityHidden(true)
        }
        .frame(width: Self.exportSize, height: Self.exportSize)
        .clipped()
    }
}
