package com.planterior.helper

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.rules.ExternalResource

/** MainActivity 시작 전에 API 37 local-network 권한을 확정해 permission Activity 개입을 막는다. */
class Api37LocalNetworkPermissionRule : ExternalResource() {
    override fun before() {
        if (android.os.Build.VERSION.SDK_INT < 37) return
        val context = ApplicationProvider.getApplicationContext<Context>()
        InstrumentationRegistry.getInstrumentation()
            .uiAutomation
            .grantRuntimePermission(
                context.packageName,
                "android.permission.ACCESS_LOCAL_NETWORK",
            )
    }
}
