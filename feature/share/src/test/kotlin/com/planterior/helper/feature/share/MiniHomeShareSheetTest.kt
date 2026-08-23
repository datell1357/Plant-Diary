package com.planterior.helper.feature.share

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.net.toUri
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class MiniHomeShareSheetTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val uri: Uri =
        "content://com.planterior.helper.fileprovider/share/mini-home-r7.png".toUri()

    @Test
    fun `image intent grants read only access with exact png mime and clip data`() {
        val intent = MiniHomeShareSheet.imageIntent(uri)

        assertEquals(Intent.ACTION_SEND, intent.action)
        assertEquals("image/png", intent.type)
        assertEquals(uri, intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java))
        assertEquals(uri, intent.clipData?.getItemAt(0)?.uri)
        assertEquals("image/png", intent.clipData?.description?.getMimeType(0))
        assertEquals(
            Intent.FLAG_GRANT_READ_URI_PERMISSION,
            intent.flags and
                (Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                    Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or
                    Intent.FLAG_GRANT_PREFIX_URI_PERMISSION),
        )
        assertEquals(0, intent.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
    }

    @Test
    fun `image intent points only at the app private share content uri`() {
        val intent = MiniHomeShareSheet.imageIntent(uri)
        val stream = intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)

        assertEquals("content", stream?.scheme)
        assertEquals("com.planterior.helper.fileprovider", stream?.authority)
        assertEquals("/share/mini-home-r7.png", stream?.path)
    }

    @Test
    fun `link intent shares the exact url as plain text`() {
        val intent = MiniHomeShareSheet.linkIntent(URL)

        assertEquals(Intent.ACTION_SEND, intent.action)
        assertEquals("text/plain", intent.type)
        assertEquals(URL, intent.getStringExtra(Intent.EXTRA_TEXT))
        assertEquals(0, intent.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        assertNull(intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java))
    }

    @Test
    fun `chooser wraps the payload intent and never leaks the url into its own extras`() {
        val chooser = MiniHomeShareSheet.chooser(context, MiniHomeShareSheet.linkIntent(URL))

        assertEquals(Intent.ACTION_CHOOSER, chooser.action)
        assertNotNull(chooser.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java))
        assertFalse(chooser.extras?.keySet().orEmpty().contains(Intent.EXTRA_TEXT))
    }

    @Test
    fun `no target is detected when nothing resolves the send intent`() {
        assertFalse(MiniHomeShareSheet.hasTarget(context, MiniHomeShareSheet.linkIntent(URL)))
        assertFalse(MiniHomeShareSheet.hasTarget(context, MiniHomeShareSheet.imageIntent(uri)))
    }

    private companion object {
        const val URL = "https://share.planterior.app/m/abc123"
    }
}
