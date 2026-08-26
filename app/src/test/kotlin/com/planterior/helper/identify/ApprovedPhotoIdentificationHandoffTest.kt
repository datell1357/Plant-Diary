package com.planterior.helper.identify

import com.planterior.helper.core.data.PrivateMediaReference
import com.planterior.helper.core.model.AccountId
import com.planterior.helper.feature.camera.PhotoDisclosure
import com.planterior.helper.feature.camera.PhotoMime
import com.planterior.helper.feature.camera.PhotoSource
import com.planterior.helper.feature.camera.PhotoSubmission
import com.planterior.helper.feature.camera.PreparedPhoto
import java.time.Instant
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
    fun `approved photo reserves uploads commits then calls server authorization once`() =
        runBlocking {
            val backend = RecordingBackend(AccountId("user-a"))
            val handoff = ApprovedPhotoIdentificationHandoff(backend, PrivatePhotoBytes { bytes })

            handoff.prepare(submission())
            handoff.prepare(submission())

            assertEquals(listOf("upload", "authorize"), backend.events)
            assertArrayEquals(bytes, requireNotNull(backend.uploaded).bytes)
            assertEquals("image/webp", requireNotNull(backend.uploaded).contentType)
            assertEquals(
                listOf("user-a", "request_12345678", "reservation_request_12345678", "7", "1"),
                backend.authorizationArguments,
            )
        }

    @Test
    fun `response loss retries exact request id and converges through callable without a firestore write`() =
        runBlocking {
            val backend =
                RecordingBackend(AccountId("user-a"), loseFirstAuthorizationResponse = true)
            val handoff = ApprovedPhotoIdentificationHandoff(backend, PrivatePhotoBytes { bytes })

            assertFailure(IdentificationHandoffFailure.RequestFailed) {
                handoff.prepare(submission())
            }
            handoff.prepare(submission())

            assertEquals(listOf("upload", "authorize", "upload", "authorize"), backend.events)
            assertEquals(
                listOf("request_12345678", "request_12345678"),
                backend.authorizedRequestIds,
            )
            assertEquals(0, backend.directFirestoreWrites)
        }

    @Test
    fun `unauthenticated and backend failures are sanitized`() = runBlocking {
        val unauthenticated = RecordingBackend(null)
        assertFailure(IdentificationHandoffFailure.Unauthenticated) {
            ApprovedPhotoIdentificationHandoff(unauthenticated, PrivatePhotoBytes { bytes })
                .prepare(submission())
        }
        assertTrue(unauthenticated.events.isEmpty())

        listOf(
                "upload" to IdentificationHandoffFailure.UploadFailed,
                "authorize" to IdentificationHandoffFailure.RequestFailed,
            )
            .forEach { (failureAt, expected) ->
                val backend = RecordingBackend(AccountId("user-a"), failureAt = failureAt)
                assertFailure(expected) {
                    ApprovedPhotoIdentificationHandoff(backend, PrivatePhotoBytes { bytes })
                        .prepare(submission())
                }
            }
    }

    private fun submission() =
        PhotoSubmission(
            requestId = "request_12345678",
            photo = photo,
            disclosure = PhotoDisclosure.Product,
            approvedAt = approvedAt,
        )

    private suspend fun assertFailure(
        expected: IdentificationHandoffFailure,
        action: suspend () -> Unit,
    ) {
        val failure = runCatching { action() }.exceptionOrNull()
        assertTrue(failure is IdentificationHandoffException)
        assertEquals(expected, (failure as IdentificationHandoffException).reason)
    }

    private class RecordingBackend(
        private val owner: AccountId?,
        private val failureAt: String? = null,
        private val loseFirstAuthorizationResponse: Boolean = false,
    ) : IdentificationHandoffBackend {
        val events = mutableListOf<String>()
        val authorizedRequestIds = mutableListOf<String>()
        val authorizationArguments = mutableListOf<String>()
        var uploaded: TemporaryIdentificationOriginal? = null
        var directFirestoreWrites = 0
        private var authorizationCalls = 0

        override fun currentOwner(): AccountId? = owner

        override suspend fun upload(
            original: TemporaryIdentificationOriginal
        ): PrivateMediaReference {
            events += "upload"
            if (failureAt == "upload") throw IllegalStateException("upload detail")
            uploaded = original
            return PrivateMediaReference("reservation_${original.requestId}", "7")
        }

        override suspend fun authorizeRequest(
            owner: AccountId,
            requestId: String,
            mediaReference: PrivateMediaReference,
            disclosureVersion: Int,
        ): IdentificationRequestAcknowledgement {
            events += "authorize"
            authorizedRequestIds += requestId
            authorizationArguments +=
                listOf(
                    owner.value,
                    requestId,
                    mediaReference.reservationId,
                    mediaReference.generation,
                    disclosureVersion.toString(),
                )
            if (failureAt == "authorize") throw IllegalStateException("callable detail")
            authorizationCalls += 1
            if (loseFirstAuthorizationResponse && authorizationCalls == 1) {
                throw IllegalStateException("response lost after server commit")
            }
            return IdentificationRequestAcknowledgement(
                requestId,
                disclosureVersion,
                1_000L,
                1_000L,
                86_401_000L,
            )
        }
    }
}
