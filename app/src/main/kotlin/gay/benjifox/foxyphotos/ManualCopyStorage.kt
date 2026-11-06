package gay.benjifox.foxyphotos

import android.content.Context
import gay.benjifox.foxyphotos.core.backup.*
import org.json.JSONArray
import org.json.JSONObject

/** Private device-local queue and immutable historical receipts; no secrets or signed URLs. */
internal class ManualCopyBatch(
    val id: String, val sourceAccount: String, val destinationAccount: String,
    val albumId: String, val albumTitle: String, val entries: List<CopyEntry>,
    val cancelled: Boolean = false,
) {
    fun matchesAccounts(source: String?, destination: String?) = source != null && destination != null &&
        source == sourceAccount && destination == destinationAccount
    fun update(entries: List<CopyEntry> = this.entries, cancelled: Boolean = this.cancelled) =
        ManualCopyBatch(id, sourceAccount, destinationAccount, albumId, albumTitle, entries, cancelled)
    override fun toString() = "ManualCopyBatch(redacted)"
}
internal class ManualCopyStorage(context: Context) {
    private val prefs = context.getSharedPreferences("foxyphotos.manual-copy", Context.MODE_PRIVATE)
    fun enabled() = prefs.getBoolean("enabled", false)
    fun setEnabled(enabled: Boolean) { check(prefs.edit().putBoolean("enabled", enabled).commit()) }
    fun load(): ManualCopyBatch? = prefs.getString("batch", null)?.let { text ->
        val o = JSONObject(text)
        val rows = o.getJSONArray("items")
        require(rows.length() in 1..20)
        ManualCopyBatch(o.getString("id"), o.getString("source"), o.getString("destination"),
            o.getString("album"), o.getString("title"), (0 until rows.length()).map {
                decode(rows.getJSONObject(it))
            }, o.getBoolean("cancelled"))
    }
    fun create(source: String, destination: String, album: String, title: String, ids: List<String>): ManualCopyBatch {
        require(load() == null && ids.size in 1..20 && ids.distinct().size == ids.size)
        val entries = ids.map { id -> receipt(source, destination, album, id)
            ?: prefs.getString(contentKey(source, destination, id), null)?.let { decode(JSONObject(it)) }
            ?: CopyEntry(id) }
        return ManualCopyBatch(java.util.UUID.randomUUID().toString(), source, destination, album, title, entries).also { save(it) }
    }
    fun save(batch: ManualCopyBatch) {
        require(batch.entries.size in 1..20)
        val obj = JSONObject().put("id", batch.id).put("source", batch.sourceAccount).put("destination", batch.destinationAccount)
            .put("album", batch.albumId).put("title", batch.albumTitle).put("cancelled", batch.cancelled)
            .put("items", JSONArray(batch.entries.map { encode(it) }))
        val edit = prefs.edit().putString("batch", obj.toString())
        batch.entries.forEach { entry ->
            if (entry.phase != CopyPhase.QUEUED && entry.phase != CopyPhase.READ_FAILED) {
                edit.putString(receiptKey(batch.sourceAccount, batch.destinationAccount, batch.albumId, entry.sourceId), encode(entry).toString())
                val content = if (entry.phase == CopyPhase.ORGANIZATION_UNCONFIRMED)
                    entry.update(CopyPhase.UPLOADED, note = "Upload confirmed; this album not yet requested") else entry
                edit.putString(contentKey(batch.sourceAccount, batch.destinationAccount, entry.sourceId), encode(content).toString())
            }
        }
        check(edit.commit())
    }
    /** Clearing the UI never clears uncertain/confirmed historical receipts. */
    fun close() { check(prefs.edit().remove("batch").commit()) }
    private fun receipt(source: String, destination: String, album: String, id: String): CopyEntry? =
        prefs.getString(receiptKey(source, destination, album, id), null)?.let { decode(JSONObject(it)) }
    private fun receiptKey(source: String, destination: String, album: String, id: String) = "receipt.${scope("$source\u0000$destination\u0000$album\u0000$id") }"
    private fun contentKey(source: String, destination: String, id: String) = "content.${scope("$source\u0000$destination\u0000$id") }"
    private fun encode(e: CopyEntry) = JSONObject().put("id", e.sourceId).put("phase", e.phase.name)
        .put("sha1", e.sha1 ?: JSONObject.NULL).put("key", e.mediaKey ?: JSONObject.NULL).put("note", e.note)
    private fun decode(o: JSONObject) = CopyEntry(o.getString("id"), CopyPhase.valueOf(o.getString("phase")),
        o.optString("sha1").takeUnless { it.isEmpty() || it == "null" },
        o.optString("key").takeUnless { it.isEmpty() || it == "null" }, o.getString("note"))
    companion object {
        fun scope(value: String) = java.security.MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
            .joinToString("") { "%02x".format(it) }
    }
}
