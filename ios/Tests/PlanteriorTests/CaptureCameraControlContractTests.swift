import AVFoundation
import CoreGraphics
@testable import Planterior
import Testing

struct CaptureCameraControlContractTests {
    @Test
    func cameraControlsMatchReferenceGeometry() {
        // Given: the measured reference camera controls.
        let referenceShutterRing: CGFloat = 72
        let referenceShutterFill: CGFloat = 56
        let referenceShutterStroke: CGFloat = 4
        let referenceReticleSegments: [CGFloat] = [64, 128]
        let referenceShutterOffset = CGSize(width: 7, height: -5)
        let referenceGallerySymbol = "photo.on.rectangle"

        // When: the live camera control contract is read.
        let actual = (
            CaptureLayoutMetrics.shutterRingDiameter,
            CaptureLayoutMetrics.shutterDiameter,
            CaptureLayoutMetrics.shutterStrokeWidth,
            CaptureLayoutMetrics.cameraReticleMiddleSegmentOffsets,
            CaptureLayoutMetrics.shutterOuterRing,
            CaptureLayoutMetrics.shutterOffset,
            CaptureLayoutMetrics.gallerySystemImage
        )

        // Then: the shutter layers and gallery glyph match the reference.
        #expect(actual.0 == referenceShutterRing)
        #expect(actual.1 == referenceShutterFill)
        #expect(actual.2 == referenceShutterStroke)
        #expect(actual.3 == referenceReticleSegments)
        #expect(actual.4 == .continuousCircle)
        #expect(actual.5 == referenceShutterOffset)
        #expect(actual.6 == referenceGallerySymbol)
    }

    #if DEBUG
        @Test
        func primaryIdentificationFixtureUsesExactFigmaSpeciesName() {
            let fixtureID = CaptureSpecies.fixtureID(for: "local-candidate-1")
            let fixture = CaptureSpecies.named("local-candidate-1")

            #expect(fixtureID == .authenticatedPrimary)
            #expect(fixture.koreanName == "몬스테라 델리시오사")
            #expect(fixture.binomial == "Monstera deliciosa")
        }
    #endif

    @Test @MainActor
    func liveCameraPreviewOwnsTypedFullBleedPreviewLayer() {
        // Given / When
        let view = LiveCameraPreviewView(
            frame: CGRect(x: 0, y: 0, width: 320, height: 480)
        )
        view.layoutIfNeeded()

        // Then
        #expect(view.previewLayer.videoGravity == .resizeAspectFill)
        #expect(view.previewLayer.frame == view.bounds)
    }

    @Test
    func simulatorSelectsDeterministicCameraFixture() {
        let mode = CameraPresentationPolicy.mode(
            isSimulator: true,
            isDebug: false,
            environment: [:]
        )

        #expect(mode == .deterministicFixture)
    }

    @Test
    func explicitDebugQARouteSelectsDeterministicCameraFixture() {
        let mode = CameraPresentationPolicy.mode(
            isSimulator: false,
            isDebug: true,
            environment: ["QA_CAMERA_STATIC_FIXTURE": "1"]
        )

        #expect(mode == .deterministicFixture)
    }

    @Test
    func deviceNonQARouteSelectsLiveCapture() {
        let mode = CameraPresentationPolicy.mode(
            isSimulator: false,
            isDebug: true,
            environment: [:]
        )

        #expect(mode == .liveCapture)
    }

    @Test
    func releaseDeviceIgnoresDebugQAFixtureRoute() {
        let mode = CameraPresentationPolicy.mode(
            isSimulator: false,
            isDebug: false,
            environment: ["QA_CAMERA_STATIC_FIXTURE": "1"]
        )

        #expect(mode == .liveCapture)
    }

    @Test
    func cameraControlsKeepMinimumHitTargets() {
        // Given: the platform minimum interactive target.
        let minimumTarget: CGFloat = 44

        // When: the camera control geometry is read.
        let sideControlTarget = CaptureLayoutMetrics.cameraControlMinimumTarget
        let shutterTarget = CaptureLayoutMetrics.shutterRingDiameter

        // Then: both gallery and shutter controls remain directly tappable.
        #expect(sideControlTarget >= minimumTarget)
        #expect(shutterTarget >= minimumTarget)
    }
}
