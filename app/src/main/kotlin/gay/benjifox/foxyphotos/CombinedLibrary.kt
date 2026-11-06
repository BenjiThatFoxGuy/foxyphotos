package gay.benjifox.foxyphotos

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import gay.benjifox.foxyphotos.core.model.*
import gay.benjifox.foxyphotos.core.security.AndroidKeystoreCredentialStore
import gay.benjifox.foxyphotos.provider.contract.CredentialReference
import gay.benjifox.foxyphotos.provider.google.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

private data class CombinedItem(val local: GalleryMedia? = null, val remote: LibraryItem? = null) {
    val key get() = local?.let { "local:${it.uri}" } ?: "google:${remote!!.id}"
    val time get() = local?.timestampMillis ?: remote!!.timestampMillis
}

/** Session-only bounded state; leaving this screen drops remote IDs, cursors and bitmap cache. */
private class GooglePreviewSession(val provider: ReadOnlyLibrary) {
    val slots = Semaphore(3)
    val cache = object : LruCache<String, Bitmap>(12 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount
    }
    suspend fun image(id: String, large: Boolean): Bitmap = slots.withPermit {
        val key = "$large:$id"
        cache.get(key) ?: withContext(Dispatchers.IO) {
            val bytes = provider.preview(id, large)
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            require(bounds.outWidth > 0 && bounds.outHeight > 0)
            val target = if (large) 1600 else 320
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / sample > target) sample *= 2
            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size,
                BitmapFactory.Options().apply { inSampleSize = sample }) ?: error("Preview unavailable")
            currentCoroutineContext().ensureActive()
            cache.put(key, bitmap)
            bitmap
        }
    }
}

