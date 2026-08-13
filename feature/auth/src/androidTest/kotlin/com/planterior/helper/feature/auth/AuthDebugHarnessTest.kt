package com.planterior.helper.feature.auth

import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AuthDebugHarnessTest {
    @Test
    fun debugProviderEntryPointIsAvailableOnlyInDebugArtifact() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val intent =
            Intent(context, DebugAuthHarnessActivity::class.java).apply {
                putExtra(
                    DebugAuthHarnessActivity.EXTRA_SCENARIO,
                    DebugAuthHarnessActivity.GOOGLE_SUCCESS,
                )
            }
        ActivityScenario.launch<DebugAuthHarnessActivity>(intent).use { scenario ->
            scenario.onActivity { activity ->
                check(!activity.isFinishing)
                check(
                    activity.intent.getStringExtra(DebugAuthHarnessActivity.EXTRA_SCENARIO) ==
                        DebugAuthHarnessActivity.GOOGLE_SUCCESS
                )
            }
        }
    }
}
