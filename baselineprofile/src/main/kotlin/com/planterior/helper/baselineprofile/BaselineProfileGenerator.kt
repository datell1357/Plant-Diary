package com.planterior.helper.baselineprofile

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {
    @get:Rule val baselineProfileRule = BaselineProfileRule()

    @Test
    fun criticalColdStartAuthHomeJourney() =
        baselineProfileRule.collect(
            packageName = PACKAGE_NAME,
            includeInStartupProfile = true,
        ) {
            startActivityAndWait()

            assertTrue(
                "Authentication screen did not become ready after cold start",
                device.wait(Until.hasObject(By.text("Google로 계속하기")), TIMEOUT_MILLIS),
            )
            device.pressBack()

            val signIn = device.wait(Until.findObject(By.text("로그인하고 시작하기")), TIMEOUT_MILLIS)
            assertTrue("Signed-out home did not become ready", signIn != null)
            signIn.click()
            assertTrue(
                "Authentication screen did not resume from home",
                device.wait(Until.hasObject(By.text("Google로 계속하기")), TIMEOUT_MILLIS),
            )
            device.pressBack()
            assertTrue(
                "Home did not resume after authentication journey",
                device.wait(Until.hasObject(By.text("로그인하고 시작하기")), TIMEOUT_MILLIS),
            )
        }

    private companion object {
        const val PACKAGE_NAME = "com.planterior.helper"
        const val TIMEOUT_MILLIS = 10_000L
    }
}
