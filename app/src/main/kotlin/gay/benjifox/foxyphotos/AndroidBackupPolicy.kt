package gay.benjifox.foxyphotos

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import gay.benjifox.foxyphotos.core.backup.*

/** Private storage contains scope and policy only, never credentials or media contents. */
internal class AndroidBackupPolicy(private val context: Context) {
    val prefs = context.getSharedPreferences(BackupService.SETTINGS, 0)
    private fun boot() = Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, 0)
    fun timerActive(key: String): Boolean {
        if (!prefs.contains("$key-created")) return false
        val active = PolicyTimer(prefs.getLong("$key-created", 0), prefs.getLong("$key-duration", 0),
            prefs.getLong("$key-elapsed", 0), prefs.getInt("$key-boot", 0))
            .active(System.currentTimeMillis(), SystemClock.elapsedRealtime(), boot())
        if (!active) clearTimer(key)
        return active
    }
    fun timer(key: String, duration: Long) {
        prefs.edit().putLong("$key-created", System.currentTimeMillis()).putLong("$key-duration", duration)
            .putLong("$key-elapsed", SystemClock.elapsedRealtime()).putInt("$key-boot", boot()).commit()
        BackupScheduling.refresh(context)
    }
    fun clearTimer(key: String) { prefs.edit().remove("$key-created").commit() }
    fun paused() = prefs.getBoolean("user-pause", false) || timerActive("pause")
    fun pause(duration: Long?) {
        prefs.edit().putBoolean("user-pause", duration == null).commit()
        clearTimer("pause")
        if (duration != null) timer("pause", duration)
    }
    fun resume() { prefs.edit().putBoolean("user-pause", false).commit(); clearTimer("pause") }
    fun request(): Set<String>? = if (prefs.contains("request-items")) prefs.getStringSet("request-items", emptySet())!!.toSet() else null
    fun allowedFolders(): Set<String>? = if (prefs.getString("request-origin", null) == "explicit") null
        else prefs.getStringSet("enabled-folders", emptySet())!!.toSet()
    fun allowsFolder(path: String): Boolean = allowedFolders()?.contains(path) ?: true
    fun storeRequest(ids: Set<String>, explicit: Boolean = false) {
        prefs.edit().putStringSet("request-items", ids.toSet()).putBoolean("request-pending", true)
            .putString("request-origin", if (explicit) "explicit" else "folders").commit()
    }
    fun completeItem(id: String, failed: Boolean = false) {
        request()?.let { prefs.edit().putStringSet("request-items", it - id).commit() }
        if (failed && prefs.getBoolean("override-batch", false)) endOverride()
    }
    fun requestItems(ids: Set<String>, explicit: Boolean = true): Boolean {
        if (BackupRuntime.state.value.active || prefs.getBoolean("request-pending", false)) return false
        storeRequest(ids, explicit)
        return true
    }
    suspend fun capturePendingBatch(): Set<String> {
        val scanner = gay.benjifox.foxyphotos.core.media.MediaStoreFolderScanner(context.contentResolver)
        val ledger = gay.benjifox.foxyphotos.core.database.SharedPreferencesUploadedContentLedger(context.applicationContext)
        val requested = request()
        val unavailable = mutableSetOf<String>()
        val media = if (requested != null) scanner.scanSelectedItems(requested, allowedFolders()) { unavailable += it }
            else scanner.scanSelectedFolders(prefs.getStringSet("enabled-folders", emptySet())!!.toSet())
        val pending = pendingItemIds(media, { it.uri.toString() }, { scanner.hash(it).sha256.toList() },
            { ledger.contains(it.toByteArray()) }) + unavailable
        val authorized = media.filter { allowsFolder(it.relativePath) }.map { it.uri.toString() }.toSet() + unavailable
        val scoped = pending.intersect(authorized)
        val remaining = request()
        return if (remaining != null) scoped.intersect(remaining) else if (requested == null) scoped else emptySet()
    }
    fun finish() {
        prefs.edit().remove("request-items").remove("request-origin").putBoolean("request-pending", false).remove("batch-override").commit()
        if (prefs.getBoolean("override-batch", false)) endOverride()
    }
    fun endOverride() { clearTimer("override"); prefs.edit().remove("override-covered").putBoolean("override-batch", false).commit() }
    fun override(covered: Set<BackupCondition>, duration: Long?, ids: Set<String>?) {
        prefs.edit().putStringSet("override-covered", covered.map { it.name }.toSet())
            .putBoolean("override-batch", duration == null).apply()
        if (duration != null) timer("override", duration)
        else { clearTimer("override"); prefs.edit().putStringSet("batch-override", ids ?: emptySet()).commit() }
    }
    fun rules() = BackupRules(prefs.getBoolean("allow-wifi", true), prefs.getBoolean("allow-cellular", false),
        prefs.getBoolean("allow-metered", false), prefs.getBoolean("allow-roaming", false),
        prefs.getBoolean("allow-data-saver", false), prefs.getBoolean("allow-power-saver", false), prefs.getBoolean("require-charging", false))
    fun rawBlockers(): Set<BackupCondition> {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val caps = cm.getNetworkCapabilities(cm.activeNetwork)
        val wifi = caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        // Non-Wi-Fi paths are conservatively treated as costly cellular.
        val cellular = caps != null && !wifi
        return BackupPolicy.blockers(rules(), BackupEnvironment(wifi, cellular,
            cm.isActiveNetworkMetered, caps != null && !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_ROAMING),
            cm.restrictBackgroundStatus == ConnectivityManager.RESTRICT_BACKGROUND_STATUS_ENABLED && cm.isActiveNetworkMetered,
            context.getSystemService(PowerManager::class.java).isPowerSaveMode,
            context.getSystemService(BatteryManager::class.java).isCharging))
    }
    fun reasons(item: String? = null, exceptions: Boolean = true): List<String> {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val caps = cm.getNetworkCapabilities(cm.activeNetwork)
        val covered = if (exceptions && (timerActive("override") ||
                (prefs.getBoolean("override-batch", false) && (item == null || item in (prefs.getStringSet("batch-override", emptySet()) ?: emptySet())))))
            prefs.getStringSet("override-covered", emptySet()) ?: emptySet() else emptySet()
        return buildList {
            if (paused()) add("Paused by you")
            if (!hasMediaAccess(context)) add("Photo access required")
            if (caps == null || !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) || !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) add("No internet connection")
            addAll(rawBlockers().filter { it.name !in covered }.map { it.label })
        }
    }
}