@Composable
private fun GooglePreview(session: GooglePreviewSession, item: LibraryItem, large: Boolean = false) {
    var attempt by remember(item.id) { mutableIntStateOf(0) }
    var loading by remember(item.id, large, attempt) { mutableStateOf(true) }
    val bitmap by produceState<Bitmap?>(null, item.id, large, attempt) {
        try { value = session.image(item.id, large) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { value = null }
        finally { loading = false }
    }
    Box(Modifier.fillMaxSize().background(Color(0xFF24242A)), contentAlignment = Alignment.Center) {
        bitmap?.let { Image(it.asImageBitmap(), if (item.video) "Google video preview" else "Google photo",
            Modifier.fillMaxSize(), contentScale = if (large) ContentScale.Fit else ContentScale.Crop) }
            ?: if (loading) CircularProgressIndicator(Modifier.size(24.dp)) else TextButton(onClick = { attempt++ }) { Text("Retry preview") }
        if (item.video) Text("Video · preview only", color = Color.White, modifier = Modifier.align(Alignment.BottomCenter))
    }
}

@Composable
internal fun CombinedLibrary(onPermission: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val diagnostics = remember { AppDiagnostics.get(context) }
    val session = remember {
        val store = AndroidKeystoreCredentialStore(context.applicationContext)
        GooglePreviewSession(GoogleLibraryClient(credential = {
            withContext(Dispatchers.IO) {
                val bytes = store.loadCredential(CredentialReference("google-native"))
                    ?: throw GoogleAuthenticationException("Connect Google Photos in Settings first.")
                try { GoogleCredentialParser.parse(bytes.decodeToString()) } finally { bytes.fill(0) }
            }
        }))
    }
    var locals by remember { mutableStateOf(emptyList<GalleryMedia>()) }
    var localStatus by remember { mutableStateOf("Loading local media…") }
    var remotes by remember { mutableStateOf(emptyList<LibraryItem>()) }
    var cursor by remember { mutableStateOf<String?>(null) }
    var pages by remember { mutableIntStateOf(0) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var stopped by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<CombinedItem?>(null) }
    fun load() {
        if (loading || stopped || pages >= 10) return
        loading = true
        error = null
        scope.launch {
            diagnostics.record(DiagnosticEvent(DiagnosticComponent.LIBRARY, DiagnosticStep.LIBRARY_PAGE))
            try {
                val page = session.provider.page(cursor)
                val combined = (remotes + page.items).distinctBy { it.id }
                if (combined.size > 5000) {
                    stopped = true
                    error = "Session item limit reached. Refresh to start again."
                } else {
                    remotes = combined
                    val previous = cursor
                    cursor = page.nextCursor
                    pages++
                    stopped = cursor == null || cursor == previous
                    if (cursor != null && cursor == previous) error = "Google repeated a page cursor. Paging stopped safely."
                }
                diagnostics.record(DiagnosticEvent(DiagnosticComponent.LIBRARY, DiagnosticStep.COMPLETE, count = page.items.size))
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: GoogleAuthenticationException) {
                error = "Google access unavailable. Connect or reconnect Google Photos in Settings, then retry."
                diagnostics.record(DiagnosticEvent(DiagnosticComponent.LIBRARY, DiagnosticStep.FAILED, DiagnosticReason.AUTHORIZATION))
            } catch (_: Exception) {
                error = "Google page unavailable. Loaded items are kept; retry when ready."
                diagnostics.record(DiagnosticEvent(DiagnosticComponent.LIBRARY, DiagnosticStep.FAILED, DiagnosticReason.NETWORK))
            } finally { loading = false }
        }
    }
    var localGeneration by remember { mutableIntStateOf(0) }
    LaunchedEffect(localGeneration) {
        try {
            locals = queryLocalGallery(context.contentResolver)
            localStatus = if (hasMediaAccess(context)) "Local media includes only items Android allows." else "Allow local photo access to include device media."
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { localStatus = "Local media unavailable. Check photo access and refresh." }
    }
    LaunchedEffect(Unit) { load() }
    val entries = remember(locals, remotes) {
        (locals.map { CombinedItem(local = it) } + remotes.map { CombinedItem(remote = it) })
            .sortedWith(compareByDescending<CombinedItem> { it.time }.thenBy { it.key })
    }
    Column(Modifier.fillMaxSize()) {
        Text("Experimental · Local + Google Photos", Modifier.padding(horizontal = 12.dp), style = MaterialTheme.typography.titleMedium)
        Text(localStatus, Modifier.padding(horizontal = 12.dp), style = MaterialTheme.typography.bodySmall)
        Text("${locals.size} local · ${remotes.size} Google loaded. Sources stay separate; Google listing may be partial.",
            Modifier.padding(horizontal = 12.dp), style = MaterialTheme.typography.bodySmall)
        Row {
            TextButton(onClick = onPermission) { Text("Photo access") }
            TextButton(enabled = !loading, onClick = {
                remotes = emptyList(); cursor = null; pages = 0; stopped = false
                session.cache.evictAll(); localGeneration++; load()
            }) { Text("Refresh") }
        }
        error?.let { Text(it, Modifier.padding(horizontal = 12.dp), color = MaterialTheme.colorScheme.error) }
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        else if (pages >= 10) Text("Session page limit reached. Refresh to start again.", Modifier.padding(12.dp))
        else if (!stopped) TextButton(onClick = { load() }) { Text(if (error != null) "Retry Google page" else "Load next Google page") }
        else if (error == null) Text("Google listing ended for this session.", Modifier.padding(horizontal = 12.dp))
        LazyVerticalGrid(GridCells.Adaptive(100.dp), Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(2.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            items(entries, key = { it.key }) { item ->
                Box(Modifier.aspectRatio(1f).clickable { selected = item }) {
                    item.local?.let { MediaImage(it) } ?: GooglePreview(session, item.remote!!)
                    Text(if (item.local != null) "Local" else "Google", color = Color.White,
                        modifier = Modifier.align(Alignment.TopStart).background(Color.Black.copy(alpha = .7f)).padding(4.dp))
                }
            }
        }
    }
    selected?.let { item ->
        Dialog(onDismissRequest = { selected = null }, properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) {
            Column(Modifier.fillMaxSize().background(Color.Black)) {
                TextButton(onClick = { selected = null }) { Text("Close") }
                Box(Modifier.weight(1f)) {
                    item.local?.let { MediaImage(it, large = true) } ?: GooglePreview(session, item.remote!!, large = true)
                }
                if (item.local?.video == true) Text("Local video thumbnail. Open Local library for playback.", color = Color.White)
            }
        }
    }
    DisposableEffect(session) { onDispose { session.cache.evictAll() } }
}
