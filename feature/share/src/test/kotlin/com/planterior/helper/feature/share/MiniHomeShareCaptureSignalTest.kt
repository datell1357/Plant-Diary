package com.planterior.helper.feature.share

import com.planterior.helper.core.model.Revision
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 캡처 신호는 실제 draw 기록이 끝난 뒤에만 온다.
 *
 * 프레임 대기나 sleep 없이 정확한 신호만으로 동기화하는지 확인한다.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MiniHomeShareCaptureSignalTest {
    private val token =
        MiniHomeShareCaptureToken(MiniHomeShareFixtures.owner, Revision(7), generation = 1L)

    @Test
    fun `awaiting suspends until the matching draw record signal arrives`() = runTest {
        val signal = MiniHomeShareRecordSignal()
        var recorded: Boolean? = null

        val awaiting = launch { recorded = signal.awaitRecorded(token) }
        runCurrent()
        assertNull("must not resolve before the layer records", recorded)

        signal.markRecorded(token)
        awaiting.join()

        assertEquals(true, recorded)
    }

    @Test
    fun `a signal that already arrived resolves immediately`() = runTest {
        val signal = MiniHomeShareRecordSignal()
        signal.markRecorded(token)

        assertTrue(signal.awaitRecorded(token))
    }

    @Test
    fun `a signal for another generation never resolves the awaited token`() = runTest {
        val signal = MiniHomeShareRecordSignal()
        var recorded: Boolean? = null

        val awaiting = launch { recorded = signal.awaitRecorded(token) }
        runCurrent()

        signal.markRecorded(token.copy(generation = 2L))
        runCurrent()
        assertNull("a stale generation must not resolve the await", recorded)

        signal.markRecorded(token)
        awaiting.join()
        assertEquals(true, recorded)
    }

    @Test
    fun `a signal for another owner never resolves the awaited token`() = runTest {
        val signal = MiniHomeShareRecordSignal()
        var recorded: Boolean? = null

        val awaiting = launch { recorded = signal.awaitRecorded(token) }
        runCurrent()

        signal.markRecorded(token.copy(owner = MiniHomeShareFixtures.otherOwner))
        runCurrent()
        assertNull(recorded)

        awaiting.cancel()
    }

    @Test
    fun `invalidating drops a previously recorded generation`() = runTest {
        val signal = MiniHomeShareRecordSignal()
        signal.markRecorded(token)
        assertTrue(signal.isRecorded(token))

        signal.invalidate()

        assertFalse(signal.isRecorded(token))
    }

    @Test
    fun `re recording after invalidation resolves a fresh await`() = runTest {
        val signal = MiniHomeShareRecordSignal()
        signal.markRecorded(token)
        signal.invalidate()
        var recorded: Boolean? = null

        val awaiting = launch { recorded = signal.awaitRecorded(token) }
        runCurrent()
        assertNull(recorded)

        signal.markRecorded(token)
        awaiting.join()
        assertEquals(true, recorded)
    }
}
