package gay.benjifox.foxyphotos

import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import gay.benjifox.foxyphotos.core.backup.*
import kotlinx.coroutines.*
import org.json.JSONObject

/** Device-private, independent of backup selections; malformed evidence fails closed. */
internal class CleanupStorage(private val context: Context) {
    val prefs = context.getSharedPreferences("foxyphotos.cleanup", Context.MODE_PRIVATE)
    fun enabled() = prefs.getBoolean("enabled", false)
    fun folders(): Set<String> = prefs.getStringSet("folders", emptySet())?.toSet() ?: emptySet()
    fun evidence(uri: Uri): CleanupUploadEvidence? { return try {
        val j = JSONObject(prefs.getString("receipt:$uri", null) ?: return null)
        CleanupUploadEvidence(j.getString("identity"), j.getString("folder"), j.getString("hash"),
            j.getLong("size"), j.getString("remote"), j.getString("run"), j.getLong("at"))
    } catch (_: Exception) { null } }
    fun endRun(run: String) { check(prefs.edit().putBoolean("ended:$run", true).commit()) }
    fun ended(run: String) = prefs.getBoolean("ended:$run", false)
    fun record(uri: Uri, e: CleanupUploadEvidence) {
        val j = JSONObject().put("identity", e.localIdentity).put("folder", e.folder).put("hash", e.sha256)
            .put("size", e.size).put("remote", e.remoteKey).put("run", e.uploadRun).put("at", e.uploadedAt)
        check(prefs.edit().putString("receipt:$uri", j.toString()).commit())
    }

    fun snapshot(uri: Uri): CleanupLocal {
        check(Build.VERSION.SDK_INT >= 30)
        require(uri.scheme == "content" && uri.authority == "media")
        val cols = arrayOf("_size", "date_modified", "relative_path", "generation_modified", "date_added", "is_pending", "is_trashed", "_display_name")
        return context.contentResolver.query(uri, cols, null, null, null)?.use { c ->
            check(c.moveToFirst() && c.getInt(5) == 0 && c.getInt(6) == 0)
            val size = c.getLong(0)
            val folder = c.getString(2) ?: error("Folder unavailable")
            val version = MediaStore.getVersion(context, MediaStore.getVolumeName(uri))
            CleanupLocal(uri, "$uri|$version|$size|${c.getLong(1)}|${c.getLong(3)}|${c.getLong(4)}|$folder", folder, size, c.getString(7), c.getLong(3))
        } ?: error("Media unavailable")
    }

    suspend fun hash(local: CleanupLocal): String = withContext(Dispatchers.IO) {
        // Never verify a privacy-redacted ContentResolver rendition as the device original.
        val original = MediaStore.setRequireOriginal(local.uri)
        context.contentResolver.openInputStream(original)?.use { VerifiedCleanup.hash(it, local.size) }
            ?: error("Original unavailable")
    }

    suspend fun recheck(uri: Uri, verification: CleanupVerification): Boolean = withContext(Dispatchers.IO) {
        val before = snapshot(uri)
        val hash = hash(before)
        val after = snapshot(uri)
        currentCoroutineContext().ensureActive()
        before == after && ended(verification.upload.uploadRun) && evidence(uri) == verification.upload &&
            VerifiedCleanup.admit(verification, enabled(), folders(), after.identity, hash, after.size, System.currentTimeMillis())
    }
}
internal data class CleanupLocal(val uri: Uri, val identity: String, val folder: String, val size: Long, val name: String, val generation: Long)
