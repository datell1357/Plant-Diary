package com.planterior.helper.feature.camera

import com.planterior.helper.core.model.ClientProductEvent
import com.planterior.helper.core.model.ProductEventRecorder
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraFlowContractTest {
    private val photo =
        PreparedPhoto(
            privateUri = "content://com.planterior.helper.fileprovider/camera/plant.jpg",
            mime = PhotoMime.Jpeg,
            byteSize = 4096,
            width = 1200,
            height = 900,
            rotationDegrees = 90,
            source = PhotoSource.Camera,
        )

    @Test
    fun `route entry and picker never request camera or broad storage permission`() {
        val fixture = fixture()
        assertTrue(fixture.commands.isEmpty())

        fixture.flow.choosePicker()

        assertEquals(listOf(CameraCommand.LaunchPhotoPicker), fixture.commands)
        assertFalse(fixture.commands.any { it is CameraCommand.RequestPermission })
    }

    @Test
    fun `camera permission is requested only after explicit camera choice`() {
        val fixture = fixture()

        fixture.flow.chooseCamera(CameraPermission.NotRequested)

        assertEquals(listOf(CameraCommand.RequestPermission), fixture.commands)
    }

    @Test
    fun `denied and permanently denied expose settings picker and direct alternatives without loops`() {
        listOf(false, true).forEach { permanent ->
            val fixture = fixture()
            fixture.flow.cameraPermissionDenied(permanent)
            val blocked = fixture.flow.state as CameraFlowState.PermissionBlocked
            assertEquals(permanent, blocked.permanentlyDenied)

            fixture.flow.chooseCamera(CameraPermission.Denied(permanent))
            assertTrue(fixture.commands.isEmpty())
            fixture.flow.openSettings()
            fixture.flow.choosePicker()
            fixture.flow.chooseDirectRegistration()
            assertEquals(
                listOf(
                    CameraCommand.OpenAppSettings,
                    CameraCommand.LaunchPhotoPicker,
                    CameraCommand.OpenDirectRegistration,
                ),
                fixture.commands,
            )
            assertFalse(fixture.commands.any { it is CameraCommand.RequestPermission })
        }
    }

    @Test
    fun `capture always receives a newly allocated app private temporary URI`() {
        val fixture = fixture()

        fixture.flow.chooseCamera(CameraPermission.Granted)

        assertEquals(
            listOf(CameraCommand.LaunchCamera(photo.privateUri)),
            fixture.commands,
        )
        assertTrue((fixture.flow.state as CameraFlowState.Capturing).temporaryUri.isNotBlank())
    }

    @Test
    fun `review replace and retake preserve draft until a new valid photo arrives`() {
        val fixture = fixture()
        fixture.flow.photoPrepared(photo)

        fixture.flow.replacePhoto()
        assertEquals(photo, fixture.flow.state.draft)
        assertEquals(CameraCommand.LaunchPhotoPicker, fixture.commands.last())
        fixture.flow.pickerCancelled()
        assertEquals(photo, (fixture.flow.state as CameraFlowState.Review).photo)

        fixture.flow.retakePhoto(CameraPermission.Granted)
        assertEquals(photo, fixture.flow.state.draft)
        assertTrue(fixture.commands.last() is CameraCommand.LaunchCamera)
        fixture.flow.captureCancelled()
        assertEquals(photo, (fixture.flow.state as CameraFlowState.Review).photo)
    }

    @Test
    fun `invalid replacement preserves existing review and exposes typed error`() {
        val fixture = fixture()
        fixture.flow.photoPrepared(photo)

        fixture.flow.photoRejected(PhotoError.Corrupt)

        val state = fixture.flow.state as CameraFlowState.Review
        assertEquals(photo, state.photo)
        assertEquals(PhotoError.Corrupt, state.error)
    }

    @Test
    fun `disclosure cancel and back make zero calls and retain review draft`() {
        listOf<(CameraFlowController) -> Unit>({ it.cancelDisclosure() }, { it.back() }).forEach {
            action ->
            val fixture = fixture()
            fixture.flow.photoPrepared(photo)
            fixture.flow.requestIdentification()
            assertTrue(fixture.flow.state is CameraFlowState.Disclosure)

            action(fixture.flow)

            assertEquals(0, fixture.gateway.submissions.size)
            assertEquals(photo, (fixture.flow.state as CameraFlowState.Review).photo)
        }
    }

    @Test
    fun `disclosure approval invokes exactly one request with per-request consent`() = runBlocking {
        val fixture = fixture()
        fixture.flow.photoPrepared(photo)
        fixture.flow.requestIdentification()

        fixture.flow.approveDisclosure()
        fixture.flow.approveDisclosure()

        assertEquals(1, fixture.gateway.submissions.size)
        val submission = fixture.gateway.submissions.single()
        assertEquals("request-1", submission.requestId)
        assertEquals(photo, submission.photo)
        assertEquals(Instant.parse("2026-08-12T00:00:00Z"), submission.approvedAt)
        assertTrue(submission.disclosure.remoteProcessing)
        assertEquals(24, submission.disclosure.originalRetentionHours)
    }

    @Test
    fun `successful photo submission records the request event exactly once`() = runBlocking {
        val events = mutableListOf<ClientProductEvent>()
        val fixture = fixture(recorder = ProductEventRecorder(events::add))
        fixture.flow.photoPrepared(photo)
        fixture.flow.requestIdentification()

        fixture.flow.approveDisclosure()
        fixture.flow.approveDisclosure()

        assertEquals(listOf(ClientProductEvent.IDENTIFICATION_REQUEST_SUBMITTED), events)
    }

    @Test
    fun `failed or cancelled photo submission records no request event`() = runBlocking {
        val events = mutableListOf<ClientProductEvent>()
        val failed =
            fixture(
                recorder = ProductEventRecorder(events::add),
                gatewayFailure = IllegalStateException("offline"),
            )
        failed.flow.photoPrepared(photo)
        failed.flow.requestIdentification()
        failed.flow.approveDisclosure()

        val cancelled = fixture(recorder = ProductEventRecorder(events::add))
        cancelled.flow.photoPrepared(photo)
        cancelled.flow.requestIdentification()
        cancelled.flow.cancelDisclosure()

        assertTrue(events.isEmpty())
    }

    @Test
    fun `recreation restores review draft without replaying launcher or request`() {
        val first = fixture()
        first.flow.photoPrepared(photo)
        val snapshot = first.flow.snapshot()

        val restored = fixture(snapshot)

        assertEquals(photo, (restored.flow.state as CameraFlowState.Review).photo)
        assertTrue(restored.commands.isEmpty())
        assertTrue(restored.gateway.submissions.isEmpty())
    }

    private fun fixture(
        snapshot: CameraFlowSnapshot? = null,
        recorder: ProductEventRecorder = ProductEventRecorder {},
        gatewayFailure: Throwable? = null,
    ): Fixture {
        val commands = mutableListOf<CameraCommand>()
        val gateway = RecordingGateway(gatewayFailure)
        val flow =
            CameraFlowController(
                temporaryUriFactory = TemporaryUriFactory { photo.privateUri },
                requestIdFactory = RequestIdFactory { "request-1" },
                clock =
                    Clock.fixed(
                        Instant.parse("2026-08-12T00:00:00Z"),
                        ZoneOffset.UTC,
                    ),
                gateway = gateway,
                launch = commands::add,
                productEventRecorder = recorder,
                restored = snapshot,
            )
        return Fixture(flow, commands, gateway)
    }

    private data class Fixture(
        val flow: CameraFlowController,
        val commands: MutableList<CameraCommand>,
        val gateway: RecordingGateway,
    )

    private class RecordingGateway(private val failure: Throwable? = null) : IdentificationGateway {
        val submissions = mutableListOf<PhotoSubmission>()

        override suspend fun submit(submission: PhotoSubmission) {
            submissions += submission
            failure?.let { throw it }
        }
    }
}
