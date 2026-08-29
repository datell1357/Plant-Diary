package com.planterior.helper.diagnostic

import java.io.File
import java.security.MessageDigest

internal data class Todo18ExpectedProvenance(
    val sourceSha256: String?,
    val appApkSha256: String?,
    val androidTestApkSha256: String?,
)

internal data class Todo18DiagnosticProvenance(
    val expectedSourceSha256: String?,
    val embeddedSourceSha256: String?,
    val expectedAppApkSha256: String?,
    val observedAppApkSha256: String?,
    val expectedAndroidTestApkSha256: String?,
    val observedAndroidTestApkSha256: String?,
) {
    val bindingValidated: Boolean
        get() =
            expectedSourceSha256.isSha256() &&
                embeddedSourceSha256.isSha256() &&
                expectedAppApkSha256.isSha256() &&
                observedAppApkSha256.isSha256() &&
                expectedAndroidTestApkSha256.isSha256() &&
                observedAndroidTestApkSha256.isSha256() &&
                expectedSourceSha256 == embeddedSourceSha256 &&
                expectedAppApkSha256 == observedAppApkSha256 &&
                expectedAndroidTestApkSha256 == observedAndroidTestApkSha256
}

internal object Todo18DiagnosticProvenanceBinding {
    fun captureIfEnabled(
        enabled: Boolean,
        expected: Todo18ExpectedProvenance,
        embeddedSourceSha256: String?,
        appApk: File,
        androidTestApk: File,
        hashFile: (File) -> String = ::sha256,
    ): Todo18DiagnosticProvenance? {
        if (!enabled) return null
        return try {
            Todo18DiagnosticProvenance(
                expectedSourceSha256 = expected.sourceSha256.normalizedHash(),
                embeddedSourceSha256 = embeddedSourceSha256.normalizedHash(),
                expectedAppApkSha256 = expected.appApkSha256.normalizedHash(),
                observedAppApkSha256 = hashFile(appApk),
                expectedAndroidTestApkSha256 = expected.androidTestApkSha256.normalizedHash(),
                observedAndroidTestApkSha256 = hashFile(androidTestApk),
            )
        } catch (_: AssertionError) {
            failed(expected, embeddedSourceSha256)
        } catch (_: Exception) {
            failed(expected, embeddedSourceSha256)
        }
    }

    private fun failed(
        expected: Todo18ExpectedProvenance,
        embeddedSourceSha256: String?,
    ) =
        Todo18DiagnosticProvenance(
            expectedSourceSha256 = expected.sourceSha256.normalizedHash(),
            embeddedSourceSha256 = embeddedSourceSha256.normalizedHash(),
            expectedAppApkSha256 = expected.appApkSha256.normalizedHash(),
            observedAppApkSha256 = null,
            expectedAndroidTestApkSha256 = expected.androidTestApkSha256.normalizedHash(),
            observedAndroidTestApkSha256 = null,
        )

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}

private val SHA_256 = Regex("^[0-9a-f]{64}$")

private fun String?.normalizedHash(): String? = this?.lowercase()

private fun String?.isSha256(): Boolean = this != null && SHA_256.matches(this)
