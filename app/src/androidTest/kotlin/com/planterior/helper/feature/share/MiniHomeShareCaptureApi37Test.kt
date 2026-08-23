package com.planterior.helper.feature.share

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.planterior.helper.core.designsystem.theme.PlanteriorTheme
import com.planterior.helper.core.model.AccountId
import com.planterior.helper.core.model.ItemId
import com.planterior.helper.core.model.MiniHomeId
import com.planterior.helper.core.model.PersonalPlantId
import com.planterior.helper.core.model.PlacementId
import com.planterior.helper.core.model.Revision
import com.planterior.helper.feature.minihome.GridPosition
import com.planterior.helper.feature.minihome.MiniHomeDecorationChoice
import com.planterior.helper.feature.minihome.MiniHomeLayout
import com.planterior.helper.feature.minihome.MiniHomePhotoLoader
import com.planterior.helper.feature.minihome.MiniHomePhotoRequest
import com.planterior.helper.feature.minihome.MiniHomePlacement
import com.planterior.helper.feature.minihome.MiniHomePlacementPolicy
import com.planterior.helper.feature.minihome.MiniHomePlacementTarget
import com.planterior.helper.feature.minihome.MiniHomePlantChoice
import com.planterior.helper.feature.minihome.MiniHomeZIndex
import com.planterior.helper.feature.minihome.PlaceholderMiniHomePhotoLoader
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 실제 렌더러가 만든 GraphicsLayer 캡처가 확정 규격 PNG로 결정적으로 인코딩되는지 확인한다.
 *
 * Robolectric은 실제 draw pipeline을 돌리지 않으므로 이 경로는 기기에서만 검증할 수 있다. 캡처는 레이어 기록 신호를 기다린 뒤에만 수행하며 프레임 대기나
 * sleep을 쓰지 않는다.
 */
@RunWith(AndroidJUnit4::class)
class MiniHomeShareCaptureApi37Test {
    @get:Rule val compose = createComposeRule()

    private val owner = AccountId("owner-api37")
    private val token = MiniHomeShareCaptureToken(owner, Revision(7), generation = 1L)

    private fun target(): MiniHomeShareTarget =
        MiniHomeShareTarget(
            owner = owner,
            committed =
                MiniHomeLayout(
                    MiniHomeId("mini-home-api37"),
                    "우리 집 식물원",
                    MiniHomePlacementPolicy.layer(
                        listOf(
                            MiniHomePlacement(
                                PlacementId("placement-a"),
                                MiniHomePlacementTarget.Plant(PersonalPlantId("plant-a")),
                                GridPosition(1, 1),
                                MiniHomeZIndex(0),
                            ),
                            MiniHomePlacement(
                                PlacementId("placement-b"),
                                MiniHomePlacementTarget.Decoration(ItemId("item-a")),
                                GridPosition(3, 2),
                                MiniHomeZIndex(1),
                            ),
                        )
                    ),
                    Revision(7),
                    Instant.ofEpochMilli(1_700_000_000_000L),
                ),
            plants =
                listOf(
                    MiniHomePlantChoice(
                        PersonalPlantId("plant-a"),
                        "몬스테라",
                        "users/owner-api37/plants/plant-a/private.jpg",
                    )
                ),
            decorations = listOf(MiniHomeDecorationChoice(ItemId("item-a"), "원목 테이블")),
        )

    @Test
    fun capturedCommittedRoomEncodesToExactExportSizeDeterministically() {
        val handle = mountCaptureSurface()

        val captured = runBlocking {
            handle.awaitRecorded(token)
            handle.encode(token)
        }

        val decoded = BitmapFactory.decodeByteArray(captured, 0, captured.size)
        assertEquals(MiniHomeShareImage.WIDTH_PX, decoded.width)
        assertEquals(MiniHomeShareImage.HEIGHT_PX, decoded.height)
        // GPU layer를 다시 읽으면 다른 테스트의 graphics teardown과 경합할 수 있다. 동일한 실제 layer readback을
        // 두 번 인코딩해 PNG 결정성을 검사하고, layer 경계 자체는 위의 captured 값으로 한 번만 검사한다.
        val first = MiniHomeShareImageEncoder.encode(decoded.asImageBitmap())
        val second = MiniHomeShareImageEncoder.encode(decoded.asImageBitmap())
        assertArrayEquals(first, second)
        decoded.recycle()
    }

