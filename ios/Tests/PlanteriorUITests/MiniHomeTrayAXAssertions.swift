import CoreGraphics
import XCTest

@MainActor
extension MiniHomeFigmaUITests {
    func assertAXTrayCellsAndRenderedPixels(in app: XCUIApplication) {
        let screenshot = app.screenshot()
        let screen = app.windows.element(boundBy: 0).frame
        let tray = app.otherElements["minihome.editor.tray"]
        let cards = trayElements(in: app)
        assertAXTrayFrames(cards, screen: screen, tray: tray.frame, in: app)
        let samples = cards.map {
            renderedBlackPixelSample(
                screenshot: screenshot,
                frame: $0.image.frame,
                identifier: "minihome.editor.tray.image.\($0.index)"
            )
        }
        for sample in samples {
            XCTAssertLessThanOrEqual(
                sample.nearBlackPixels,
                sample.totalPixels / 20,
                "\(sample.identifier) contains a hard black compositing artifact"
            )
        }
        let canvas = app.otherElements["minihome.editor.canvas"]
        XCTAssertTrue(canvas.exists)
        let canvasSample = renderedBlackPixelSample(
            screenshot: screenshot,
            frame: canvas.frame,
            identifier: "minihome.editor.canvas"
        )
        XCTAssertEqual(
            canvasSample.nearBlackPixels,
            0,
            "the live canvas must not paint opaque placement-image bases"
        )
        attachAXTrayEvidence(
            MiniHomeTrayAXEvidence(
                screen: screen,
                tray: tray.frame,
                cards: cards,
                samples: samples,
                canvasSample: canvasSample
            )
        )
    }

    private func trayElements(
        in app: XCUIApplication
    ) -> [MiniHomeTrayAXElements] {
        (0 ... 1).map { index in
            MiniHomeTrayAXElements(
                index: index,
                card: app.buttons["minihome.editor.tray.\(index)"],
                image: app.images.matching(
                    identifier: "minihome.editor.tray.image.\(index)"
                ).firstMatch
            )
        }
    }

    private func assertAXTrayFrames(
        _ cards: [MiniHomeTrayAXElements],
        screen: CGRect,
        tray: CGRect,
        in app: XCUIApplication
    ) {
        for elements in cards {
            let index = elements.index
            let captionFrame = elements.card.frame
            XCTAssertEqual(
                app.buttons.matching(
                    identifier: "minihome.editor.tray.\(index)"
                ).count,
                1,
                "each tray caption must remain one atomic accessibility stop"
            )
            XCTAssertEqual(
                app.images.matching(
                    identifier: "minihome.editor.tray.image.\(index)"
                ).count,
                1,
                "each tray tile must expose no duplicate accessibility image"
            )
            XCTAssertTrue(tray.contains(captionFrame))
            XCTAssertTrue(captionFrame.contains(elements.image.frame))
            XCTAssertEqual(
                captionFrame.midX,
                elements.image.frame.midX,
                accuracy: 1,
                "the atomic caption must stay centered under its tile"
            )
            XCTAssertGreaterThanOrEqual(
                captionFrame.minX,
                screen.minX + 17 - 1,
                "tray caption \(index) breached the leading screen gutter"
            )
            XCTAssertLessThanOrEqual(
                captionFrame.maxX,
                screen.maxX - 17 + 1,
                "tray caption \(index) breached the trailing screen gutter"
            )
        }
    }

    private func attachAXTrayEvidence(_ evidence: MiniHomeTrayAXEvidence) {
        attachJSON(
            [
                "screen": NSCoder.string(for: evidence.screen),
                "tray": NSCoder.string(for: evidence.tray),
                "cards": evidence.cards.map {
                    [
                        "identifier": "minihome.editor.tray.\($0.index)",
                        "captionFrame": NSCoder.string(for: $0.card.frame),
                        "imageFrame": NSCoder.string(for: $0.image.frame)
                    ]
                },
                "nearBlackPixels": evidence.samples.map {
                    [
                        "identifier": $0.identifier,
                        "count": $0.nearBlackPixels,
                        "total": $0.totalPixels
                    ] as [String: Any]
                },
                "canvasNearBlackPixels": [
                    "identifier": evidence.canvasSample.identifier,
                    "count": evidence.canvasSample.nearBlackPixels,
                    "total": evidence.canvasSample.totalPixels
                ]
            ],
            named: "minihome-ax5-tray-frames-and-pixels"
        )
    }
}

struct MiniHomeTrayAXElements {
    let index: Int
    let card: XCUIElement
    let image: XCUIElement
}

private struct MiniHomeTrayAXEvidence {
    let screen: CGRect
    let tray: CGRect
    let cards: [MiniHomeTrayAXElements]
    let samples: [MiniHomeTrayPixelSample]
    let canvasSample: MiniHomeTrayPixelSample
}
