package com.planterior.helper.identify

import com.planterior.helper.core.data.IdentificationRequestDto
import com.planterior.helper.core.model.AccountId
import com.planterior.helper.feature.camera.CameraCommand
import com.planterior.helper.feature.camera.CameraFlowController
import com.planterior.helper.feature.camera.IdentificationGateway
import com.planterior.helper.feature.camera.PhotoMime
import com.planterior.helper.feature.camera.PhotoSource
import com.planterior.helper.feature.camera.PreparedPhoto
import com.planterior.helper.feature.camera.RequestIdFactory
import com.planterior.helper.feature.camera.TemporaryUriFactory
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ApprovedPhotoIdentificationHandoffTest {
    private val approvedAt = Instant.parse("2026-08-12T00:00:00Z")
    private val bytes = byteArrayOf(1, 4, 9, 16)
    private val photo =
        PreparedPhoto(
            privateUri = "content://com.planterior.helper.fileprovider/camera/plant.webp",
            mime = PhotoMime.Webp,
            byteSize = bytes.size.toLong(),
            width = 1024,
            height = 768,
            rotationDegrees = 0,
            source = PhotoSource.Camera,
        )

    @Test
    fun `approved release photo is handed off once before identification invocation`() =
        runBlocking {
            // Given
            val backend = RecordingBackend(AccountId("user-a"))
            val events = backend.events
            val handoff = ApprovedPhotoIdentificationHandoff(backend, PrivatePhotoBytes { bytes })
            val flow =
                CameraFlowController(
                    temporaryUriFactory = TemporaryUriFactory { photo.privateUri },
                    requestIdFactory = RequestIdFactory { "request_12345678" },
                    clock = Clock.fixed(approvedAt, ZoneOffset.UTC),
                    gateway =
                        IdentificationGateway { submission ->
                            handoff.prepare(submission)
                            events += "function"
                        },
                    launch = { _: CameraCommand -> },
                )
            flow.photoPrepared(photo)
            flow.requestIdentification()
            assertTrue(events.isEmpty())

            // When
            flow.approveDisclosure()
            flow.approveDisclosure()

            // Then
            assertEquals(listOf("lookup", "upload", "create", "function"), events)
            val original = requireNotNull(backend.uploaded)
            assertEquals(
                "identification-originals/user-a/request_12345678/original.webp",
                original.path,
            )
            assertEquals("image/webp", original.contentType)
            assertEquals("user-a", original.ownerUid)
            assertEquals("request_12345678", original.requestId)
            assertEquals(Instant.parse("2026-08-13T00:00:00Z"), original.expiresAt)
            assertArrayEquals(bytes, original.bytes)
            val request = requireNotNull(backend.created)
            assertEquals("user-a", request.ownerUid)
            assertEquals(original.path, request.temporaryOriginalPath)
            assertEquals(approvedAt, request.createdAt.toDate().toInstant())
            assertEquals(original.expiresAt, request.expiresAt.toDate().toInstant())
            assertEquals(1L, request.revision)
            assertEquals(0L, request.expectedRevision)
            assertEquals("request_12345678", request.idempotencyKey)
        }

    @Test
    fun `backend failures are sanitized before returning to the camera flow`() = runBlocking {
        listOf(
                "upload" to IdentificationHandoffFailure.UploadFailed,
                "create" to IdentificationHandoffFailure.RequestFailed,
            )
            .forEach { (failureAt, expected) ->
                val backend = RecordingBackend(AccountId("user-a"), failureAt = failureAt)
                val handoff =
                    ApprovedPhotoIdentificationHandoff(backend, PrivatePhotoBytes { bytes })

                assertFailure(expected) { handoff.prepare(submission()) }
            }
    }

    @Test
    fun `unauthenticated and cross-owner handoffs fail before upload or request creation`() =
        runBlocking {
            // Given / When / Then
            val unauthenticated = RecordingBackend(null)
            val unauthenticatedHandoff =
                ApprovedPhotoIdentificationHandoff(
                    unauthenticated,
                    PrivatePhotoBytes { bytes },
                )
            assertFailure(IdentificationHandoffFailure.Unauthenticated) {
                unauthenticatedHandoff.prepare(submission())
            }
            assertTrue(unauthenticated.events.isEmpty())

            val foreign = RecordingBackend(AccountId("user-a"), existingOwnerUid = "user-b")
            val foreignHandoff =
                ApprovedPhotoIdentificationHandoff(foreign, PrivatePhotoBytes { bytes })
            assertFailure(IdentificationHandoffFailure.PermissionDenied) {
                foreignHandoff.prepare(submission())
            }
            assertEquals(listOf("lookup"), foreign.events)
        }

    private fun submission() =
        com.planterior.helper.feature.camera.PhotoSubmission(
            requestId = "request_12345678",
            photo = photo,
            disclosure = com.planterior.helper.feature.camera.PhotoDisclosure.Product,
            approvedAt = approvedAt,
        )

    private suspend fun assertFailure(
        expected: IdentificationHandoffFailure,
        action: suspend () -> Unit,
    ) {
        val failure = runCatching { action() }.exceptionOrNull()
        assertTrue(failure is IdentificationHandoffException)
        assertEquals(expected, (failure as IdentificationHandoffException).reason)
        assertEquals(expected.name, failure.message)
    }

    private inner class RecordingBackend(
        private val owner: AccountId?,
        private val existingOwnerUid: String? = null,
        private val failureAt: String? = null,
    ) : IdentificationHandoffBackend {
        val events = mutableListOf<String>()
        var uploaded: TemporaryIdentificationOriginal? = null
        var created: IdentificationRequestDto? = null

        override fun currentOwner(): AccountId? = owner

        override suspend fun findRequest(
            owner: AccountId,
            requestId: String,
        ): IdentificationRequestDto? {
            events += "lookup"
            return existingOwnerUid?.let {
                IdentificationRequestDto(
                    ownerUid = it,
                    temporaryOriginalPath = "identification-originals/$it/$requestId/original.webp",
                    createdAt = com.google.firebase.Timestamp(approvedAt.epochSecond, 0),
                    expiresAt =
                        com.google.firebase.Timestamp(
                            approvedAt.plusSeconds(86_400).epochSecond,
                            0,
                        ),
                    revision = 1,
                    expectedRevision = 0,
                    idempotencyKey = requestId,
                    updatedAt = approvedAt.toString(),
                )
            }
        }

        override suspend fun upload(original: TemporaryIdentificationOriginal) {
            events += "upload"
            if (failureAt == "upload") throw IllegalStateException("secret upload detail")
            uploaded = original
        }

        override suspend fun createRequest(
            owner: AccountId,
            requestId: String,
            request: IdentificationRequestDto,
        ) {
            events += "create"
            if (failureAt == "create") throw IllegalStateException("secret request detail")
            created = request
        }
    }
}
