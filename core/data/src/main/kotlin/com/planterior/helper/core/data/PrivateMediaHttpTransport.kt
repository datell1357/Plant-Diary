package com.planterior.helper.core.data

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible

class HttpSignedPutTransport : SignedPutTransport {
    private val connectionOpener: (URL) -> HttpURLConnection

    constructor() : this({ url -> url.openConnection() as HttpURLConnection })

    internal constructor(connectionOpener: (URL) -> HttpURLConnection) {
        this.connectionOpener = connectionOpener
    }

    override suspend fun put(
        url: String,
        headers: Map<String, String>,
        bytes: ByteArray,
    ): SignedPutResult {
        val contentLengthHeaders = headers.filterKeys {
            it.equals("content-length", ignoreCase = true)
        }
        if (
            contentLengthHeaders.size != 1 ||
                contentLengthHeaders["content-length"] != bytes.size.toString()
        ) {
            throw PrivateMediaGatewayException(PrivateMediaGatewayError.MALFORMED_RESPONSE)
        }
        return try {
            runInterruptible(Dispatchers.IO) {
                val connection = connectionOpener(URL(url))
                try {
                    connection.connectTimeout = CONNECT_TIMEOUT_MILLIS
                    connection.readTimeout = READ_TIMEOUT_MILLIS
                    connection.requestMethod = "PUT"
                    connection.instanceFollowRedirects = false
                    connection.doOutput = true
                    connection.setFixedLengthStreamingMode(bytes.size)
                    headers
                        .filterKeys { !it.equals("content-length", ignoreCase = true) }
                        .forEach(connection::setRequestProperty)
                    connection.outputStream.use { it.write(bytes) }
                    when (connection.responseCode) {
                        in 200..299 -> SignedPutResult.Uploaded
                        HttpURLConnection.HTTP_PRECON_FAILED -> SignedPutResult.PreconditionFailed
                        else ->
                            throw PrivateMediaGatewayException(
                                PrivateMediaGatewayError.UPLOAD_REJECTED
                            )
                    }
                } finally {
                    connection.disconnect()
                }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: PrivateMediaGatewayException) {
            throw error
        } catch (error: IOException) {
            SignedPutResult.Indeterminate(error)
        }
    }

    private companion object {
        const val CONNECT_TIMEOUT_MILLIS = 15_000
        const val READ_TIMEOUT_MILLIS = 30_000
    }
}
