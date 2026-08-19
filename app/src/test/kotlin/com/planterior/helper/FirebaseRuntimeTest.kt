package com.planterior.helper

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.google.firebase.FirebaseApp
import org.junit.After
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class FirebaseRuntimeTest {
    @After
    fun tearDown() {
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
                FirebaseRuntime.Configuration("", "", ""),
            )
        )
    }
}
