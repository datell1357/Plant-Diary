package com.planterior.helper

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.FileProvider
import androidx.test.core.app.ApplicationProvider
import com.planterior.helper.core.model.AccountId
import com.planterior.helper.core.model.Revision
import com.planterior.helper.feature.share.MiniHomeShareImage
import com.planterior.helper.feature.share.MiniHomeShareImageStore
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 공유 이미지는 앱 전용 cache 하위 share/ 경로에서만 read-only content URI로 노출된다.
 *
 * Robolectric은 [FileProvider]의 경로 전략을 authority 단위로 정적 캐시하고 테스트 메서드마다 새 임시 데이터 디렉터리를 준다. 그래서 URI를
 * 실제로 만드는 검증은 한 메서드 안에서 모두 수행한다.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ShareFileProviderContractTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val owner = AccountId("owner-share-1")

    @Test
    fun `expired share cache is swept on store access while active artifacts remain`() {
        val directory =
            File(context.cacheDir, "share").apply {
                deleteRecursively()
                mkdirs()
            }
        val now = Instant.parse("2026-08-25T12:00:00Z")
        val expired = File(directory, "expired.png").apply { writeBytes(byteArrayOf(1)) }
        val active = File(directory, "active.png").apply { writeBytes(byteArrayOf(2)) }
        assertTrue(
            expired.setLastModified(
                now.minus(com.planterior.helper.feature.share.MiniHomeShareLink.LIFETIME)
                    .minusMillis(1)
                    .toEpochMilli()
            )
        )
        assertTrue(
            active.setLastModified(
                now.minus(com.planterior.helper.feature.share.MiniHomeShareLink.LIFETIME)
                    .plusMillis(1)
                    .toEpochMilli()
            )
        )

        MiniHomeShareImageStore(context, Clock.fixed(now, ZoneOffset.UTC))

        assertFalse(expired.exists())
        assertTrue(active.exists())
        active.delete()
    }

    @Test
    fun `share and camera paths are exposed as private read only content uris`() {
        val store = MiniHomeShareImageStore(context)
        store.clear()

        val uri = store.write(owner, Revision(7), byteArrayOf(1, 2, 3))

        assertEquals("content", uri.scheme)
        assertEquals("${context.packageName}.fileprovider", uri.authority)
        assertEquals("/share/${MiniHomeShareImage.fileName(Revision(7))}", uri.path)

        val stored = File(context.cacheDir, "share").listFiles().orEmpty().single()
        assertTrue(stored.absolutePath.startsWith(context.cacheDir.absolutePath))
        assertEquals(MiniHomeShareImage.fileName(Revision(7)), stored.name)
        assertFalse("file name must not expose the owner", stored.name.contains(owner.value))

        // 새 revision을 쓰면 권한이 필요한 최신 파일 하나만 남는다.
        store.write(owner, Revision(8), byteArrayOf(4))
        val afterRewrite = File(context.cacheDir, "share").listFiles().orEmpty()
        assertEquals(1, afterRewrite.size)
        assertEquals(MiniHomeShareImage.fileName(Revision(8)), afterRewrite.single().name)

        // 계정 전환·화면 종료에서는 어떤 파일도 남지 않는다.
        store.clear()
        assertTrue(File(context.cacheDir, "share").listFiles().orEmpty().isEmpty())

        // 기존 카메라 경로도 그대로 노출된다.
        val cameraDirectory = File(context.cacheDir, "camera").apply { mkdirs() }
        val cameraFile =
            File(cameraDirectory, "photo-contract.jpg").apply { writeBytes(byteArrayOf(1)) }
        val cameraUri =
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", cameraFile)
        assertEquals("/camera/photo-contract.jpg", cameraUri.path)
        cameraFile.delete()
    }
}

/** FileProvider 자체가 비공개이며 URI 권한을 명시적으로만 준다는 계약을 고정한다. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ShareFileProviderManifestTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `share file provider stays private and grants uri access explicitly`() {
        val provider =
            context.packageManager.getProviderInfo(
                ComponentName(context, FileProvider::class.java),
                PackageManager.GET_META_DATA,
            )

        assertEquals("${context.packageName}.fileprovider", provider.authority)
        assertFalse(provider.exported)
        assertTrue(provider.grantUriPermissions)
    }
}
