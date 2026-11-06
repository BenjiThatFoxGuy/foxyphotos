package gay.benjifox.foxyphotos.core.backup

import org.junit.Assert.*
import org.junit.Test

class BackupPolicyTest {
    @Test fun pauseSurvivesBlockerChurnAndOverrideExpiry() {
        val pause = PolicyTimer(1000, 900000, 500)
        val exception = PolicyTimer(1000, 100, 500)
        assertTrue(pause.active(1200, 700))
        assertFalse(exception.active(1200, 700))
        assertEquals(setOf(BackupCondition.POWER_SAVER), BackupPolicy.blockers(
            BackupRules(), BackupEnvironment(wifi = true, powerSaver = true)))
        assertTrue(pause.active(1200, 700))
        assertFalse(pause.active(999, 701))
        assertFalse(pause.active(901000, 900500))
    }
    @Test fun cellularAndRoamingRequireIndependentPermission() {
        val state = BackupEnvironment(cellular = true, roaming = true, metered = true, dataSaver = true)
        assertEquals(setOf(BackupCondition.CELLULAR, BackupCondition.ROAMING, BackupCondition.DATA_SAVER),
            BackupPolicy.blockers(BackupRules(), state))
    }
    @Test fun batchScopeIsAnImmutableWhitelist() {
        val captured = BackupBatch(setOf("one", "two"))
        assertTrue(captured.admits("one"))
        assertFalse(captured.admits("neighbor"))
        assertFalse(captured.admits("new-arrival"))
    }
}
