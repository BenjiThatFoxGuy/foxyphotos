package gay.benjifox.foxyphotos

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import gay.benjifox.foxyphotos.core.media.*
import gay.benjifox.foxyphotos.core.model.*
import gay.benjifox.foxyphotos.core.security.AndroidKeystoreCredentialStore
import gay.benjifox.foxyphotos.provider.contract.CredentialReference
import gay.benjifox.foxyphotos.provider.google.*
import kotlinx.coroutines.*

@Composable
internal fun FolderAlbumSettings(open: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences(BackupService.SETTINGS, android.content.Context.MODE_PRIVATE) }
    var enabled by remember { mutableStateOf(FolderAlbums.enabled(context)) }
    Row {
        Text("Experimental folder → Google albums", Modifier.weight(1f))
        Switch(enabled, onCheckedChange = { enabled = it; prefs.edit().putBoolean("experimental-folder-albums", it).apply() })
    }
    Text("Off by default. Applies only to confirmed media in admitted backup batches, including existing uploads. Exact unique titles only; equal folder names stop safely. No automatic rollover.", style = MaterialTheme.typography.bodySmall)
    Text("Album creation and membership are separate from backup success. Requests are not proof of membership; uncertain writes are never automatically replayed.", style = MaterialTheme.typography.bodySmall)
    Text(prefs.getString("folder-albums-status", "No album operation yet")!!, style = MaterialTheme.typography.bodySmall)
    TextButton(onClick = open) { Text("Browse folders and Google albums") }
}

/** Real native metadata listing. Content uses the provider-returned web album ID explicitly. */
@Composable
internal fun AlbumBrowser() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val client = remember {
        val store = AndroidKeystoreCredentialStore(context)
        GoogleLibraryClient(credential = {
            withContext(Dispatchers.IO) {
                val bytes = store.loadCredential(CredentialReference("google-native")) ?: error("Missing Google connection")
                try { GoogleCredentialParser.parse(bytes.decodeToString()) } finally { bytes.fill(0) }
            }
        })
    }
    var folders by remember { mutableStateOf(emptyList<MediaFolder>()) }
    var albums by remember { mutableStateOf(emptyList<GoogleAlbum>()) }
    var cursor by remember { mutableStateOf<String?>(null) }
    var pages by remember { mutableIntStateOf(0) }
    var ended by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("Google albums have not been loaded.") }
    var localError by remember { mutableStateOf<String?>(null) }
    var localItems by remember { mutableStateOf<List<LocalMedia>?>(null) }
    var selected by remember { mutableStateOf<GalleryMedia?>(null) }
    fun load() {
        if (busy || ended || pages >= 10) return
        busy = true
        scope.launch {
            try {
                AppDiagnostics.get(context).safelyRecord(DiagnosticEvent(DiagnosticComponent.LIBRARY, DiagnosticStep.ALBUM_PAGE))
                val page = client.albumPage(cursor)
                val combined = (albums + page.albums).distinctBy { it.id }
                check(combined.size <= 5000)
                albums = combined
                ended = page.nextCursor == null || page.nextCursor == cursor
                if (page.nextCursor != null && page.nextCursor == cursor) message = "Repeated cursor; stopped safely."
                else message = if (ended) "Google album listing ended." else "Partial album listing; load the next page."
                cursor = page.nextCursor
                pages++
                if (pages == 10 && !ended) message = "Session page limit reached; listing is partial."
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { message = "Google albums unavailable. Check your saved connection and retry; loaded albums are kept." }
            finally { busy = false }
        }
    }
    LaunchedEffect(Unit) {
        try { folders = MediaStoreFolderScanner(context.contentResolver).scan() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { localError = "Local folders unavailable. Check photo access in Settings." }
    }
    val contents = localItems
    if (contents != null) Column(Modifier.fillMaxSize()) {
        TextButton(onClick = { localItems = null }) { Text("Back to albums and folders") }
        Text("${contents.size} accessible local items")
        LazyVerticalGrid(GridCells.Adaptive(100.dp), Modifier.weight(1f)) {
            items(contents, key = { it.uri.toString() }) { item ->
                val media = GalleryMedia(item.uri, item.modifiedEpochSeconds * 1000, item.uri.toString().contains("/video/"))
                Box(Modifier.aspectRatio(1f).clickable { selected = media }) { MediaImage(media) }
            }
        }
    } else LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp)) {
        item { Text("Folders and Google albums", style = MaterialTheme.typography.titleLarge) }
        item { Text("Local folders", style = MaterialTheme.typography.titleMedium); localError?.let { Text(it) } }
        items(folders, key = { "local:${it.relativePath}" }) { folder ->
            TextButton(onClick = { scope.launch {
                try { localItems = MediaStoreFolderScanner(context.contentResolver).scanSelectedFolders(setOf(folder.relativePath)) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { localError = "Folder unavailable; check photo access." }
            } }) { Text("${folder.relativePath} · ${folder.itemCount}") }
        }
        item {
            Text("Google albums", style = MaterialTheme.typography.titleMedium)
            Text("Real native album metadata. Open contents in Google Photos; in-app Google album contents are not yet supported.", style = MaterialTheme.typography.bodySmall)
            Text(message)
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            TextButton(enabled = !busy && !ended && pages < 10, onClick = { load() }) { Text(if (pages == 0) "Load Google albums" else "Load next album page / retry") }
            TextButton(enabled = !busy, onClick = { albums = emptyList(); cursor = null; pages = 0; ended = false; load() }) { Text("Refresh Google albums") }
        }
        items(albums, key = { "google:${it.id}" }) { album ->
            Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                Text(album.title.ifBlank { "Untitled album" })
                Text(album.count?.let { "$it media items" } ?: "Item count unavailable", style = MaterialTheme.typography.bodySmall)
                if (album.webId != null) TextButton(onClick = {
                    val url = Uri.Builder().scheme("https").authority("photos.google.com").appendPath("album").appendPath(album.webId).build()
                    try { context.startActivity(Intent(Intent.ACTION_VIEW, url)) }
                    catch (_: Exception) { message = "No app available to open Google Photos." }
                }) { Text("Open album in Google Photos") }
                else Text("Google web album link unavailable", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
    selected?.let { media -> Dialog(onDismissRequest = { selected = null }) {
        Column { TextButton(onClick = { selected = null }) { Text("Close") }; Box(Modifier.fillMaxWidth().aspectRatio(.8f)) { MediaImage(media, large = true) } }
    } }
}
