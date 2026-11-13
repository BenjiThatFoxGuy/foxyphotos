package gay.benjifox.foxyphotos.core.backup

import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSUserDefaults
import platform.Network.nw_path_get_status
import platform.Network.nw_path_monitor_create
import platform.Network.nw_path_monitor_set_update_handler
import platform.Network.nw_path_monitor_start
import platform.Network.nw_path_monitor_cancel
import platform.Network.nw_path_status_satisfied
import platform.Network.nw_path_uses_interface_type
import platform.Network.nw_interface_type_wifi
import platform.Network.nw_interface_type_cellular
import platform.Network.nw_path_is_constrained
import platform.Network.nw_path_is_expensive
import platform.UIKit.UIDevice
import platform.Foundation.NSProcessInfo

/**
 * iOS backup policy manager.
 *
 * Mirrors AndroidBackupPolicy: stores backup rules and state in UserDefaults,
 * checks network conditions via NWPathMonitor, and checks battery/power state
 * via UIDevice and NSProcessInfo.
 */
class IosBackupPolicy {

    val prefs = NSUserDefaults(suiteName = PREFS_SUITE)

    // --- Timer management ---

    fun timerActive(key: String): Boolean {
        val created = prefs.doubleForKey("$key-created")
        if (created == 0.0) return false
        val duration = prefs.doubleForKey("$key-duration")
        val now = platform.Foundation.NSDate().timeIntervalSince1970 * 1000.0
        val active = now >= created && (now - created) < duration
        if (!active) clearTimer(key)
        return active
    }

    fun timer(key: String, durationMillis: Long) {
        val now = platform.Foundation.NSDate().timeIntervalSince1970 * 1000.0
        prefs.setDouble(now, forKey = "$key-created")
        prefs.setDouble(durationMillis.toDouble(), forKey = "$key-duration")
        prefs.synchronize()
    }

    fun clearTimer(key: String) {
        prefs.removeObjectForKey("$key-created")
        prefs.removeObjectForKey("$key-duration")
        prefs.synchronize()
    }

    // --- Pause / Resume ---

    fun paused(): Boolean =
        prefs.boolForKey("user-pause") || timerActive("pause")

    fun pause(durationMillis: Long?) {
        prefs.setBool(durationMillis == null, forKey = "user-pause")
        clearTimer("pause")
        if (durationMillis != null) timer("pause", durationMillis)
        prefs.synchronize()
    }

    fun resume() {
        prefs.setBool(false, forKey = "user-pause")
        clearTimer("pause")
        prefs.synchronize()
    }

    // --- Request tracking ---

    fun requestPending(): Boolean = prefs.boolForKey("request-pending")

    fun storeRequest(ids: Set<String>, explicit: Boolean = false) {
        prefs.setObject(ids.toList(), forKey = "request-items")
        prefs.setBool(true, forKey = "request-pending")
        prefs.setObject(if (explicit) "explicit" else "folders", forKey = "request-origin")
        prefs.synchronize()
    }

    fun finish() {
        prefs.removeObjectForKey("request-items")
        prefs.removeObjectForKey("request-origin")
        prefs.setBool(false, forKey = "request-pending")
        if (prefs.boolForKey("override-batch")) endOverride()
        prefs.synchronize()
    }

    // --- Override management ---

    fun override(covered: Set<BackupCondition>, durationMillis: Long?) {
        prefs.setObject(covered.map { it.name }, forKey = "override-covered")
        prefs.setBool(durationMillis == null, forKey = "override-batch")
        if (durationMillis != null) timer("override", durationMillis)
        else clearTimer("override")
        prefs.synchronize()
    }

    fun endOverride() {
        clearTimer("override")
        prefs.removeObjectForKey("override-covered")
        prefs.setBool(false, forKey = "override-batch")
        prefs.synchronize()
    }

    // --- Backup rules & conditions ---

