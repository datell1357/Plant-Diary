package com.planterior.helper.feature.share

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.asImageBitmap
import com.planterior.helper.core.model.Revision
import com.planterior.helper.feature.minihome.MiniHomeIsometricProjection
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class MiniHomeShareImageTest {
    @Test
    fun `export scales any canonical 1_2 capture to exactly 1200 by 1000`() {
        listOf(300 to 250, 600 to 500, 2400 to 2000, 1200 to 1000).forEach { (width, height) ->
            val bytes = MiniHomeShareImageEncoder.encode(solidBitmap(width, height).asImageBitmap())
            val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)

            assertEquals(MiniHomeShareImage.WIDTH_PX, decoded.width)
            assertEquals(MiniHomeShareImage.HEIGHT_PX, decoded.height)
            assertTrue(isPng(bytes))
            decoded.recycle()
        }
    }

    // 8) 임의 비율 캡처를 억지로 늘리지 않는다

    @Test
    fun `a capture whose aspect ratio is not the canonical room is rejected`() {
        listOf(1000 to 1000, 1200 to 800, 800 to 1200, 1920 to 1080, 1210 to 1000, 602 to 500)
            .forEach { (width, height) ->
                val error = runCatching {
                    MiniHomeShareImageEncoder.encode(solidBitmap(width, height).asImageBitmap())
                }
                    .exceptionOrNull()
                assertTrue(
                    "${width}x$height must be rejected instead of distorted",
                    error is IllegalArgumentException,
                )
            }
    }

    @Test
    fun `a capture with a zero dimension is rejected before any scaling`() {
        listOf(0 to 1000, 1200 to 0, 0 to 0).forEach { (width, height) ->
            val error = runCatching {
                MiniHomeShareImageEncoder.requireCanonicalCapture(width, height)
            }
                .exceptionOrNull()
            assertTrue("${width}x$height must be rejected", error is IllegalArgumentException)
        }
    }

    @Test
    fun `bounded pixel rounding around the canonical ratio is still accepted`() {
        // 밀도 반올림으로 1px 어긋난 캡처는 허용한다.
        listOf(599 to 500, 601 to 500, 1199 to 1000, 1201 to 1001).forEach { (width, height) ->
            val bytes = MiniHomeShareImageEncoder.encode(solidBitmap(width, height).asImageBitmap())
            val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)

            assertEquals("${width}x$height", MiniHomeShareImage.WIDTH_PX, decoded.width)
            assertEquals("${width}x$height", MiniHomeShareImage.HEIGHT_PX, decoded.height)
            decoded.recycle()
        }
    }

    @Test
    fun `encoding never recycles the caller's source bitmap`() {
        val source = solidBitmap(600, 500)

        MiniHomeShareImageEncoder.encode(source.asImageBitmap())

        assertFalse("the caller still owns the captured bitmap", source.isRecycled)
        source.recycle()
    }

    @Test
    fun `an already exact capture is encoded without an intermediate copy`() {
        val source = solidBitmap(MiniHomeShareImage.WIDTH_PX, MiniHomeShareImage.HEIGHT_PX)

        val bytes = MiniHomeShareImageEncoder.encode(source.asImageBitmap())

        assertFalse(source.isRecycled)
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        assertEquals(MiniHomeShareImage.WIDTH_PX, decoded.width)
        decoded.recycle()
        source.recycle()
    }

    @Test
    fun `export aspect ratio matches the canonical 1_2 mini home room`() {
        assertEquals(
            1.2f,
            MiniHomeShareImage.WIDTH_PX.toFloat() / MiniHomeShareImage.HEIGHT_PX.toFloat(),
            0.0001f,
        )
        val projection =
            MiniHomeIsometricProjection(
                MiniHomeShareImage.WIDTH_PX.toFloat(),
                MiniHomeShareImage.HEIGHT_PX.toFloat(),
            )
        assertEquals(
            MiniHomeShareImage.HEIGHT_PX * MiniHomeIsometricProjection.FLOOR_TOP_RATIO,
            projection.floorTop,
            0.001f,
        )
    }

    @Test
    fun `encoded png carries no exif or textual metadata chunks`() {
        val bytes = MiniHomeShareImageEncoder.encode(solidBitmap(1200, 1000).asImageBitmap())

        val chunks = pngChunkTypes(bytes)
        assertTrue("IHDR" in chunks)
        assertTrue("IEND" in chunks)
        listOf("eXIf", "tEXt", "iTXt", "zTXt", "tIME", "iCCP").forEach { forbidden ->
            assertFalse("$forbidden must not be present", forbidden in chunks)
        }
    }

    @Test
    fun `encoding the same capture twice is byte identical`() {
        val captured = solidBitmap(600, 500).asImageBitmap()

        assertArrayEquals(
            MiniHomeShareImageEncoder.encode(captured),
            MiniHomeShareImageEncoder.encode(captured),
        )
    }

    @Test
    fun `file name carries the committed revision and no owner or home identity`() {
        val name = MiniHomeShareImage.fileName(Revision(42))

        assertTrue(name.contains("42"))
        assertTrue(name.endsWith(".png"))
        assertFalse(name.contains(MiniHomeShareFixtures.owner.value))
        assertFalse(name.contains(MiniHomeShareFixtures.layout().id.value))
        assertEquals(name, MiniHomeShareImage.fileName(Revision(42)))
    }

    private fun solidBitmap(width: Int, height: Int): Bitmap =
        Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
            eraseColor(android.graphics.Color.rgb(61, 102, 66))
        }

    private fun isPng(bytes: ByteArray): Boolean =
        bytes.size > 8 && bytes.copyOfRange(0, 8).contentEquals(PNG_SIGNATURE)

    private fun pngChunkTypes(bytes: ByteArray): Set<String> {
        assertTrue(isPng(bytes))
        val types = mutableSetOf<String>()
        var offset = 8
        while (offset + 8 <= bytes.size) {
            val length =
                ((bytes[offset].toInt() and 0xFF) shl 24) or
                    ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
                    ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
                    (bytes[offset + 3].toInt() and 0xFF)
            types += String(bytes, offset + 4, 4, Charsets.US_ASCII)
            offset += 12 + length
        }
        return types
    }

    private companion object {
        val PNG_SIGNATURE = byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10)
    }
}
