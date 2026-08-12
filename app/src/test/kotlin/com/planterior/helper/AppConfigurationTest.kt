package com.planterior.helper

import org.junit.Assert.assertEquals
import org.junit.Test

class AppConfigurationTest {
    @Test
    fun `application identity and minimum sdk remain stable`() {
        assertEquals("com.planterior.helper", BuildConfig.APPLICATION_ID)
        assertEquals(29, BuildConfig.MIN_SUPPORTED_SDK)
    }

    @Test
    fun `default locale is Korean`() {
        assertEquals("ko", BuildConfig.DEFAULT_LOCALE)
    }
}