    fun rules(): BackupRules = BackupRules(
        wifi = prefs.boolWithDefault("allow-wifi", true),
        cellular = prefs.boolWithDefault("allow-cellular", false),
        meteredWifi = prefs.boolWithDefault("allow-metered", false),
        roaming = prefs.boolWithDefault("allow-roaming", false),
        dataSaver = prefs.boolWithDefault("allow-data-saver", false),
        powerSaver = prefs.boolWithDefault("allow-power-saver", false),
        charging = prefs.boolWithDefault("require-charging", false),
    )

    /**
     * Check current network and power conditions against backup rules.
     * Returns human-readable reason strings for any blockers.
     */
    fun reasons(): List<String> = buildList {
        if (paused()) add("Paused by you")

        val env = currentEnvironment()
        val covered = if (timerActive("override") || prefs.boolForKey("override-batch")) {
            @Suppress("UNCHECKED_CAST")
            (prefs.objectForKey("override-covered") as? List<String>)?.toSet() ?: emptySet()
        } else {
            emptySet()
        }

        val blockers = BackupPolicy.blockers(rules(), env)
        addAll(blockers.filter { it.name !in covered }.map { it.label })
    }

    /**
     * Snapshot the current network and power environment.
     */
    fun currentEnvironment(): BackupEnvironment {
        val pathInfo = NetworkSnapshot.current()
        val isLowPower = NSProcessInfo.processInfo.lowPowerModeEnabled
        UIDevice.currentDevice.batteryMonitoringEnabled = true
        val batteryState = UIDevice.currentDevice.batteryState
        val isCharging = batteryState.value.toInt() == 2L.toInt() || // UIDeviceBatteryStateCharging
            batteryState.value.toInt() == 3L.toInt() // UIDeviceBatteryStateFull

        return BackupEnvironment(
            wifi = pathInfo.wifi,
            cellular = pathInfo.cellular,
            metered = pathInfo.expensive,
            roaming = false, // iOS doesn't directly expose roaming; treat as non-roaming
            dataSaver = pathInfo.constrained,
            powerSaver = isLowPower,
            charging = isCharging,
        )
    }

    private fun NSUserDefaults.boolWithDefault(key: String, default: Boolean): Boolean {
        return if (objectForKey(key) != null) boolForKey(key) else default
    }

    private companion object {
        const val PREFS_SUITE = "foxyphotos.backup-settings"
    }
}

/**
 * Synchronous network state snapshot using NWPathMonitor.
 *
 * NWPathMonitor is callback-based but we need a one-shot read. This captures
 * the initial path synchronously on the calling thread.
 */
@OptIn(ExperimentalForeignApi::class)
internal object NetworkSnapshot {
    data class Info(
        val wifi: Boolean,
        val cellular: Boolean,
        val expensive: Boolean,
        val constrained: Boolean,
        val satisfied: Boolean,
    )

    fun current(): Info {
        // NWPathMonitor fires the handler immediately with current state.
        var result = Info(wifi = false, cellular = false, expensive = false, constrained = false, satisfied = false)
        val monitor = nw_path_monitor_create()
        val queue = platform.darwin.dispatch_get_global_queue(
            platform.darwin.DISPATCH_QUEUE_PRIORITY_DEFAULT.toLong(), 0u
        )

        var received = false
        nw_path_monitor_set_update_handler(monitor) { path ->
            if (!received) {
                received = true
                result = Info(
                    wifi = nw_path_uses_interface_type(path, nw_interface_type_wifi),
                    cellular = nw_path_uses_interface_type(path, nw_interface_type_cellular),
                    expensive = nw_path_is_expensive(path),
                    constrained = nw_path_is_constrained(path),
                    satisfied = nw_path_get_status(path) == nw_path_status_satisfied,
                )
            }
        }
        nw_path_monitor_set_queue(monitor, queue)
        nw_path_monitor_start(monitor)

        // Brief spin to allow the initial callback.
        val deadline = platform.Foundation.NSDate().timeIntervalSince1970 + 0.5
        while (!received && platform.Foundation.NSDate().timeIntervalSince1970 < deadline) {
            platform.Foundation.NSRunLoop.currentRunLoop.runUntilDate(
                platform.Foundation.NSDate(timeIntervalSinceNow = 0.01)
            )
        }

        nw_path_monitor_cancel(monitor)
        return result
    }
}
