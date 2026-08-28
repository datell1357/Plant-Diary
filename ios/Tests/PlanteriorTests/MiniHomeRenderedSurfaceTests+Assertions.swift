import CoreGraphics
@testable import Planterior
import PlanteriorDomain
import SwiftUI
import Testing
import UIKit

extension MiniHomeRenderedSurfaceTests {
    func assertEditorSurface(
        room: MiniHome,
        emptyRoom: MiniHome
    ) throws {
        let editor = try render(
            MiniHomeEditorCanvas(
                room: room,
                placementLabel: MiniRoomPlacementPresentation.accessibilityLabel,
                move: { _, _ in },
                moveBy: { _, _, _ in }
            )
        )
        let emptyEditor = try render(
            MiniHomeEditorCanvas(
                room: emptyRoom,
                placementLabel: MiniRoomPlacementPresentation.accessibilityLabel,
                move: { _, _ in },
                moveBy: { _, _, _ in }
            )
        )
        assertPaintedCopy(
            editor,
            differsFrom: emptyEditor,
            at: [
                CGPoint(x: 153, y: 210),
                CGPoint(x: 186, y: 193),
                CGPoint(x: 211, y: 216)
            ]
        )
    }

    func assertCommittedSurface(
        room: MiniHome,
        emptyRoom: MiniHome
    ) throws {
        let committed = try render(
            MiniHomeCanvasView(room: room).frame(width: 358, height: 320)
        )
        let emptyCommitted = try render(
            MiniHomeCanvasView(room: emptyRoom).frame(width: 358, height: 320)
        )
        assertPaintedCopy(
            committed,
            differsFrom: emptyCommitted,
            at: [
                CGPoint(x: 153, y: 203),
                CGPoint(x: 186, y: 187),
                CGPoint(x: 211, y: 208)
            ]
        )
    }

    func assertHomeSurface(
        room: MiniHome,
        emptyRoom: MiniHome
    ) throws {
        let home = try render(homeRoom(room))
        let emptyHome = try render(homeRoom(emptyRoom))
        assertPaintedCopy(
            home,
            differsFrom: emptyHome,
            at: [
                CGPoint(x: 153, y: 207),
                CGPoint(x: 186, y: 190),
                CGPoint(x: 211, y: 213)
            ]
        )
    }

    func assertShareSurface(
        room: MiniHome,
        emptyRoom: MiniHome
    ) throws {
        let share = try #require(MiniHomeShareRenderer().render(room: room)?.image)
        let emptyShare = try #require(
            MiniHomeShareRenderer().render(room: emptyRoom)?.image
        )
        #expect(share.size == CGSize(width: 1200, height: 1200))
        let sharePoints = [
            CGPoint(x: 153, y: 210),
            CGPoint(x: 186, y: 193),
            CGPoint(x: 211, y: 216)
        ].map(referencePointInShareExport)
        assertPaintedCopy(
            share,
            differsFrom: emptyShare,
            at: sharePoints,
            radius: 24,
            minimumChangedPixels: 40
        )
    }

    /// The independent export oracle pins the 358x330 reference canvas inside
    /// the 1200x1200 share contract. It does not consume production projection
    /// metrics, so a changed export side, scale, or centering origin fails here.
    private func referencePointInShareExport(_ point: CGPoint) -> CGPoint {
        let exportSide = 1200.0
        let referenceWidth = 358.0
        let referenceHeight = 330.0
        let scale = exportSide / referenceWidth
        let verticalOrigin = (exportSide - referenceHeight * scale) / 2
        return CGPoint(
            x: point.x * scale,
            y: verticalOrigin + point.y * scale
        )
    }

    private func homeRoom(_ room: MiniHome) -> some View {
        MiniHomeRoomComposition(
            room: room,
            background: .homeRoom,
            roomIdentifier: "test.home.room",
            roomLabel: "테스트 미니홈"
        )
        .frame(width: 358, height: HomeReferenceMetrics.miniRoomHeight)
    }

    private func render(_ content: some View) throws -> UIImage {
        let renderer = ImageRenderer(content: content)
        renderer.scale = 1
        return try #require(renderer.uiImage)
    }
}
