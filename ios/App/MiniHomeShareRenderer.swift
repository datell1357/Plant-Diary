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
}

@MainActor
struct MiniHomeShareRenderer {
    func render(room: MiniHome) -> MiniHomeShareRenderResult? {
        let snapshot = ShareSnapshotPolicy.snapshot(committed: room)
        let content = MiniHomeShareCanvas(snapshot: snapshot)
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
            snapshot: snapshot
        )
    }
}

private struct MiniHomeShareCanvas: View {
    let snapshot: MiniHomeShareSnapshot

    var body: some View {
        ZStack {
            PlanteriorPalette.canvas.color
            RoundedRectangle(cornerRadius: 56)
                .fill(PlanteriorPalette.surface.color)
                .overlay {
                    RoundedRectangle(cornerRadius: 56)
                        .stroke(
                            PlanteriorPalette.accent.color.opacity(0.35),
                            lineWidth: 8
                        )
                }
                .padding(72)
            ForEach(
                Array(snapshot.placements.enumerated()),
                id: \.offset
            ) { _, placement in
                Image(
                    systemName: placement.kind == .plant
                        ? "leaf.fill"
                        : "shippingbox.fill"
                )
                .font(.system(size: 104, weight: .semibold))
                .foregroundStyle(PlanteriorPalette.accent.color)
                .position(
                    x: 120 + placement.normalizedX * 960,
                    y: 140 + placement.normalizedY * 760
                )
            }
            VStack(spacing: 12) {
                Spacer()
                Text(snapshot.roomName)
                    .font(.system(size: 72, weight: .bold))
                    .foregroundStyle(PlanteriorPalette.textPrimary.color)
                Text("초보 식집사 미니홈")
                    .font(.system(size: 36, weight: .medium))
                    .foregroundStyle(
                        PlanteriorPalette.textSecondary.color
                    )
                Spacer().frame(height: 72)
            }
        }
    }
}
