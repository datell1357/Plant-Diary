package com.planterior.helper.diagnostic

import com.planterior.helper.diagnostic.Todo18DiagnosticReceiptFixtures.HASH_A
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Todo18DiagnosticProvenanceBindingTest {
    @Test
    fun `debug APK file hashing is deterministic and independently equality bound`() {
        val directory = Files.createTempDirectory("todo18-provenance").toFile()
        val app = File(directory, "app.apk").apply { writeText("abc") }
        val test = File(directory, "test.apk").apply { writeText("abc") }
        val expected = Todo18ExpectedProvenance(HASH_A, ABC_SHA256, ABC_SHA256)

        val first =
            checkNotNull(
                Todo18DiagnosticProvenanceBinding.captureIfEnabled(
                    enabled = true,
                    expected = expected,
                    embeddedSourceSha256 = HASH_A,
                    appApk = app,
                    androidTestApk = test,
                )
            )
        val second =
            checkNotNull(
                Todo18DiagnosticProvenanceBinding.captureIfEnabled(
                    enabled = true,
                    expected = expected,
                    embeddedSourceSha256 = HASH_A,
                    appApk = app,
                    androidTestApk = test,
                )
            )

        assertEquals(first, second)
        assertEquals(ABC_SHA256, first.observedAppApkSha256)
        assertEquals(ABC_SHA256, first.observedAndroidTestApkSha256)
        assertTrue(first.bindingValidated)
    }

    @Test
    fun `wrong or omitted independently expected value cannot validate`() {
        val baseline = provenance()
        val invalid =
            listOf(
                baseline.copy(expectedSourceSha256 = HASH_B),
                baseline.copy(expectedAppApkSha256 = HASH_B),
                baseline.copy(expectedAndroidTestApkSha256 = HASH_B),
                baseline.copy(expectedSourceSha256 = null),
                baseline.copy(expectedAppApkSha256 = null),
                baseline.copy(expectedAndroidTestApkSha256 = null),
            )

        invalid.forEach { assertFalse(it.bindingValidated) }
    }

    @Test
    fun `normal debug without Todo18 override does not hash package files`() {
        var hashes = 0
        val actual =
            Todo18DiagnosticProvenanceBinding.captureIfEnabled(
                enabled = false,
                expected = Todo18ExpectedProvenance(HASH_A, HASH_A, HASH_A),
                embeddedSourceSha256 = HASH_A,
                appApk = File("missing-app.apk"),
                androidTestApk = File("missing-test.apk"),
                hashFile = {
                    hashes += 1
                    error("disabled capture must not hash")
                },
            )

        assertNull(actual)
        assertEquals(0, hashes)
    }

    private fun provenance() =
        Todo18DiagnosticProvenance(
            expectedSourceSha256 = HASH_A,
            embeddedSourceSha256 = HASH_A,
            expectedAppApkSha256 = HASH_A,
            observedAppApkSha256 = HASH_A,
            expectedAndroidTestApkSha256 = HASH_A,
            observedAndroidTestApkSha256 = HASH_A,
        )

    private companion object {
        const val HASH_B = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
        const val ABC_SHA256 = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
    }
}
