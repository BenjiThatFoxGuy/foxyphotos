package gay.benjifox.foxyphotos.core.backup

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Test

class BackupSessionTest {
    @Test fun `cancel retains run ownership until delayed cleanup finishes`() = runBlocking {
        val state = MutableStateFlow(BackupSnapshot())
        val entered = CompletableDeferred<Unit>()
        val cleanup = CompletableDeferred<Unit>()
        val session = BackupSession(this, state, { _, _ ->
            entered.complete(Unit)
            try { awaitCancellation() } finally { withContext(NonCancellable) { cleanup.await() } }
        })
        session.start("first")
        entered.await()
        session.cancel("first")
        yield()
        val cleaningWasActive = state.value.active
        session.start("second")
        val admittedRun = state.value.runId
        cleanup.complete(Unit)
        session.await()
        assertTrue(cleaningWasActive)
        assertEquals("first", admittedRun)
        assertEquals(BackupPhase.CANCELLED, state.value.phase)
        session.start("second")
        assertEquals("second", state.value.runId)
        session.close()
        session.await()
    }

    @Test fun `late completion from cancelled IO cannot turn cancel into success`() = runBlocking {
        val state = MutableStateFlow(BackupSnapshot())
        val entered = CompletableDeferred<Unit>()
        val session = BackupSession(this, state, { progress, _ ->
            entered.complete(Unit)
            try { awaitCancellation() } catch (_: CancellationException) { }
            progress(BackupSnapshot(phase = BackupPhase.UPLOADING))
            BackupRunResult(BackupCounts(uploaded = 99))
        })
        session.start("run")
        entered.await()
        session.cancel("run")
        session.await()
        assertEquals(BackupPhase.CANCELLED, state.value.phase)
        assertEquals(0, state.value.counts.uploaded)
    }

    @Test fun `destroy before coroutine dispatch terminates visible state`() = runBlocking {
        val state = MutableStateFlow(BackupSnapshot())
        var worked = false
        val session = BackupSession(this, state, { _, _ ->
            worked = true
            BackupRunResult(BackupCounts())
        })
        session.start("run")
        session.close()
        session.await()
        assertFalse(worked)
        assertEquals(BackupPhase.CANCELLED, state.value.phase)
    }

    @Test fun `cancel and timeout stop suspended IO without reporting success`() = runBlocking {
        for (timeout in listOf(false, true)) {
            val state = MutableStateFlow(BackupSnapshot())
            val entered = CompletableDeferred<Unit>()
            var cleaned = false
            val session = BackupSession(this, state, { _, _ ->
                try { entered.complete(Unit); awaitCancellation() }
                finally { cleaned = true }
            }, { throw IllegalStateException("broken notification builder") })
            session.start("run")
            entered.await()
            session.cancel("old")
            assertTrue(state.value.active)
            session.cancel("run", timedOut = timeout)
            session.await()
            assertTrue(cleaned)
            assertEquals(if (timeout) BackupPhase.TIMED_OUT else BackupPhase.CANCELLED, state.value.phase)
        }
    }

    @Test fun `one service session owns work and pause finishes the current item`() = runBlocking {
        val state = MutableStateFlow(BackupSnapshot())
        val entered = CompletableDeferred<Unit>()
        val finishItem = CompletableDeferred<Unit>()
        var runs = 0
        val session = BackupSession(this, state, { _, shouldPause ->
            runs++
            entered.complete(Unit)
            finishItem.await()
            BackupRunResult(BackupCounts(uploaded = 1), shouldPause())
        })
        session.start("run")
        entered.await()
        session.start("duplicate")
        session.pause("stale")
        assertEquals(BackupPhase.SCANNING, state.value.phase)
        session.pause("run")
        assertEquals(BackupPhase.PAUSING, state.value.phase)
        finishItem.complete(Unit)
        session.await()
        assertEquals(1, runs)
        assertEquals(BackupPhase.PAUSED, state.value.phase)
        assertEquals(1, state.value.counts.uploaded)
        assertFalse(state.value.active)
    }
}
