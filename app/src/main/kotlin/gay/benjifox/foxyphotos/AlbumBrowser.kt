package gay.benjifox.foxyphotos

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import gay.benjifox.foxyphotos.provider.google.*
import kotlinx.coroutines.*

@Composable
internal fun FolderAlbumSettings(open: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences(BackupService.SETTINGS, Context.MODE_PRIVATE) }
    var enabled by remember { mutableStateOf(FolderAlbums.enabled(context)) }
    Row {
        Text("Experimental folder → Google albums", Modifier.weight(1f))
        Switch(enabled, modifier = Modifier.semantics { contentDescription = "Enable experimental folder albums" }, onCheckedChange = { enabled = it; prefs.edit().putBoolean("experimental-folder-albums", it).apply() })
    }
    Text("Off by default. Choose existing destinations below. Unmapped folders use exact unique titles. Only selected backup folders are eligible.", style = MaterialTheme.typography.bodySmall)
    Text(prefs.getString("folder-albums-status", "No album operation yet")!!, style = MaterialTheme.typography.bodySmall)
    TextButton(onClick = open) { Text("Folder album destinations") }
}

internal class AlbumMappingConnection(val account: String, val client: GoogleAlbumAccess)

@JvmSynthetic
internal suspend fun albumMappingConnection(context: Context): AlbumMappingConnection {
    val credential = AndroidManualCopy.credential(context)
    return AlbumMappingConnection(credential.accountEmail, GoogleLibraryClient({ credential }))
}

