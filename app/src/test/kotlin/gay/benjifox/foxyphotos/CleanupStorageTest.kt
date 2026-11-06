package gay.benjifox.foxyphotos

import android.content.Context
import android.net.Uri
import gay.benjifox.foxyphotos.core.backup.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class CleanupStorageTest {
    @Test fun privateReceiptSurvivesNewStorageInstanceButOldLedgerCannotGrantAuthority() {
        val app = RuntimeEnvironment.getApplication()
        val storage = CleanupStorage(app)
        storage.prefs.edit().clear().commit()
        val uri = Uri.parse("content://media/external/images/media/123")
        assertNull(storage.evidence(uri))
        app.getSharedPreferences("foxyphotos.upload-ledger", Context.MODE_PRIVATE).edit().putString("uploaded.fixture", "remote").commit()
        assertNull(storage.evidence(uri))
        val receipt = CleanupUploadEvidence("fixture-identity", "DCIM/", "a".repeat(64), 12, "fixture-key", "earlier-run", 1000)
        storage.record(uri, receipt)
        assertEquals(receipt, CleanupStorage(app).evidence(uri))
        assertFalse(storage.enabled())
        assertTrue(storage.folders().isEmpty())
        storage.prefs.edit().putString("receipt:$uri", "{broken").commit()
        assertNull(CleanupStorage(app).evidence(uri))
    }
}