    @Test
    fun privatePhotoCompletionCannotChangeExportBytesOrInvokeTheLoader() {
        val loader = ControlledPhotoLoader()
        val handle = mountCaptureSurface(loader)
        val captured = runBlocking {
            handle.awaitRecorded(token)
            // This is the sole GraphicsLayer readback. Keep this immutable capture for every later encode.
            handle.layer.toImageBitmap()
        }
        val capturedBitmap = captured.asAndroidBitmap()

        try {
            assertEquals(0, loader.calls.get())
            compose.runOnIdle { assertTrue(loader.complete()) }
            compose.waitForIdle()
            assertEquals(0, loader.calls.get())

            val first = MiniHomeShareImageEncoder.encode(captured)
            val second = MiniHomeShareImageEncoder.encode(captured)
            assertArrayEquals(first, second)

            val decoded = BitmapFactory.decodeByteArray(first, 0, first.size)
            try {
                assertEquals(MiniHomeShareImage.WIDTH_PX, decoded.width)
                assertEquals(MiniHomeShareImage.HEIGHT_PX, decoded.height)
                assertPngHasNoMetadata(first)
            } finally {
                decoded.recycle()
            }
        } finally {
            capturedBitmap.recycle()
            loader.recycleIfNotDelivered()
        }
    }

    @Test
    fun captureIsRefusedUntilTheLayerHasActuallyRecorded() {
        lateinit var handle: MiniHomeShareCaptureHandle
        compose.setContent {
            PlanteriorTheme {
                handle = rememberMiniHomeShareCaptureHandle()
                // 캡처 표면을 붙이지 않으므로 어떤 기록 신호도 오지 않는다.
            }
        }
        compose.waitForIdle()

        val error = runCatching { runBlocking { handle.encode(token) } }.exceptionOrNull()

        assertTrue("encoding without a record signal must fail", error is IllegalStateException)
    }

    @Test
    fun captureIsRefusedForAStaleGenerationEvenAfterRecording() {
        val handle = mountCaptureSurface()
        runBlocking { handle.awaitRecorded(token) }

        val stale = token.copy(generation = token.generation + 1)
        val error = runCatching { runBlocking { handle.encode(stale) } }.exceptionOrNull()

        assertTrue("a stale generation must never encode", error is IllegalStateException)
    }

    /** setContent는 규칙당 한 번만 호출할 수 있으므로 표면을 한 번 붙이고 손잡이를 돌려준다. */
    private fun mountCaptureSurface(
        photoLoader: MiniHomePhotoLoader = PlaceholderMiniHomePhotoLoader
    ): MiniHomeShareCaptureHandle {
        lateinit var handle: MiniHomeShareCaptureHandle
        compose.setContent {
            PlanteriorTheme {
                handle = rememberMiniHomeShareCaptureHandle()
                MiniHomeShareCaptureSurface(
                    target = target(),
                    modifier = Modifier.size(360.dp, 300.dp),
                    handle = handle,
                    captureToken = token,
                    photoLoader = photoLoader,
                )
            }
        }
        compose.waitForIdle()
        return handle
    }

    private class ControlledPhotoLoader : MiniHomePhotoLoader {
        val calls = AtomicInteger()
        private val delivered = AtomicBoolean()
        private val bitmap =
            Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888).apply {
                eraseColor(Color.MAGENTA)
            }
        private val result = CompletableDeferred<Bitmap>()

        override suspend fun load(request: MiniHomePhotoRequest): Bitmap {
            calls.incrementAndGet()
            return result.await().also { delivered.set(true) }
        }

        fun complete(): Boolean = result.complete(bitmap)

        fun recycleIfNotDelivered() {
            if (!delivered.get()) bitmap.recycle()
        }
    }

    private fun assertPngHasNoMetadata(bytes: ByteArray) {
        val chunks = pngChunkTypes(bytes)
        assertFalse("eXIf must not be present", "eXIf" in chunks)
        assertFalse("tEXt must not be present", "tEXt" in chunks)
        assertFalse("iTXt must not be present", "iTXt" in chunks)
        assertFalse("zTXt must not be present", "zTXt" in chunks)
        assertFalse("tIME must not be present", "tIME" in chunks)
        assertFalse("iCCP must not be present", "iCCP" in chunks)
    }

    private fun pngChunkTypes(bytes: ByteArray): Set<String> {
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
}
