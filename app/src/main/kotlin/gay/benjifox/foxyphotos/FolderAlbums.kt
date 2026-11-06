package gay.benjifox.foxyphotos

import android.content.Context
import android.provider.MediaStore
import gay.benjifox.foxyphotos.core.model.*
import gay.benjifox.foxyphotos.provider.google.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Device-private, account-scoped journal. An intent is committed BEFORE each non-idempotent write.
 * Missing acknowledgement is terminal for automatic replay. A title alone never repairs an intent.
 */
internal class FolderAlbums(context: Context, account: String, private val client: GoogleAlbumAccess) {
    private val prefs = context.getSharedPreferences("foxyphotos.folder-albums", Context.MODE_PRIVATE)
    private val scope = digest(account)
    private var operations = 0
    private var cached: List<GoogleAlbum>? = null
    private fun key(kind: String, value: String) = "$scope.$kind.${digest(value)}"
    private fun put(key: String, value: String) { check(prefs.edit().putString(key, value).commit()) }
    private fun get(key: String) = prefs.getString(key, null)
    private suspend fun albums(): List<GoogleAlbum> {
        val result = mutableListOf<GoogleAlbum>()
        val cursors = mutableSetOf<String>()
        var cursor: String? = null
        repeat(10) {
            val page = client.albumPage(cursor)
            result += page.albums
            check(result.size <= 5000)
            cursor = page.nextCursor
            if (cursor == null) {
                check(result.groupBy { it.id }.values.all { entries -> entries.distinct().size == 1 })
                return result.distinctBy { it.id }
            }
            check(cursors.add(cursor!!))
        }
        error("Incomplete album enumeration")
    }

    fun attempted(folder: String, contentIdentity: String): Boolean = get(key("candidate", "$folder\u0000$contentIdentity")) != null

    suspend fun associate(folder: String, knownFolders: Set<String>, mediaKey: String, contentIdentity: String = mediaKey, admitted: () -> Boolean): String = mutex.withLock {
        if (!admitted()) return@withLock "Paused"
        if (operations >= 20) return@withLock "Batch album limit reached; run backup again for remaining items"
        val candidate = key("candidate", "$folder\u0000$contentIdentity")
        if (get(candidate) != null) return@withLock "Earlier album request uncertain or acknowledged; no replay"
        val title = folderAlbumTitle(folder)
        if (title.isBlank() || title.length > 500 || folderTitleCollides(folder, knownFolders))
            return@withLock "Folder title collision or unsupported title; no album write"
        val claim = key("title", title)
        val owner = get(claim)
        if (owner != null && owner != digest(folder)) return@withLock "Folder title already claimed by another device folder"
        put(claim, digest(folder))
        val mapping = key("mapping", folder)
        val intent = key("create", folder)
        val list = cached ?: albums().also { cached = it }
        val match = matchFolderAlbum(folder, title, list.map { AlbumIdentity(it.id, it.title) }, true)
        var id = get(mapping)
        if (id != null) {
            if (match !is AlbumNameMatch.Existing || match.albumId != id)
                return@withLock "Mapped album missing, renamed or ambiguous; no album write"
        } else when (match) {
            is AlbumNameMatch.Existing -> {
                // A lost create response cannot be resolved from title alone (external writers may race).
                if (get(intent) != null) return@withLock "Earlier creation uncertain; inspect Google albums before any further mapping"
                id = match.albumId
                put(mapping, id)
            }
            AlbumNameMatch.Missing -> {
                if (get(intent) != null) return@withLock "Earlier creation uncertain; automatic creation stopped"
                if (!admitted()) return@withLock "Paused"
                operations++
                check(prefs.edit().putString(intent, "pending").putString(candidate, "uncertain").commit())
                id = client.createAlbum(title, mediaKey)
                // The create response confirms identity, not that the included item became a member.
                // Persist identity and unresolved membership atomically before allowing another item.
                check(prefs.edit().putString(mapping, id).putString(intent, "confirmed")
                    .putString(key("member", "$id\u0000$mediaKey"), "acknowledged")
                    .putString(key("reserved", id), "1").commit())
                cached = null
                return@withLock "Album creation confirmed; included media membership unconfirmed"

            }
            else -> return@withLock "Ambiguous album title; no album write"
        }
        val albumId = requireNotNull(id)
        val member = key("member", "$albumId\u0000$mediaKey")
        if (get(member) != null) return@withLock "Membership previously requested; confirmation unavailable, no replay"
        val album = cached!!.singleOrNull { it.id == albumId }
        val reservationKey = key("reserved", albumId)
        val reserved = get(reservationKey)?.toIntOrNull() ?: 0
        if (!albumHasCapacity(album?.count, reserved)) return@withLock "Album full or capacity unknown; no rollover or membership request"
        if (!admitted()) return@withLock "Paused"
        // One atomic durable reservation + intent. Never retry an uncertain request automatically.
        operations++
        check(prefs.edit().putString(member, "uncertain").putString(candidate, "uncertain").putString(reservationKey, (reserved + 1).toString()).commit())
        client.requestMembership(albumId, mediaKey)
        put(member, "acknowledged")
        "Membership request acknowledged; remote membership unconfirmed"
    }

    companion object {
        private val mutex = Mutex()
        private fun digest(value: String) = java.security.MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
        fun enabled(context: Context) = context.getSharedPreferences(BackupService.SETTINGS, Context.MODE_PRIVATE)
            .getBoolean("experimental-folder-albums", false)
        fun status(context: Context, value: String) {
            context.getSharedPreferences(BackupService.SETTINGS, Context.MODE_PRIVATE).edit().putString("folder-albums-status", value).apply()
        }
    }
}

/** Include actual MediaStore volume; relative paths alone can collide across cards. */
internal fun albumFolderIdentity(uri: android.net.Uri, relativePath: String): String =
    "${MediaStore.getVolumeName(uri)}:$relativePath"
