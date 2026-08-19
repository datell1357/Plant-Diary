package com.planterior.helper

import androidx.test.core.app.ApplicationProvider
import com.planterior.helper.home.setDebugHomeSession
import org.junit.rules.ExternalResource

class DebugHomeSessionRule(
    private val value: String,
    private val accountUid: String = "",
) : ExternalResource() {
    override fun before() {
        setDebugHomeSession(
            ApplicationProvider.getApplicationContext(),
            value,
            accountUid = accountUid,
        )
    }

    override fun after() {
        setDebugHomeSession(ApplicationProvider.getApplicationContext(), "")
    }
}
