package com.planterior.helper.feature.shop

import java.io.File
import java.security.MessageDigest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CatalogMediaFixtureContractTest {
    @Test
    fun `canonical API 37 media and visual evidence match their manifest contract`() {
        val manifest = jsonResource("todo14/catalog-media-fixture.json")
        val file = manifest.string("file")
        val storagePath = manifest.string("storagePath")
        val contentType = manifest.string("contentType")
        val bytes = resource(file)
        val (width, height) = losslessWebpDimensions(bytes)
        val storageMetadata = manifest.getValue("storageMetadata").jsonObject

        assertEquals(3, manifest.getValue("contractVersion").jsonPrimitive.int)
        assertEquals("webp", file.substringAfterLast('.'))
        assertEquals("webp", storagePath.substringAfterLast('.'))
        assertEquals("image/webp", contentType)
        assertTrue(bytes.copyOfRange(0, 4).contentEquals("RIFF".encodeToByteArray()))
        assertTrue(bytes.copyOfRange(8, 12).contentEquals("WEBP".encodeToByteArray()))
        assertEquals(contentType, detectCatalogMediaContentType(bytes))
        assertEquals(manifest.getValue("byteSize").jsonPrimitive.long, bytes.size.toLong())
        assertEquals(manifest.string("sha256"), sha256(bytes))
        assertEquals(manifest.getValue("width").jsonPrimitive.int, width)
        assertEquals(manifest.getValue("height").jsonPrimitive.int, height)
        assertEquals(width.toString(), storageMetadata.string("width"))
        assertEquals(height.toString(), storageMetadata.string("height"))
        assertEquals(manifest.string("sha256"), storageMetadata.string("sha256"))
        assertEquals(
            manifest.getValue("mediaRevision").jsonPrimitive.long.toString(),
            storageMetadata.string("mediaRevision"),
        )
        assertTrue(storagePath.contains("/${manifest.string("sha256")}."))
        assertNull(
            validateCatalogMediaMetadata(
                storagePath,
                CatalogMediaObjectMetadata(
                    contentType,
                    bytes.size.toLong(),
                    width,
                    height,
                    manifest.string("sha256"),
                    manifest.getValue("mediaRevision").jsonPrimitive.long,
                ),
            )
        )

        val visual = manifest.getValue("visualEvidence").jsonObject
        val visualWidth = visual.getValue("width").jsonPrimitive.int
        val visualHeight = visual.getValue("height").jsonPrimitive.int
        val files = visual.getValue("files").jsonObject
        assertEquals(2, visual.getValue("contractVersion").jsonPrimitive.int)
        assertEquals(37, visual.getValue("apiLevel").jsonPrimitive.int)
        assertEquals("pixel_7", visual.string("deviceProfile"))
        assertEquals("swiftshader_indirect", visual.string("renderer"))
        assertEquals(1080, visualWidth)
        assertEquals(2400, visualHeight)
        assertEquals(3, visual.getValue("requiredIndependentWipedRuns").jsonPrimitive.int)
        assertEquals("activity-enable-edge-to-edge", visual.string("windowInsetsContract"))
        assertEquals(
            setOf(
                "todo14-api37-shop.png",
                "todo14-api37-warehouse.png",
                "todo14-api37-item-detail.png",
                "todo14-api37-background.png",
            ),
            files.keys,
        )
        files.forEach { (name, value) ->
            val entry = value.jsonObject
            val image = resource(entry.string("file"))
            assertTrue("$name is not PNG", image.copyOfRange(0, 8).contentEquals(PNG_MAGIC))
            assertEquals(visualWidth, bigEndianInt(image, 16))
            assertEquals(visualHeight, bigEndianInt(image, 20))
            assertEquals(entry.string("sha256"), sha256(image))
        }

        val metadata = jsonResource(visual.string("metadataFile"))
        assertEquals(6, metadata.getValue("contractVersion").jsonPrimitive.int)
        assertEquals(37, metadata.getValue("apiLevel").jsonPrimitive.int)
        assertEquals(visual.string("deviceProfile"), metadata.string("deviceProfile"))
        assertEquals(visual.string("renderer"), metadata.string("renderer"))
        assertEquals(
            visual.getValue("requiredIndependentWipedRuns").jsonPrimitive.int,
            metadata.getValue("independentlyWipedAvdRuns").jsonPrimitive.int,
        )
        assertEquals("${visualWidth}x$visualHeight", metadata.string("evidenceDimensions"))
        assertEquals(
            visual.getValue("contractVersion").jsonPrimitive.int,
            metadata.getValue("visualEvidenceContractVersion").jsonPrimitive.int,
        )
        assertEquals(visual.string("windowInsetsContract"), metadata.string("windowInsetsContract"))
        assertEquals("#FF3D6642", metadata.string("selectedCategoryContainerArgb"))
        assertEquals("#FFFFFFFF", metadata.string("selectedCategoryContentArgb"))
        assertEquals("transparent", metadata.string("unselectedCategoryContainer"))
        assertEquals("#FF6B7280", metadata.string("unselectedCategoryContentArgb"))
        assertEquals("#FFE5E7EB", metadata.string("categoryBorderArgb"))
        assertEquals("current-content-color", metadata.string("categoryStateLayer"))
        assertEquals(3, metadata.getValue("fixtureManifestVersion").jsonPrimitive.int)
        assertEquals(manifest.string("sha256"), metadata.string("fixtureSha256"))
        val evidence = metadata.getValue("evidence").jsonObject
        assertEquals(
            files.getValue("todo14-api37-shop.png").jsonObject.string("sha256"),
            evidence.string("shop"),
        )
        assertEquals(
            files.getValue("todo14-api37-warehouse.png").jsonObject.string("sha256"),
            evidence.string("warehouse"),
        )
        assertEquals(
            files.getValue("todo14-api37-item-detail.png").jsonObject.string("sha256"),
            evidence.string("itemDetail"),
        )
        assertEquals(
            files.getValue("todo14-api37-background.png").jsonObject.string("sha256"),
            evidence.string("background"),
        )

        val diff = jsonResource(visual.string("diffMetadataFile"))
        assertEquals(2, diff.getValue("contractVersion").jsonPrimitive.int)
        val cause = diff.getValue("cause").jsonObject
        assertEquals("figma-category-chip-chrome", cause.string("type"))
        assertTrue(cause.getValue("productionSourceChanged").jsonPrimitive.boolean)
        assertFalse(cause.getValue("fixtureChanged").jsonPrimitive.boolean)
        assertTrue(cause.getValue("windowInsetsHardened").jsonPrimitive.boolean)
        assertEquals("pixel_7", diff.getValue("reference").jsonObject.string("deviceProfile"))
        assertEquals("pixel_7", diff.getValue("current").jsonObject.string("deviceProfile"))
        val diffs = diff.getValue("diffs").jsonObject
        setOf("shop", "warehouse").forEach { name ->
            val entry = diffs.getValue(name).jsonObject
            assertTrue(entry.getValue("changed").jsonPrimitive.boolean)
            assertTrue(entry.getValue("diffPixels").jsonPrimitive.int > 0)
            assertTrue(entry.string("oldSha256") != entry.string("newSha256"))
            assertTrue(entry.getValue("alphaChannelIntact").jsonPrimitive.boolean)
        }
        setOf("itemDetail", "background").forEach { name ->
            val entry = diffs.getValue(name).jsonObject
            assertFalse(entry.getValue("changed").jsonPrimitive.boolean)
            assertEquals(0, entry.getValue("diffPixels").jsonPrimitive.int)
            assertEquals(entry.string("oldSha256"), entry.string("newSha256"))
            assertTrue(entry.getValue("alphaChannelIntact").jsonPrimitive.boolean)
        }
        val review = diff.getValue("review").jsonObject
        assertEquals("#3D6642", review.string("figmaCategorySelectedFill"))
        assertEquals("#FFFFFF", review.string("figmaCategorySelectedContent"))
        assertEquals(0, review.getValue("defaultMaterialPurplePixels").jsonPrimitive.int)
    }

    private fun jsonResource(path: String): JsonObject =
        resource(path).decodeToString().let(Json::parseToJsonElement).jsonObject

    private fun resource(path: String): ByteArray {
        val root = checkNotNull(System.getProperty("todo14.fixtureRoot"))
        val file = File(root, path)
        check(file.isFile) { "Missing canonical catalog fixture resource: $path" }
        return file.readBytes()
    }

    private fun losslessWebpDimensions(bytes: ByteArray): Pair<Int, Int> {
        require(bytes.size >= 25)
        require(bytes.copyOfRange(12, 16).contentEquals("VP8L".encodeToByteArray()))
        require(bytes[20] == 0x2f.toByte())
        val packed =
            (bytes[21].toInt() and 0xff) or
                ((bytes[22].toInt() and 0xff) shl 8) or
                ((bytes[23].toInt() and 0xff) shl 16) or
                ((bytes[24].toInt() and 0xff) shl 24)
        return ((packed and 0x3fff) + 1) to (((packed ushr 14) and 0x3fff) + 1)
    }

    private fun bigEndianInt(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xff) shl 24) or
            ((bytes[offset + 1].toInt() and 0xff) shl 16) or
            ((bytes[offset + 2].toInt() and 0xff) shl 8) or
            (bytes[offset + 3].toInt() and 0xff)

    private fun JsonObject.string(name: String): String = getValue(name).jsonPrimitive.content

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") {
            "%02x".format(it)
        }

    private companion object {
        val PNG_MAGIC = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)
    }
}
