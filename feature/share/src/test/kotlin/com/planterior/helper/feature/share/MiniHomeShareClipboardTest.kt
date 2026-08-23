package com.planterior.helper.feature.share

import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
class MiniHomeShareClipboardTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val url = "https://share.planterior.app/m/abc123"

    @Test
    @Config(sdk = [29])
    fun `api29 copies the exact url and asks for in app feedback`() {
        val clipboard = MiniHomeShareClipboard(context)

        val shown = clipboard.copy(url)

        assertTrue(shown)
        assertEquals(url, clipboardText())
    }

    @Test
    @Config(sdk = [32])
    fun `api32 still shows the app's own copy feedback`() {
        assertTrue(MiniHomeShareClipboard(context).copy(url))
    }

    @Test
    @Config(sdk = [33])
    fun `api33 does not duplicate the system copy overlay`() {
        val clipboard = MiniHomeShareClipboard(context)

        val shown = clipboard.copy(url)

        assertFalse(shown)
        assertEquals(url, clipboardText())
    }

    @Test
    @Config(sdk = [36])
    fun `current api keeps relying on the system copy overlay`() {
        assertFalse(MiniHomeShareClipboard(context).copy(url))
        assertTrue(Build.VERSION.SDK_INT >= 33)
    }

    @Test
    @Config(sdk = [36])
    fun `copied clip is marked sensitive so the url is not previewed`() {
        MiniHomeShareClipboard(context).copy(url)

        val clip =
            (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).primaryClip
        assertTrue(
            clip?.description?.extras?.getBoolean("android.content.extra.IS_SENSITIVE") == true
        )
    }

    private fun clipboardText(): String? =
        (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
            .primaryClip
            ?.getItemAt(0)
            ?.text
            ?.toString()
}
