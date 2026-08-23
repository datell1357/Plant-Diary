package com.planterior.helper.feature.share

import android.app.Activity
import android.content.Intent
import android.net.Uri
import androidx.core.net.toUri
import com.planterior.helper.core.model.AccountId
import com.planterior.helper.core.model.Revision
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 이미지 내보내기는 매 중단점 이후 소유자와 revision을 다시 확인한다.
 *
 * 낡아진 캡처는 파일을 지우고 어떤 시트도 열지 않는다.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
@OptIn(ExperimentalCoroutinesApi::class)
class MiniHomeShareExportTest {
    private val token =
        MiniHomeShareCaptureToken(MiniHomeShareFixtures.owner, Revision(7), generation = 1L)
    private val uri: Uri =
        "content://com.planterior.helper.fileprovider/share/mini-home-r7.png".toUri()

    @Test
    fun `a current export writes once and launches the chooser`() = runTest {
        val sink = FakeSink(uri)
        val launcher = FakeLauncher(hasTarget = true)
        val exporter = exporter(sink, launcher)

        val outcome = exporter.export(token)

        assertEquals(MiniHomeShareExportOutcome.Launched, outcome)
        assertEquals(listOf(Revision(7)), sink.written)
        assertEquals(listOf(uri), launcher.launched)
        assertEquals(0, sink.clearCount)
    }

    @Test
    fun `an export never starts when the record signal is for another generation`() = runTest {
        val sink = FakeSink(uri)
        val launcher = FakeLauncher(hasTarget = true)
        val exporter = exporter(sink, launcher, recordedToken = token.copy(generation = 2L))

        val outcome = exporter.export(token)

        assertEquals(MiniHomeShareExportOutcome.Stale, outcome)
        assertTrue(sink.written.isEmpty())
        assertTrue(launcher.launched.isEmpty())
    }

    @Test
    fun `an account switch during encoding clears the file and never launches`() = runTest {
        val encodeGate = CompletableDeferred<ByteArray>()
        val sink = FakeSink(uri)
        val launcher = FakeLauncher(hasTarget = true)
        var current = token
        val exporter =
            exporter(sink, launcher, encodeGate = encodeGate, isCurrent = { it == current })

        var outcome: MiniHomeShareExportOutcome? = null
        val job = launch { outcome = exporter.export(token) }
        runCurrent()

        // 인코딩 도중 계정이 바뀐다.
        current = token.copy(owner = MiniHomeShareFixtures.otherOwner, generation = 2L)
        encodeGate.complete(byteArrayOf(1, 2, 3))
        job.join()

        assertEquals(MiniHomeShareExportOutcome.Stale, outcome)
        assertTrue(launcher.launched.isEmpty())
        assertTrue(sink.written.isEmpty())
        assertTrue("stale export must clear the cache", sink.clearCount > 0)
    }

    @Test
    fun `a revision change after the file is written clears it and never launches`() = runTest {
        val sink = FakeSink(uri)
        val launcher = FakeLauncher(hasTarget = true)
        var current = token
        val exporter =
            exporter(
                sink,
                launcher,
                isCurrent = { it == current },
                onWritten = { current = token.copy(revision = Revision(9), generation = 3L) },
            )

        val outcome = exporter.export(token)

        assertEquals(MiniHomeShareExportOutcome.Stale, outcome)
        assertTrue("written file must be cleared", sink.clearCount > 0)
        assertTrue(launcher.launched.isEmpty())
    }

    @Test
    fun `a route dispose between the target check and the launch cancels the launch`() = runTest {
        val sink = FakeSink(uri)
        val launcher = FakeLauncher(hasTarget = true)
        var current: MiniHomeShareCaptureToken? = token
        val exporter =
            exporter(
                sink,
                launcher,
                isCurrent = { it == current },
                onTargetChecked = { current = null },
            )

        val outcome = exporter.export(token)

        assertEquals(MiniHomeShareExportOutcome.Stale, outcome)
        assertTrue(launcher.launched.isEmpty())
        assertTrue(sink.clearCount > 0)
    }

    @Test
    fun `no share target is reported without writing a lingering file`() = runTest {
        val sink = FakeSink(uri)
        val launcher = FakeLauncher(hasTarget = false)
        val exporter = exporter(sink, launcher)

        val outcome = exporter.export(token)

        assertEquals(MiniHomeShareExportOutcome.NoTarget, outcome)
        assertTrue(launcher.launched.isEmpty())
        assertTrue("no-target export must not leave a granted file", sink.clearCount > 0)
    }

    @Test
    fun `an encoding failure reports a failure and leaves no file behind`() = runTest {
        val sink = FakeSink(uri)
        val launcher = FakeLauncher(hasTarget = true)
        val exporter = exporter(sink, launcher, encodeFailure = IllegalStateException("no layer"))

        val outcome = exporter.export(token)

        assertEquals(MiniHomeShareExportOutcome.Failed, outcome)
        assertTrue(launcher.launched.isEmpty())
        assertTrue(sink.clearCount > 0)
    }

    // 6) ActivityResult 결과 코드 매핑

    @Test
    fun `a cancelled activity result is neutral and never claims delivery`() {
        assertEquals(
            MiniHomeShareSheetOutcome.Cancelled,
            miniHomeShareSheetOutcome(Activity.RESULT_CANCELED),
        )
        assertFalse(MiniHomeShareFeedback.SHEET_CANCELLED.error)
    }

    @Test
    fun `every other activity result only reports that the sheet opened`() {
        listOf(
                Activity.RESULT_OK,
                Activity.RESULT_FIRST_USER,
                Activity.RESULT_FIRST_USER + 7,
                42,
                -7,
                Int.MAX_VALUE,
                Int.MIN_VALUE,
            )
            .forEach { code ->
                assertEquals(
                    "result $code must only report that the sheet opened",
                    MiniHomeShareSheetOutcome.Opened,
                    miniHomeShareSheetOutcome(code),
                )
            }
    }

    private fun exporter(
        sink: FakeSink,
        launcher: FakeLauncher,
        recordedToken: MiniHomeShareCaptureToken = token,
        encodeGate: CompletableDeferred<ByteArray>? = null,
        encodeFailure: Throwable? = null,
        isCurrent: (MiniHomeShareCaptureToken) -> Boolean = { it == token },
        onWritten: () -> Unit = {},
        onTargetChecked: () -> Unit = {},
    ) =
        MiniHomeShareImageExporter(
            sink = sink,
            recorder =
                object : MiniHomeShareCaptureRecorder {
                    override suspend fun awaitRecorded(token: MiniHomeShareCaptureToken): Boolean =
                        recordedToken == token

                    override suspend fun encode(token: MiniHomeShareCaptureToken): ByteArray {
                        encodeFailure?.let { throw it }
                        return encodeGate?.await() ?: byteArrayOf(1, 2, 3)
                    }
                },
            isCurrent = isCurrent,
            hasTarget = {
                val result = launcher.hasTarget
                onTargetChecked()
                result
            },
            launch = launcher::launch,
            onWritten = onWritten,
        )

    private class FakeSink(private val uri: Uri) : MiniHomeShareImageSink {
        val written = mutableListOf<Revision>()
        var clearCount = 0

        override fun write(owner: AccountId, revision: Revision, bytes: ByteArray): Uri {
            written += revision
            return uri
        }

        override fun clear() {
            clearCount += 1
            written.clear()
        }
    }

    private class FakeLauncher(val hasTarget: Boolean) {
        val launched = mutableListOf<Uri>()

        fun launch(intent: Intent) {
            launched +=
                requireNotNull(intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java))
        }
    }
}
