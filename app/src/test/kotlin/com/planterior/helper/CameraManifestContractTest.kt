package com.planterior.helper

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.FileProvider
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class CameraManifestContractTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `camera is the only photo input permission and picker needs no broad storage permission`() {
        val permissions =
            context.packageManager
                .getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
                .requestedPermissions
                .orEmpty()
                .toSet()

        assertTrue(Manifest.permission.CAMERA in permissions)
        assertFalse(Manifest.permission.READ_EXTERNAL_STORAGE in permissions)
        assertFalse("android.permission.READ_MEDIA_IMAGES" in permissions)
        assertFalse(Manifest.permission.WRITE_EXTERNAL_STORAGE in permissions)
    }

    @Test
    fun `camera FileProvider is private and grants URI access explicitly`() {
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
