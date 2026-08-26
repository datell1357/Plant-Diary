package com.planterior.helper.feature.shop

import java.io.File
import java.security.MessageDigest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
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
        assertEquals(3, visual.getValue("contractVersion").jsonPrimitive.int)
        assertEquals(37, visual.getValue("apiLevel").jsonPrimitive.int)
        assertEquals("pixel_7", visual.string("deviceProfile"))
        assertEquals("swiftshader_indirect", visual.string("renderer"))
        assertEquals(1080, visualWidth)
        assertEquals(2400, visualHeight)
        assertEquals(3, visual.getValue("requiredIndependentWipedRuns").jsonPrimitive.int)
        assertEquals(6, visual.getValue("systemImageRevision").jsonPrimitive.int)
        assertEquals(SYSTEM_IMAGE_FINGERPRINT, visual.string("systemImageBuildFingerprint"))
        assertEquals(420, visual.getValue("physicalDensityDpi").jsonPrimitive.int)
        assertEquals(1.0, visual.getValue("fontScale").jsonPrimitive.double, 0.0)
        assertEquals("ko-KR", visual.string("locale"))
        val animationScales = visual.getValue("animationScales").jsonObject
        setOf("window", "transition", "animator").forEach { name ->
            assertEquals(0.0, animationScales.getValue(name).jsonPrimitive.double, 0.0)
        }
        val sourceProvenance = visual.getValue("sourceProvenance").jsonObject
        assertEquals(REFERENCE_SOURCE_COMMIT, sourceProvenance.string("referenceCommit"))
        assertEquals(CAPTURE_HARNESS_COMMIT, sourceProvenance.string("captureHarnessCommit"))
        assertNotEquals(
            sourceProvenance.string("referenceCommit"),
            sourceProvenance.string("captureHarnessCommit"),
        )
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
            assertEquals(REFERENCE_VISUAL_HASHES.getValue(name), entry.string("referenceSha256"))
            assertEquals(CURRENT_VISUAL_HASHES.getValue(name), entry.string("sha256"))
            assertEquals(entry.string("referenceSha256"), sha256(image))
            assertNotEquals(entry.string("referenceSha256"), entry.string("sha256"))
        }

        val metadata = jsonResource(visual.string("metadataFile"))
        assertEquals(7, metadata.getValue("contractVersion").jsonPrimitive.int)
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
        assertEquals(
            visual.getValue("systemImageRevision"),
            metadata.getValue("systemImageRevision"),
        )
        assertEquals(
            visual.string("systemImageBuildFingerprint"),
            metadata.string("systemImageBuildFingerprint"),
        )
        assertEquals(visual.getValue("physicalDensityDpi"), metadata.getValue("physicalDensityDpi"))
        assertEquals(visual.getValue("fontScale"), metadata.getValue("fontScale"))
        assertEquals(visual.string("locale"), metadata.string("locale"))
        assertEquals(visual.getValue("animationScales"), metadata.getValue("animationScales"))
        assertEquals(visual.getValue("sourceProvenance"), metadata.getValue("sourceProvenance"))
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
        assertEquals(3, diff.getValue("contractVersion").jsonPrimitive.int)
        val reference = diff.getValue("reference").jsonObject
        val current = diff.getValue("current").jsonObject
        assertEquals(REFERENCE_SOURCE_COMMIT, reference.string("sourceCommit"))
        assertEquals(CAPTURE_HARNESS_COMMIT, current.string("captureHarnessCommit"))
        assertEquals(3, current.getValue("independentlyWipedRuns").jsonPrimitive.int)
        assertEquals(SYSTEM_IMAGE_FINGERPRINT, current.string("systemImageBuildFingerprint"))
        val cause = diff.getValue("cause").jsonObject
        assertEquals(
            "stale-contract-after-capture-and-production-source-changes",
            cause.string("type"),
        )
        assertTrue(cause.getValue("productionSourceChanged").jsonPrimitive.boolean)
        assertTrue(cause.getValue("captureHarnessChanged").jsonPrimitive.boolean)
        assertTrue(cause.getValue("historicalPngBytesPreserved").jsonPrimitive.boolean)
        assertFalse(cause.getValue("todo17LayoutModifiersChanged").jsonPrimitive.boolean)
        assertEquals(REFERENCE_SOURCE_COMMIT, cause.string("referenceCommit"))
        assertEquals(CAPTURE_HARNESS_COMMIT, cause.string("captureHarnessCommit"))
        assertNotEquals(cause.string("referenceCommit"), cause.string("captureHarnessCommit"))
        val diffs = diff.getValue("diffs").jsonObject
        val evidenceNames =
            mapOf(
                "shop" to "todo14-api37-shop.png",
                "warehouse" to "todo14-api37-warehouse.png",
                "itemDetail" to "todo14-api37-item-detail.png",
                "background" to "todo14-api37-background.png",
            )
        evidenceNames.forEach { (diffName, fileName) ->
            val entry = diffs.getValue(diffName).jsonObject
            val fileEntry = files.getValue(fileName).jsonObject
            assertTrue(entry.getValue("changed").jsonPrimitive.boolean)
            assertEquals(REFERENCE_VISUAL_HASHES.getValue(fileName), entry.string("oldSha256"))
            assertEquals(CURRENT_VISUAL_HASHES.getValue(fileName), entry.string("newSha256"))
            assertEquals(fileEntry.string("referenceSha256"), entry.string("oldSha256"))
            assertEquals(fileEntry.string("sha256"), entry.string("newSha256"))
            assertEquals(evidence.string(diffName), entry.string("newSha256"))
            assertEquals(
                DIFF_PIXELS.getValue(diffName),
                entry.getValue("diffPixels").jsonPrimitive.int,
            )
            assertTrue(entry.getValue("diffRatio").jsonPrimitive.double > 0.0)
            assertTrue(entry.getValue("rmseNormalized").jsonPrimitive.double > 0.0)
            assertTrue(entry.getValue("ssimDistortionNormalized").jsonPrimitive.double > 0.0)
            assertTrue(entry.getValue("alphaChannelIntact").jsonPrimitive.boolean)
        }
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
        const val REFERENCE_SOURCE_COMMIT = "6efb7478984b6f6ba59448542787bde2c0bbea71"
        const val CAPTURE_HARNESS_COMMIT = "768edfc66e060fa36434bac3ab730682a6f0294f"
        const val SYSTEM_IMAGE_FINGERPRINT =
            "google/sdk_gphone64_arm64/emu64a:17/CE2A.260420.019/15611780:userdebug/dev-keys"
        val REFERENCE_VISUAL_HASHES =
            mapOf(
                "todo14-api37-shop.png" to
                    "196877f42eb10e6c29b9a5395a4b4890d5c5b10f79d22f4d283db4590582bfef",
                "todo14-api37-warehouse.png" to
                    "5d63ff77c6343a38897e52822a65cf618ac4f4485acccbc53ab6b527dd4ee817",
                "todo14-api37-item-detail.png" to
                    "55ca1a83fe3f71b04d89d026f57c59ec286ac19832699bea70f0a6cac7ca9c3e",
                "todo14-api37-background.png" to
                    "9157f71c9fd4ee6157d2f95f7853b90205c1452ae5138ceb224439a3826787c2",
            )
        val CURRENT_VISUAL_HASHES =
            mapOf(
                "todo14-api37-shop.png" to
                    "8a4c38a0aefc6976c9cca1b165544b389f4043180ae8bd97c06336584bb8fc1f",
                "todo14-api37-warehouse.png" to
                    "998440007b40f0ab85e1a0fd3100fae1e1d7e22856aac3ec42f97f7a990bcfd3",
                "todo14-api37-item-detail.png" to
                    "1d4c40e78c51f001750fa97018080c4838aa72debb29c4c63dfb3ee64e680da1",
                "todo14-api37-background.png" to
                    "fa3583a312b8f87e9c37bf4e71f36515d650e80b86e121abd5501b205901a241",
            )
        val DIFF_PIXELS =
            mapOf(
                "shop" to 129384,
                "warehouse" to 102779,
                "itemDetail" to 135938,
                "background" to 206950,
            )
        val PNG_MAGIC = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)
    }
}
