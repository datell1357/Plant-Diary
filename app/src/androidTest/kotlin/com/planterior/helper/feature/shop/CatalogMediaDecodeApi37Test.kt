package com.planterior.helper.feature.shop

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import com.planterior.helper.core.model.CatalogMediaIdentity
import com.planterior.helper.core.model.Revision
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.MessageDigest
import java.util.zip.CRC32
import java.util.zip.DeflaterOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 37, maxSdkVersion = 37)
class CatalogMediaDecodeApi37Test {
    @Test
    fun extremeLandscapePngIsSampledBeforePixelAllocation() {
        val bytes = solidPng(width = 32_768, height = 1_535)
        assertTrue(bytes.size <= 8 * 1024 * 1024)
        val digest = sha256(bytes)
        val identity =
            CatalogMediaIdentity(
                "catalog-assets/extreme-landscape/$digest.png",
                digest,
                bytes.size.toLong(),
                "image/png",
                32_768,
                1_535,
                Revision(1),
            )
        val loader =
            BoundedCatalogMediaLoader(
                CatalogMediaVerifiedSource { _, _ ->
                    CatalogMediaPayload(
                        bytes,
                        CatalogMediaObjectMetadata(
                            contentType = identity.mimeType,
                            sizeBytes = identity.byteSize,
                            width = identity.width,
                            height = identity.height,
                            sha256 = identity.sha256,
                            mediaRevision = identity.mediaRevision.value,
                        ),
                    )
                }
            )

        val result = runBlocking { loader.load(identity) }

        assertTrue(result is CatalogMediaLoadResult.Loaded)
        val bitmap = (result as CatalogMediaLoadResult.Loaded).bitmap
        assertEquals(512, bitmap.width)
        assertTrue(bitmap.height in 23..24)
        assertTrue(bitmap.allocationByteCount <= 768 * 768 * 4)
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun solidPng(width: Int, height: Int): ByteArray {
        val output = ByteArrayOutputStream()
        DataOutputStream(output).use { png ->
            png.write(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A))
            png.writePngChunk(
                "IHDR",
                ByteArrayOutputStream().use { bytes ->
                    DataOutputStream(bytes).use { data ->
                        data.writeInt(width)
                        data.writeInt(height)
                        data.writeByte(8)
                        data.writeByte(6)
                        data.writeByte(0)
                        data.writeByte(0)
                        data.writeByte(0)
                    }
                    bytes.toByteArray()
                },
            )
            val compressed =
                ByteArrayOutputStream().use { bytes ->
                    DeflaterOutputStream(bytes).use { deflater ->
                        val row = ByteArray(width * 4 + 1)
                        repeat(height) { deflater.write(row) }
                    }
                    bytes.toByteArray()
                }
            png.writePngChunk("IDAT", compressed)
            png.writePngChunk("IEND", byteArrayOf())
        }
        return output.toByteArray()
    }

    private fun DataOutputStream.writePngChunk(type: String, bytes: ByteArray) {
        val typeBytes = type.toByteArray(Charsets.US_ASCII)
        writeInt(bytes.size)
        write(typeBytes)
        write(bytes)
        val crc =
            CRC32().apply {
                update(typeBytes)
                update(bytes)
            }
        writeInt(crc.value.toInt())
    }
}