/** Native mapping controls. Saving is local only: no upload, membership request or backup admission. */
@Composable
internal fun AlbumBrowser(
    connectionLoader: (suspend () -> AlbumMappingConnection)? = null,
    folderLoader: (suspend () -> List<AlbumLocalFolder>)? = null,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var connection by remember { mutableStateOf<AlbumMappingConnection?>(null) }
    var journal by remember { mutableStateOf<FolderAlbums?>(null) }
    var folders by remember { mutableStateOf(emptyList<AlbumLocalFolder>()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var revision by remember { mutableIntStateOf(0) }
    var picker by remember { mutableStateOf<AlbumLocalFolder?>(null) }
    var clear by remember { mutableStateOf<AlbumLocalFolder?>(null) }
    var saving by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<String?>(null) }
    val selectedFolders = context.getSharedPreferences(BackupService.SETTINGS, Context.MODE_PRIVATE)
        .getStringSet("enabled-folders", emptySet()) ?: emptySet()
    fun refresh() {
        loading = true; error = null
        scope.launch {
            try {
                folders = folderLoader?.invoke() ?: accessibleAlbumFolders(context)
                val loaded = withContext(Dispatchers.IO) {
                    connectionLoader?.invoke() ?: albumMappingConnection(context)
                }
                connection = loaded
                journal = withContext(Dispatchers.IO) { FolderAlbums(context, loaded.account, loaded.client) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { error = "Folders or Google connection unavailable. Check photo access and your saved connection, then retry." }
            finally { loading = false }
        }
    }
    LaunchedEffect(Unit) { refresh() }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp)) {
        item {
            Text("Folder album destinations", style = MaterialTheme.typography.titleLarge)
            Text("Saving a destination does not select a backup folder or start backup. Manage inclusion in Backup folders.", style = MaterialTheme.typography.bodySmall)
            Text("Album requests remain unconfirmed. Changing or clearing a destination never replays earlier requests.", style = MaterialTheme.typography.bodySmall)
            if (!FolderAlbums.enabled(context)) Text("Album organization is off. Enable it in Settings to apply saved destinations.")
            if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            error?.let { Text(it); TextButton(onClick = { refresh() }) { Text("Retry folders and connection") } }
            result?.let { Text(it) }
            if (!loading && error == null && folders.isEmpty()) Text("No accessible local folders. Check photo access.")
        }
        items(folders, key = { it.identity }) { folder ->
            val saved = remember(journal, revision, folder.identity) { journal?.destination(folder.identity) }
            Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                Text(folder.path, style = MaterialTheme.typography.titleMedium)
                Text("${folder.volume} · ${folder.count} accessible items", style = MaterialTheme.typography.bodySmall)
                Text(if (folder.path in selectedFolders) "Included in backup" else "Not selected for backup")
                Text("Destination: ${saved?.title?.ifBlank { "Untitled album" } ?: if (journal?.destinationCleared(folder.identity) == true) "None" else "Automatic exact-title matching"}")
                Row {
                    TextButton(enabled = journal != null && !saving, onClick = { picker = folder }) { Text(if (saved == null) "Choose album" else "Change album") }
                    TextButton(enabled = journal != null && !saving, onClick = { clear = folder }) { Text("Clear destination") }
                }
            }
        }
    }
    val target = picker
    val connected = connection
    if (target != null && connected != null) key(target.identity, connected) {
        GoogleDestinationPicker(connected.client, onDismiss = { if (!saving) picker = null }, saving = saving) { album ->
            saving = true
            scope.launch {
                try {
                    withContext(Dispatchers.IO) { journal!!.selectDestination(target.identity, album) }
                    revision++; picker = null; result = "Destination saved. Backup selection unchanged."
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { result = "Destination could not be saved. Try again." }
                finally { saving = false }
            }
        }
    }
    clear?.let { folder -> AlertDialog(onDismissRequest = { if (!saving) clear = null },
        title = { Text("Clear destination?") },
        text = { Text("Stops album organization for this folder. Backup selection and earlier request receipts are kept.") },
        confirmButton = { TextButton(enabled = !saving, onClick = {
            saving = true
            scope.launch {
                try {
                    withContext(Dispatchers.IO) { journal!!.clearDestination(folder.identity) }
                    revision++; clear = null; result = "Destination cleared. Backup selection unchanged."
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { result = "Destination could not be cleared. Try again." }
                finally { saving = false }
            }
        }) { Text("Clear") } },
        dismissButton = { TextButton(enabled = !saving, onClick = { clear = null }) { Text("Cancel") } }) }
}

@Composable
private fun GoogleDestinationPicker(client: GoogleAlbumAccess, onDismiss: () -> Unit, saving: Boolean, onSave: (GoogleAlbum) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var albums by remember { mutableStateOf(emptyList<GoogleAlbum>()) }
    var cursor by remember { mutableStateOf<String?>(null) }
    val seen = remember { mutableSetOf<String>() }
    var pages by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var ended by remember { mutableStateOf(false) }
    var stopped by remember { mutableStateOf(false) }
    var chosen by remember { mutableStateOf<GoogleAlbum?>(null) }
    var message by remember { mutableStateOf("Loading Google albums…") }
    fun load() {
        if (busy || ended || stopped) return
        busy = true
        scope.launch {
            try {
                AppDiagnostics.get(context).safelyRecord(gay.benjifox.foxyphotos.core.model.DiagnosticEvent(
                    gay.benjifox.foxyphotos.core.model.DiagnosticComponent.LIBRARY, gay.benjifox.foxyphotos.core.model.DiagnosticStep.ALBUM_PAGE))
                val page = client.albumPage(cursor)
                val all = albums + page.albums
                check(all.size <= 5000 && all.groupBy { it.id }.values.all { it.distinct().size == 1 })
                albums = all.distinctBy { it.id }
                pages++
                ended = page.nextCursor == null
                stopped = !ended && (pages >= 10 || !seen.add(page.nextCursor!!))
                cursor = page.nextCursor
                message = when {
                    stopped -> "Partial listing stopped safely. Close and reopen to refresh."
                    ended && albums.isEmpty() -> "No Google albums found."
                    ended -> "All available albums loaded."
                    else -> "Partial listing. More albums available."
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { message = "Could not load Google albums. Loaded choices are kept; retry." }
            finally { busy = false }
        }
    }
    LaunchedEffect(Unit) { load() }
    AlertDialog(onDismissRequest = onDismiss,
        title = { Text("Choose Google album") },
        text = { Column {
            Text(message)
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            LazyColumn(Modifier.heightIn(max = 320.dp)) {
                items(albums, key = { it.id }) { album ->
                    Row(Modifier.fillMaxWidth()) {
                        RadioButton(selected = chosen?.id == album.id, enabled = !saving,
                            onClick = { chosen = album }, modifier = Modifier.semantics { contentDescription = "Select ${album.title.ifBlank { "Untitled album" }}" })
                        Column(Modifier.weight(1f)) {
                            Text(album.title.ifBlank { "Untitled album" })
                            Text(album.count?.let { "$it items" } ?: "Capacity unknown", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                item { if (!ended && !stopped) TextButton(enabled = !busy && !saving, onClick = { load() }) { Text("Load more / retry") } }
            }
        } },
        confirmButton = { TextButton(enabled = chosen != null && !saving, onClick = { chosen?.let(onSave) }) { Text("Save destination") } },
        dismissButton = { TextButton(enabled = !saving, onClick = onDismiss) { Text("Cancel") } })
}
