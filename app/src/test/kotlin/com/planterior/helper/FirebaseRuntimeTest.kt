package com.planterior.helper

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.google.firebase.FirebaseApp
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class FirebaseRuntimeTest {
    @Before
    fun setUp() {
        clearFirebaseApps()
    }

    @After
    fun tearDown() {
        clearFirebaseApps()
    }

    private fun clearFirebaseApps() {
        FirebaseApp.getApps(ApplicationProvider.getApplicationContext())
            .forEach(FirebaseApp::delete)
    }

    @Test
    fun `shared initializer is idempotent for application and activity callers`() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val first = FirebaseRuntime.initialize(context)
        val second = FirebaseRuntime.initialize(context)

        assertSame(first, second)
    }

    @Test
    fun `shared initializer safely declines incomplete configuration`() {
        val context = ApplicationProvider.getApplicationContext<Application>()

        assertNull(
            FirebaseRuntime.initialize(
                context,
                FirebaseRuntime.Configuration("", "", "", ""),
            )
        )
    }

    @Test
    fun `storage bucket is required and installed in Firebase options`() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val incomplete =
            FirebaseRuntime.Configuration(
                projectId = "demo-planterior-release",
                applicationId = "1:123456789012:android:0123456789abcdef",
                apiKey = "verification-public-api-key",
                storageBucket = "",
            )
        assertFalse(incomplete.isComplete())

        val configured =
            incomplete.copy(storageBucket = "demo-planterior-release.firebasestorage.app")
        val app = FirebaseRuntime.initialize(context, configured)

        assertEquals(configured.storageBucket, app?.options?.storageBucket)
    }
}
