package com.planterior.helper.core.data

import com.sun.net.httpserver.HttpServer
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.net.URL
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PrivateMediaHttpTransportTest {
    private val bytes = byteArrayOf(1, 2, 3)
    private val headers =
        mapOf(
            "content-length" to "3",
            "content-type" to "image/webp",
            "x-goog-if-generation-match" to "0",
        )

    @Test
    fun `HTTP transport emits one exact fixed content length`() = runTest {
        RecordingHttpServer().use { server ->
            val result = HttpSignedPutTransport().put(server.url, headers, bytes)

            assertEquals(SignedPutResult.Uploaded, result)
            assertEquals(listOf("3"), server.requests.single().contentLengths)
            assertEquals(null, server.requests.single().transferEncoding)
            assertTrue(server.requests.single().body.contentEquals(bytes))
        }
    }

    @Test
    fun `HTTP transport configures exact connect and read timeouts before IO`() = runTest {
        val connection = RecordingHttpURLConnection(URL("http://signed-put.test/upload"))

        val result = HttpSignedPutTransport {
            connection
        }
            .put(connection.url.toString(), headers, bytes)

        assertEquals(SignedPutResult.Uploaded, result)
        assertEquals(15_000, connection.connectTimeout)
        assertEquals(30_000, connection.readTimeout)
        assertTrue(connection.timeoutsConfiguredBeforeOutputStream)
        assertTrue(connection.timeoutsConfiguredBeforeResponseCode)
    }

    @Test
    fun `connect timeout is indeterminate`() = runTest {
        val timeout = SocketTimeoutException("connect")
        val result = HttpSignedPutTransport {
            throw timeout
        }
            .put("http://signed-put.test/upload", headers, bytes)

        assertEquals(SignedPutResult.Indeterminate(timeout), result)
    }

    @Test
    fun `read timeout is indeterminate`() = runTest {
        val timeout = SocketTimeoutException("read")
        val connection = RecordingHttpURLConnection(URL("http://signed-put.test/upload"))
        connection.responseCodeFailure = timeout

        val result = HttpSignedPutTransport {
            connection
        }
            .put(connection.url.toString(), headers, bytes)

        assertEquals(SignedPutResult.Indeterminate(timeout), result)
    }

    @Test
    fun `HTTP transport rejects signed content length mismatch before network IO`() = runTest {
        RecordingHttpServer().use { server ->
            val error =
                try {
                    HttpSignedPutTransport()
                        .put(server.url, headers + ("content-length" to "4"), bytes)
                    null
                } catch (error: PrivateMediaGatewayException) {
                    error
                }

            assertEquals(PrivateMediaGatewayError.MALFORMED_RESPONSE, error?.reason)
            assertTrue(server.requests.isEmpty())
        }
    }

    @Test
    fun `HTTP transport rejects a conflicting content length duplicate before network IO`() =
        runTest {
            RecordingHttpServer().use { server ->
                val error =
                    try {
                        HttpSignedPutTransport()
                            .put(server.url, headers + ("Content-Length" to "3"), bytes)
                        null
                    } catch (error: PrivateMediaGatewayException) {
                        error
                    }

                assertEquals(PrivateMediaGatewayError.MALFORMED_RESPONSE, error?.reason)
                assertTrue(server.requests.isEmpty())
            }
        }

    private class RecordingHttpURLConnection(url: URL) : HttpURLConnection(url) {
        var responseCodeFailure: IOException? = null
        var timeoutsConfiguredBeforeOutputStream = false
        var timeoutsConfiguredBeforeResponseCode = false

        override fun connect() = Unit

        override fun disconnect() = Unit

        override fun usingProxy() = false

        override fun getOutputStream(): OutputStream {
            timeoutsConfiguredBeforeOutputStream = connectTimeout == 15_000 && readTimeout == 30_000
            return ByteArrayOutputStream()
        }

        override fun getResponseCode(): Int {
            timeoutsConfiguredBeforeResponseCode = connectTimeout == 15_000 && readTimeout == 30_000
            responseCodeFailure?.let { throw it }
            return HTTP_OK
        }
    }

    private data class ObservedRequest(
        val contentLengths: List<String>,
        val transferEncoding: List<String>?,
        val body: ByteArray,
    )

    private class RecordingHttpServer : AutoCloseable {
        val requests = CopyOnWriteArrayList<ObservedRequest>()
        private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val url: String
            get() = "http://127.0.0.1:${server.address.port}/upload"

        init {
            server.createContext("/upload") { exchange ->
                requests +=
                    ObservedRequest(
                        contentLengths =
                            exchange.requestHeaders["Content-length"]?.toList().orEmpty(),
                        transferEncoding = exchange.requestHeaders["Transfer-encoding"]?.toList(),
                        body = exchange.requestBody.use { it.readBytes() },
                    )
                exchange.sendResponseHeaders(200, -1)
                exchange.close()
            }
            server.start()
        }

        override fun close() = server.stop(0)
    }
}
