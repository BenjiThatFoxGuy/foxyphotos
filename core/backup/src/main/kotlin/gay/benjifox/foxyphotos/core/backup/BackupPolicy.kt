package gay.benjifox.foxyphotos.core.backup

/** Persist both clocks. A backward wall clock expires, never extends, a grant. */
data class PolicyTimer(val createdUtc: Long, val durationMillis: Long, val createdElapsed: Long, val boot: Int = 0) {
    fun active(nowUtc: Long, elapsed: Long, currentBoot: Int = boot): Boolean =
        nowUtc >= createdUtc && nowUtc - createdUtc < durationMillis &&
            (currentBoot != boot || (elapsed >= createdElapsed && elapsed - createdElapsed < durationMillis))
}
enum class BackupCondition(val label: String) {
    WIFI("Wi-Fi disabled"), CELLULAR("Cellular disabled"), METERED_WIFI("Metered Wi-Fi"),
    ROAMING("Roaming"), DATA_SAVER("Data Saver"), POWER_SAVER("Power saver"), CHARGING("Not charging")
}
data class BackupRules(val wifi: Boolean = true, val cellular: Boolean = false,
    val meteredWifi: Boolean = false, val roaming: Boolean = false, val dataSaver: Boolean = false,
    val powerSaver: Boolean = false, val charging: Boolean = false)
data class BackupEnvironment(val wifi: Boolean = false, val cellular: Boolean = false,
    val metered: Boolean = false, val roaming: Boolean = false, val dataSaver: Boolean = false,
    val powerSaver: Boolean = false, val charging: Boolean = false)
object BackupPolicy {
    fun blockers(r: BackupRules, e: BackupEnvironment): Set<BackupCondition> = buildSet {
        if (e.wifi && !r.wifi) add(BackupCondition.WIFI)
        if (e.cellular && !r.cellular) add(BackupCondition.CELLULAR)
        if (e.wifi && e.metered && !r.meteredWifi) add(BackupCondition.METERED_WIFI)
        if (e.cellular && e.roaming && (!r.roaming || !r.cellular)) add(BackupCondition.ROAMING)
        if (e.dataSaver && !r.dataSaver) add(BackupCondition.DATA_SAVER)
        if (e.powerSaver && !r.powerSaver) add(BackupCondition.POWER_SAVER)
        if (r.charging && !e.charging) add(BackupCondition.CHARGING)
    }
}
class BackupBatch(ids: Set<String>) {
    val ids: Set<String> = ids.toSet()
    fun admits(id: String) = id in ids
}
