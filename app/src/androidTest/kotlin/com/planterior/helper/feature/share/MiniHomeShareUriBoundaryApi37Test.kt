package com.planterior.helper.feature.share

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.exifinterface.media.ExifInterface
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.planterior.helper.core.model.AccountId
import com.planterior.helper.core.model.Revision
import java.io.ByteArrayOutputStream
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 실제 기기·에뮬레이터 경계에서 공유 이미지 URI 계약을 확인한다.
 *
 * JVM 테스트는 FileProvider 경로 전략을 Robolectric 임시 디렉터리로 대신하지만, 실제 grant 동작과 ContentResolver의 MIME 응답은
 * 여기서만 확인할 수 있다.
 */
@RunWith(AndroidJUnit4::class)
class MiniHomeShareUriBoundaryApi37Test {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val owner = AccountId("owner-api37")
    private lateinit var store: MiniHomeShareImageStore

    @Before
    fun setUp() {
        store = MiniHomeShareImageStore(context)
        store.clear()
    }

    @After
    fun tearDown() {
        store.clear()
    }

    @Test
    fun exportedPngIsExactlyTwelveHundredByOneThousandAndCarriesNoMetadata() {
        val uri = store.write(owner, Revision(7), encodedRoomPng())

        val bytes =
            requireNotNull(context.contentResolver.openInputStream(uri)).use { it.readBytes() }
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)

        assertEquals(MiniHomeShareImage.WIDTH_PX, decoded.width)
        assertEquals(MiniHomeShareImage.HEIGHT_PX, decoded.height)
        assertEquals("image/png", context.contentResolver.getType(uri))

        val exif = ExifInterface(bytes.inputStream())
        assertNull(exif.getAttribute(ExifInterface.TAG_DATETIME))
        assertNull(exif.getAttribute(ExifInterface.TAG_GPS_LATITUDE))
        assertNull(exif.getAttribute(ExifInterface.TAG_MAKE))
        assertFalse(bytes.containsAscii("eXIf"))
        assertFalse(bytes.containsAscii("tEXt"))
        assertFalse(bytes.containsAscii(owner.value))
        decoded.recycle()
    }

    @Test
    fun shareUriIsPrivateReadOnlyAndNeverGrantsWrite() {
        val uri = store.write(owner, Revision(7), encodedRoomPng())
        val intent = MiniHomeShareSheet.imageIntent(uri)

        assertEquals(ContentResolver.SCHEME_CONTENT, uri.scheme)
        assertEquals("${context.packageName}.fileprovider", uri.authority)
        assertEquals(
            Intent.FLAG_GRANT_READ_URI_PERMISSION,
            intent.flags and
                (Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                    Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or
                    Intent.FLAG_GRANT_PREFIX_URI_PERMISSION),
        )
        assertEquals("image/png", intent.clipData?.description?.getMimeType(0))
        assertEquals(uri, intent.clipData?.getItemAt(0)?.uri)
    }

    @Test
    fun cacheHoldsOnlyTheNewestGrantableFileAndClearsCompletely() {
        store.write(owner, Revision(7), encodedRoomPng())
        store.write(owner, Revision(8), encodedRoomPng())

        val directory = File(context.cacheDir, "share")
        assertEquals(1, directory.listFiles().orEmpty().size)
        assertEquals(
            MiniHomeShareImage.fileName(Revision(8)),
            directory.listFiles().orEmpty().single().name,
        )

        store.clear()

        assertTrue(directory.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun noTargetIsDetectedWithoutOpeningASheet() {
        val intent = MiniHomeShareSheet.linkIntent("https://share.planterior.app/m/abc123")

        // 에뮬레이터 이미지에 따라 대상 유무가 달라지므로 결과가 아니라 판정 경로만 확인한다.
        val hasTarget = MiniHomeShareSheet.hasTarget(context, intent)
        assertEquals(hasTarget, intent.resolveActivity(context.packageManager) != null)
    }

    private fun encodedRoomPng(): ByteArray {
        val bitmap =
            Bitmap.createBitmap(600, 500, Bitmap.Config.ARGB_8888).apply {
                eraseColor(android.graphics.Color.rgb(238, 243, 240))
            }
        val scaled =
            Bitmap.createScaledBitmap(
                bitmap,
                MiniHomeShareImage.WIDTH_PX,
                MiniHomeShareImage.HEIGHT_PX,
                true,
            )
        val bytes =
            ByteArrayOutputStream().use { output ->
                check(scaled.compress(Bitmap.CompressFormat.PNG, 100, output))
                output.toByteArray()
            }
        bitmap.recycle()
        scaled.recycle()
        return bytes
    }

    private fun ByteArray.containsAscii(needle: String): Boolean {
        val target = needle.toByteArray(Charsets.US_ASCII)
        if (target.isEmpty() || target.size > size) return false
        outer@ for (start in 0..size - target.size) {
            for (offset in target.indices) {
                if (this[start + offset] != target[offset]) continue@outer
            }
            return true
        }
        return false
    }
}
