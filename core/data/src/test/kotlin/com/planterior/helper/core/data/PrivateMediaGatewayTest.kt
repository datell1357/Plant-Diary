package com.planterior.helper.core.data

import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PrivateMediaGatewayTest {
    private val request =
        PrivateMediaUpload(
            expectedOwnerUid = "owner-a",
            mediaKind = PrivateMediaKind.IDENTIFICATION_ORIGINAL,
            contentType = "image/webp",
            bytes = byteArrayOf(1, 2, 3),
            idempotencyKey = "request_12345678",
        )
    private val headers =
        linkedMapOf(
            "content-length" to "3",
            "content-type" to "image/webp",
            "x-goog-if-generation-match" to "0",
            "x-goog-meta-owner-uid" to "owner-a",
            "x-goog-meta-reservation-id" to "reservation_12345678",
        )

    @Test
    fun `reserve exact header PUT commit sequence returns typed reference`() = runTest {
        val events = mutableListOf<String>()
        val calls = RecordingCallable(events = events)
        val put = RecordingPut(SignedPutResult.Uploaded, events = events)
        val gateway = FirebasePrivateMediaGateway(calls, put)

        val reference = gateway.upload(request)

        assertEquals(
            listOf("reservePrivateMediaUpload", "PUT", "commitPrivateMediaReservation"),
            events,
        )
        assertEquals(headers, put.headers)
        assertEquals("https://upload.example/signed", put.url)
        assertTrue(put.bytes.contentEquals(request.bytes))
        assertEquals(PrivateMediaReference("reservation_12345678", "7"), reference)
        assertEquals(
            mapOf(
                "expectedOwnerUid" to "owner-a",
                "mediaKind" to "IDENTIFICATION_ORIGINAL",
                "contentType" to "image/webp",
                "byteSize" to 3L,
                "idempotencyKey" to "request_12345678",
            ),
            calls.payloads.first(),
        )
        assertEquals(
            mapOf("expectedOwnerUid" to "owner-a", "reservationId" to "reservation_12345678"),
            calls.payloads.last(),
        )
    }

    @Test
    fun `reserve rejects omitted or non-exact signed content length`() = runTest {
        listOf(
                headers - "content-length",
                headers + ("content-length" to "4"),
            )
            .forEach { requiredHeaders ->
                val calls = RecordingCallable(reserve = reserved(requiredHeaders))
                val error =
                    assertSuspendFails<PrivateMediaGatewayException> {
                        FirebasePrivateMediaGateway(
                                calls,
                                RecordingPut(SignedPutResult.Uploaded),
                            )
                            .upload(request)
                    }
                assertEquals(PrivateMediaGatewayError.MALFORMED_RESPONSE, error.reason)
            }
    }

    @Test
    fun `conditional 412 and lost response converge through idempotent commit`() = runTest {
        listOf(
                SignedPutResult.PreconditionFailed,
                SignedPutResult.Indeterminate(IOException("lost")),
            )
            .forEach { outcome ->
                val calls = RecordingCallable()
                val reference =
                    FirebasePrivateMediaGateway(calls, RecordingPut(outcome)).upload(request)
                assertEquals("reservation_12345678", reference.reservationId)
                assertEquals(2, calls.payloads.size)
            }
    }

    @Test
    fun `owner kind size and content mismatches fail closed`() = runTest {
        listOf(
                committed(mediaKind = "PLANT_PHOTO"),
                committed(contentType = "image/png"),
                committed(byteSize = 4L),
                committed(reservationId = "reservation_other"),
            )
            .forEach { response ->
                val calls = RecordingCallable(commit = response)
                val error =
                    assertSuspendFails<PrivateMediaGatewayException> {
                        FirebasePrivateMediaGateway(calls, RecordingPut(SignedPutResult.Uploaded))
                            .upload(request)
                    }
                assertEquals(PrivateMediaGatewayError.MALFORMED_RESPONSE, error.reason)
            }
        assertThrows(IllegalArgumentException::class.java) {
            request.copy(expectedOwnerUid = "bad/owner")
        }
        assertThrows(IllegalArgumentException::class.java) { request.copy(bytes = byteArrayOf()) }
        assertThrows(IllegalArgumentException::class.java) {
            request.copy(contentType = "text/plain")
        }
    }

    @Test
    fun `cancellation is propagated and commit is not attempted`() = runTest {
        val calls = RecordingCallable()
        val cancellation = CancellationException("left")
        val error =
            assertSuspendFails<CancellationException> {
                FirebasePrivateMediaGateway(calls, RecordingPut(throwable = cancellation))
                    .upload(request)
            }
        assertEquals(cancellation, error)
        assertEquals(1, calls.payloads.size)
    }

    @Test
    fun `legacy and seal object paths fail closed`() {
        assertThrows(IllegalArgumentException::class.java) {
            PrivateMediaReference.fromStorageObject("plant-photos/owner/plant/photo.webp", "7")
        }
        val reference =
            PrivateMediaReference.fromStorageObject("private-media-v2/reservation_12345678", "7")
        assertEquals("reservation_12345678", reference.reservationId)
        assertThrows(IllegalArgumentException::class.java) {
            PrivateMediaObjectMetadata(
                    path = reference.storagePath,
                    generation = "7",
                    byteSize = 0,
                    contentType = "application/x.planterior-private-media-seal",
                    customMetadata = mapOf("privateMediaSeal" to "true"),
                )
                .requireOwnerReadable(reference, "owner-a")
        }
    }

    private class RecordingCallable(
        private val reserve: Any? = reserved(),
        private val commit: Any? = committed(),
        private val events: MutableList<String> = mutableListOf(),
    ) : PrivateMediaCallable {
        val payloads = mutableListOf<Map<String, Any>>()

        override suspend fun call(name: String, payload: Map<String, Any>): Any? {
            events += name
            payloads += payload
            return if (name == "reservePrivateMediaUpload") reserve else commit
        }
    }

    private class RecordingPut(
        private val result: SignedPutResult = SignedPutResult.Uploaded,
        private val throwable: Throwable? = null,
        private val events: MutableList<String> = mutableListOf(),
    ) : SignedPutTransport {
        lateinit var url: String
        lateinit var headers: Map<String, String>
        lateinit var bytes: ByteArray

        override suspend fun put(
            url: String,
            headers: Map<String, String>,
            bytes: ByteArray,
        ): SignedPutResult {
            events += "PUT"
            throwable?.let { throw it }
            this.url = url
            this.headers = headers
            this.bytes = bytes
            return result
        }
    }

    private suspend inline fun <reified T : Throwable> assertSuspendFails(
        crossinline block: suspend () -> Unit
    ): T {
        val error =
            try {
                block()
                throw AssertionError("Expected ${T::class.java.simpleName}")
            } catch (error: Throwable) {
                error
            }
        if (error !is T) throw AssertionError("Expected ${T::class.java.simpleName}, got $error")
        return error
    }

    private companion object {
        fun reserved(requiredHeaders: Map<String, String> = defaultHeaders()): Map<String, Any> =
            mapOf(
                "reservationId" to "reservation_12345678",
                "upload" to
                    mapOf(
                        "method" to "PUT",
                        "url" to "https://upload.example/signed",
                        "expiresAtMillis" to 2_000_000_000_000L,
                        "requiredHeaders" to requiredHeaders,
                    ),
            )

        private fun defaultHeaders(): Map<String, String> =
            mapOf(
                "content-length" to "3",
                "content-type" to "image/webp",
                "x-goog-if-generation-match" to "0",
                "x-goog-meta-owner-uid" to "owner-a",
                "x-goog-meta-reservation-id" to "reservation_12345678",
            )

        fun committed(
            reservationId: String = "reservation_12345678",
            mediaKind: String = "IDENTIFICATION_ORIGINAL",
            contentType: String = "image/webp",
            byteSize: Long = 3L,
        ): Map<String, Any> =
            mapOf(
                "reference" to mapOf("reservationId" to reservationId, "generation" to "7"),
                "mediaKind" to mediaKind,
                "contentType" to contentType,
                "byteSize" to byteSize,
            )
    }
}
