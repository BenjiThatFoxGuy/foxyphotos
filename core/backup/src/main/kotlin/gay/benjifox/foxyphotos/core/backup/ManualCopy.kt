package gay.benjifox.foxyphotos.core.backup

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Only upload identity is confirmed. Organization never establishes content safety or deletion authority. */
enum class CopyPhase { QUEUED, READ_FAILED, UPLOAD_UNCERTAIN, UPLOADED, ORGANIZATION_UNCONFIRMED }
class CopyEntry(
    val sourceId: String,
    val phase: CopyPhase = CopyPhase.QUEUED,
    val sha1: String? = null,
    val mediaKey: String? = null,
    val note: String = "Waiting",
) {
    fun update(phase: CopyPhase = this.phase, sha1: String? = this.sha1, mediaKey: String? = this.mediaKey, note: String = this.note) =
        CopyEntry(sourceId, phase, sha1, mediaKey, note)
    override fun toString() = "CopyEntry($phase)"
}
class CopyOrganization(val requested: Boolean, val note: String)

interface ManualCopyPort {
    /** Read/stage one original, return its actual SHA-1. No remote writes. */
    suspend fun stage(sourceId: String): String
    suspend fun findExisting(sha1: String): String?
    suspend fun upload(sourceId: String): String
    /** Returns a sanitized organization status, never a membership confirmation claim. */
    suspend fun organize(sourceId: String, mediaKey: String): CopyOrganization
    fun releaseStage()
}

/** One explicit pass, no daemon or automatic retry. State must be durably saved before each write.
 * A lost upload is reconciled read-only by digest; a negative lookup never authorizes reupload.
 * Organization is independently journaled by the destination adapter before any album write.
 */
suspend fun runManualCopy(
    entries: List<CopyEntry>, port: ManualCopyPort,
    save: (Int, CopyEntry) -> Unit,
    paused: () -> Boolean = { false },
) {
    require(entries.size in 1..20 && entries.map { it.sourceId }.distinct().size == entries.size)
    for ((index, original) in entries.withIndex()) {
        currentCoroutineContext().ensureActive()
        if (paused()) return
        var entry = original
        try {
            if (entry.phase == CopyPhase.ORGANIZATION_UNCONFIRMED) continue
            if (entry.phase == CopyPhase.UPLOAD_UNCERTAIN) {
                val digest = entry.sha1 ?: error("Missing uncertain upload digest")
                val key = port.findExisting(digest)
                if (key == null) { save(index, entry.update(note = "Upload uncertain; no match found. Reupload stopped safely.")); continue }
                entry = entry.update(CopyPhase.UPLOADED, mediaKey = key, note = "Upload identity recovered by presence lookup")
                save(index, entry)
            }
            if (entry.phase == CopyPhase.QUEUED || entry.phase == CopyPhase.READ_FAILED) {
                val digest = port.stage(entry.sourceId)
                require(digest.matches(Regex("[a-f0-9]{40}")))
                if (paused()) return
                val existing = port.findExisting(digest)
                if (existing != null) entry = entry.update(CopyPhase.UPLOADED, digest, existing, "Already present in destination")
                else {
                    currentCoroutineContext().ensureActive()
                    if (paused()) return
                    entry = entry.update(CopyPhase.UPLOAD_UNCERTAIN, digest, note = "Upload in flight or uncertain")
                    save(index, entry) // MUST commit before provider bytes/commit.
                    val key = port.upload(entry.sourceId)
                    require(key.isNotBlank())
                    entry = entry.update(CopyPhase.UPLOADED, mediaKey = key, note = "Upload confirmed; organization pending")
                }
                save(index, entry)
            }
            if (paused()) return
            if (entry.phase == CopyPhase.UPLOADED) {
                // Do not mark final before adapter returns: its own journal prevents replay on interruption.
                val result = port.organize(entry.sourceId, requireNotNull(entry.mediaKey))
                entry = entry.update(if (result.requested) CopyPhase.ORGANIZATION_UNCONFIRMED else CopyPhase.UPLOADED, note = result.note)
                save(index, entry)
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) {
            val next = when (entry.phase) {
                CopyPhase.QUEUED, CopyPhase.READ_FAILED -> entry.update(CopyPhase.READ_FAILED, note = "Read/presence failed; retry is safe")
                CopyPhase.UPLOAD_UNCERTAIN -> entry.update(note = "Upload uncertain; retry performs presence lookup only")
                else -> entry.update(note = "Upload confirmed; organization unavailable or uncertain")
            }
            save(index, next)
        } finally { port.releaseStage() }
    }
}
