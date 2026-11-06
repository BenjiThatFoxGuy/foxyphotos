package gay.benjifox.foxyphotos.core.model

/** Closed vocabulary: no caller-supplied messages, identifiers or network payloads. */
enum class DiagnosticStep(val title: String) {
    AUTO_CHECK("Automatic update check requested"), MANUAL_CHECK("Manual update check requested"),
    THROTTLED("Check skipped: checked within 24 hours"), DISABLED("Check skipped: automatic checks disabled"),
    READY_ARTIFACT("Check skipped: downloaded update pending"), BUSY("Request skipped: updater busy"),
    CONTACT_GITHUB("Contacting GitHub"), HTTP_RESPONSE("Network response"), RELEASE_FOUND("Published release found"),
    LOCATE_MANIFEST("Locating release manifest"), MANIFEST_MISSING("Release manifest missing or ambiguous"),
    MANIFEST_PARSED("Release metadata parsed and validated"), MANIFEST_REJECTED("Release metadata rejected"),
    LOCATE_APK("Locating advertised APK asset"), APK_FOUND("Advertised APK asset found"), APK_MISSING("APK asset missing or inconsistent"),
    COMPARE_VERSION("Comparing installed and available versions"), UPDATE_AVAILABLE("Update available"), NO_UPDATE("No newer update"),
    INCOMPATIBLE("Release package or SDK incompatible"), DOWNLOAD_START("Download started"), DOWNLOAD_PROGRESS("Download progress"),
    DOWNLOAD_COMPLETE("Download complete"), APK_SAVED("APK saved"), INSTALLER_CALL("Calling system installer"), CANCEL_REQUEST("Cancellation requested"), CANCELLED("Operation cancelled"), FAILED("Operation failed"),
    SIZE_CHECK("Downloaded size matches"), HASH_CHECK("Downloaded digest matches"), PACKAGE_CHECK("APK package matches"),
    VERSION_CHECK("APK version matches"), SDK_CHECK("APK SDK compatible"), SIGNATURE_CHECK("APK signing identity matches"),
    RESTORED("Restored pending APK reverified"), AUTH_REQUIRED("Installer authorization required"),
    AUTH_GRANTED("Installer authorization granted"), AUTH_DENIED("Installer authorization not granted"),
    INSTALLER_LAUNCHED("System installer opened; installation unconfirmed"),
    SCAN("Backup scan started"), PLAN("Backup plan ready"), HASH("Backup hashing started"),
    ADMISSION("Backup upload admission"), USER_PAUSE("Backup paused by user"), CONDITION_WAIT("Backup waiting for conditions"),
    COMPLETE("Backup pass complete"), SCHEDULE_REGISTER("Scheduler registration"), SCHEDULE_START("Scheduled backup started"),
    SCHEDULE_STOP("Scheduled backup stopped"), SCHEDULE_SKIP("Scheduled backup skipped")
}
enum class DiagnosticComponent { UPDATER, BACKUP, SCHEDULER }
enum class DiagnosticReason { NONE, NETWORK, HTTP, METADATA, SIZE, HASH, PACKAGE, VERSION, SDK, SIGNATURE, STORAGE, INSTALLER, AUTHORIZATION, OTHER, OWNER_BUSY, SERVICE_OWNER_BUSY, JOB_OWNER_BUSY, POLICY, NO_REQUEST, SYSTEM_STOP }

/** Names are accepted only as semantic release versions; never redact arbitrary input into a log. */
class DiagnosticVersion private constructor(val value: String) {
    companion object {
        fun from(value: String?): DiagnosticVersion? = value?.takeIf {
            it.matches(Regex("v?[0-9]{1,8}\\.[0-9]{1,8}(\\.[0-9]{1,8})?(-[0-9]+)?"))
        }?.let(::DiagnosticVersion)
    }
}
data class DiagnosticEvent(
    val component: DiagnosticComponent, val step: DiagnosticStep,
    val reason: DiagnosticReason = DiagnosticReason.NONE, val status: Int = 0,
    val bytes: Long = 0, val count: Int = 0, val failed: Int = 0, val uncertain: Int = 0,
    val installedCode: Long = 0, val availableCode: Long = 0,
    val installedName: DiagnosticVersion? = null, val availableName: DiagnosticVersion? = null,
    val timestampMillis: Long = System.currentTimeMillis(),
) {
    init { require(status == 0 || status in 100..599); require(listOf(bytes, count.toLong(), failed.toLong(), uncertain.toLong(), installedCode, availableCode, timestampMillis).all { it >= 0 }) }
    fun render(): String = buildString {
        append("${java.time.Instant.ofEpochMilli(timestampMillis)} ${component.name.lowercase()}: ${step.title}")
        if (reason != DiagnosticReason.NONE) append(" · reason=${reason.name.lowercase()}")
        if (status != 0) append(" · HTTP $status")
        if (bytes != 0L) append(" · $bytes bytes")
        if (count != 0 || failed != 0 || uncertain != 0) append(" · count=$count failed=$failed uncertain=$uncertain")
        if (installedCode != 0L || availableCode != 0L) append(" · current ${installedName?.value ?: "?"} ($installedCode) → available ${availableName?.value ?: "?"} ($availableCode)")
    }
    fun encode(): String = listOf("op", component, step, reason, status, bytes, count, failed, uncertain, installedCode, availableCode, installedName?.value ?: "-", availableName?.value ?: "-", timestampMillis).joinToString("|")
    companion object {
        fun decode(line: String): DiagnosticEvent {
            val p = line.split('|'); require(p.size == 14 && p[0] == "op")
            require(p[11] == "-" || DiagnosticVersion.from(p[11]) != null)
            require(p[12] == "-" || DiagnosticVersion.from(p[12]) != null)
            return DiagnosticEvent(DiagnosticComponent.valueOf(p[1]), DiagnosticStep.valueOf(p[2]), DiagnosticReason.valueOf(p[3]), p[4].toInt(), p[5].toLong(), p[6].toInt(), p[7].toInt(), p[8].toInt(), p[9].toLong(), p[10].toLong(), DiagnosticVersion.from(p[11]), DiagnosticVersion.from(p[12]), p[13].toLong())
        }
    }
}
fun interface DiagnosticSink {
    fun record(event: DiagnosticEvent)
    companion object { val NONE = DiagnosticSink { } }
}
/** Observability must not change work outcomes. */
fun DiagnosticSink.safelyRecord(event: DiagnosticEvent) { try { record(event) } catch (cancelled: java.util.concurrent.CancellationException) { throw cancelled } catch (_: Exception) { } }
