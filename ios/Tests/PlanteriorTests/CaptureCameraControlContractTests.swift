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
        let referenceGallerySymbol = "photo"

        // When: the live camera control contract is read.
        let actual = (
            CaptureLayoutMetrics.shutterRingDiameter,
            CaptureLayoutMetrics.shutterDiameter,
            CaptureLayoutMetrics.shutterStrokeWidth,
            CaptureLayoutMetrics.gallerySystemImage
        )

        // Then: the shutter layers and gallery glyph match the reference.
        #expect(actual.0 == referenceShutterRing)
        #expect(actual.1 == referenceShutterFill)
        #expect(actual.2 == referenceShutterStroke)
        #expect(actual.3 == referenceGallerySymbol)
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
