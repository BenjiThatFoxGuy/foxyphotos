package gay.benjifox.foxyphotos

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import gay.benjifox.foxyphotos.core.model.*
import gay.benjifox.foxyphotos.core.security.AndroidKeystoreCredentialStore
import gay.benjifox.foxyphotos.provider.contract.CredentialReference
import gay.benjifox.foxyphotos.provider.icloud.*
import kotlinx.coroutines.*

@Composable
internal fun ICloudSessionSetup() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val repository = remember { ICloudSessionRepository(AndroidKeystoreCredentialStore(context.applicationContext)) }
    var section by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("Choose a locally exported rclone config containing the intended iCloud Photos remote. Existing session is kept if validation fails.") }
    val diagnostics = remember { AppDiagnostics.get(context) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            busy = true
            scope.launch {
                try {
                    withContext(Dispatchers.IO) {
                        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytesBounded(256 * 1024) }
                            ?: throw IllegalArgumentException()
                        try { repository.importSession(bytes.decodeToString(throwOnInvalidSequence = true), section.trim()) }
                        finally { bytes.fill(0) }
                    }
                    status = "Session validated and stored encrypted. Reopen the iCloud library to use it."
                    diagnostics.record(DiagnosticEvent(DiagnosticComponent.LIBRARY, DiagnosticStep.COMPLETE))
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: ICloudReauthenticationRequired) {
                    status = "Session rejected or a renewed Apple challenge is required. Refresh it externally, then import again. Previous session and backup queues are kept."
                    diagnostics.record(DiagnosticEvent(DiagnosticComponent.LIBRARY, DiagnosticStep.FAILED, DiagnosticReason.AUTHORIZATION))
                } catch (_: IllegalArgumentException) {
                    status = "Import rejected. Check section name, type = iclouddrive, service = photos, trust_token and Cookie-header cookies. Password, if present, must use rclone obscure. Whole-file encrypted configs and browser JSON are unsupported."
                } catch (_: Exception) {
                    status = "Could not validate or securely save the session. Check network and Photos availability, then retry. Previous session and queues are kept."
                    diagnostics.record(DiagnosticEvent(DiagnosticComponent.LIBRARY, DiagnosticStep.FAILED, DiagnosticReason.NETWORK))
                } finally { busy = false }
            }
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Manual iCloud session", style = MaterialTheme.typography.titleMedium)
        Text("Sign in externally first. Import only the intended remote; no password sign-in is performed here. Cookies must be name=value;name=value;. Keep the exported file private and remove it yourself when finished.", style = MaterialTheme.typography.bodySmall)
        OutlinedTextField(section, { section = it }, label = { Text("Exact remote section name (without brackets)") }, enabled = !busy, singleLine = true, modifier = Modifier.fillMaxWidth())
        Button(enabled = !busy && section.isNotBlank(), onClick = { picker.launch(arrayOf("text/*", "application/octet-stream")) }) { Text("Import / replace session file") }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        Text(status, style = MaterialTheme.typography.bodySmall)
    }
}

private fun java.io.InputStream.readBytesBounded(limit: Int): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (out.size() <= limit) {
        val n = read(buffer, 0, minOf(buffer.size, limit + 1 - out.size()))
        if (n < 0) break
        out.write(buffer, 0, n)
    }
    require(out.size() <= limit)
    return out.toByteArray()
}

internal class ICloudSourceState {
    var preview by mutableStateOf<GooglePreviewSession?>(null)
    var items by mutableStateOf(emptyList<LibraryItem>())
}

@Composable
internal fun ICloudLibraryControls(state: ICloudSourceState) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val repository = remember { ICloudSessionRepository(AndroidKeystoreCredentialStore(context.applicationContext)) }
    val diagnostics = remember { AppDiagnostics.get(context) }
    var client by remember { mutableStateOf<ICloudLibraryClient?>(null) }
    var status by remember { mutableStateOf("Loading encrypted iCloud session…") }
    var busy by remember { mutableStateOf(false) }
    var expired by remember { mutableStateOf(false) }
    var cursor by remember { mutableStateOf<String?>(null) }
    var pages by remember { mutableIntStateOf(0) }
    var ended by remember { mutableStateOf(false) }
    var album by remember { mutableStateOf<LibraryAlbum?>(null) }
    var albums by remember { mutableStateOf(emptyList<LibraryAlbum>()) }
    var albumCursor by remember { mutableStateOf<String?>(null) }
    var albumPages by remember { mutableIntStateOf(0) }
    var showAlbums by remember { mutableStateOf(false) }
    fun failure(error: Exception) {
        expired = error is ICloudReauthenticationRequired
        status = if (expired) "iCloud session expired/rejected. Import a renewed session in Settings and reopen this view. Loaded items and backup queues are kept."
            else "iCloud request unavailable. Loaded items are kept. Retry or refresh; Photos may be unavailable or still indexing."
        diagnostics.record(DiagnosticEvent(DiagnosticComponent.LIBRARY, DiagnosticStep.FAILED,
            if (expired) DiagnosticReason.AUTHORIZATION else DiagnosticReason.NETWORK))
    }
    fun load() {
        val provider = client ?: return
        if (busy || expired || ended || pages >= 10) return
        busy = true
        scope.launch {
            try {
                val result = withContext(Dispatchers.IO) { album?.let { provider.albumPage(it.id, cursor) } ?: provider.page(cursor) }
                val all = (state.items + result.items).distinctBy { it.id }
                require(all.size <= 5000)
                state.items = all
                val previous = cursor
                cursor = result.nextCursor
                pages++
                ended = cursor == null || cursor == previous
                status = "${state.items.size} iCloud loaded · ${album?.title ?: "Primary library · All Photos"}. " +
                    if (ended) "Listing ended." else "Partial listing; load more explicitly."
                diagnostics.record(DiagnosticEvent(DiagnosticComponent.LIBRARY, DiagnosticStep.COMPLETE, count = result.items.size))
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (e: Exception) { failure(e) }
            finally { busy = false }
        }
    }
    fun loadAlbums() {
        val provider = client ?: return
        if (busy || expired || albumPages >= 10) return
        busy = true
        scope.launch {
            try {
                val result = withContext(Dispatchers.IO) { provider.albums(albumCursor) }
                albums = (albums + result.albums).distinctBy { it.id }
                albumCursor = result.nextCursor
                albumPages++
                showAlbums = true
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (e: Exception) { failure(e) }
            finally { busy = false }
        }
    }
    LaunchedEffect(Unit) {
        try {
            val stored = withContext(Dispatchers.IO) { repository.load() }
            if (stored == null) {
                val credential = AndroidKeystoreCredentialStore(context.applicationContext)
                    .loadCredential(CredentialReference("google-native"))
                    ?.decodeToString()
                if (credential != null) {
                    status = "iCloud session expired. Import a renewed session in Settings and reopen this view. Loaded items and backup queues are kept."
                } else {
                    status = "iCloud not configured. Import a session file in Settings. Local only mode is active."
                }
            } else {
                client = ICloudLibraryClient(stored) { updated -> withContext(Dispatchers.IO) { repository.save(updated) } }
                state.preview = GooglePreviewSession(client!!)
                load()
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (e: Exception) { failure(e) }
    }
    Text(status, Modifier.padding(horizontal = 12.dp), style = MaterialTheme.typography.bodySmall)
    Row {
        TextButton(modifier = Modifier.weight(1f), enabled = client != null && !busy && !expired && !ended && pages < 10, onClick = { load() }) { Text("Load iCloud page") }
        TextButton(modifier = Modifier.weight(1f), enabled = client != null && !busy && !expired, onClick = { if (albumPages == 0) loadAlbums() else showAlbums = true }) { Text("iCloud albums") }
        TextButton(modifier = Modifier.weight(1f), enabled = client != null && !busy && !expired, onClick = {
            state.items = emptyList(); cursor = null; pages = 0; ended = false
            state.preview?.cache?.evictAll(); load()
        }) { Text("Refresh iCloud") }
    }
    if (pages >= 10) Text("iCloud page limit reached. Refresh or choose another album.", Modifier.padding(horizontal = 12.dp))
    if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
    ManualCopyControls(client, album, state.items)
    if (showAlbums) AlertDialog(onDismissRequest = { showAlbums = false }, title = { Text("iCloud libraries / albums") },
        text = {
            LazyColumn(Modifier.heightIn(max = 400.dp)) {
                items(albums, key = { it.id }) { entry ->
                    TextButton(enabled = !busy, onClick = {
                        album = entry; state.items = emptyList(); cursor = null; pages = 0; ended = false
                        showAlbums = false; load()
                    }) { Text(entry.title) }
                }
                if (albumCursor != null && albumPages < 10) item { TextButton(enabled = !busy, onClick = { loadAlbums() }) { Text("Load more albums") } }
                item { Text("Bounded read-only list. Album names are not used to merge identities.") }
            }
        }, confirmButton = { TextButton(onClick = { showAlbums = false }) { Text("Close") } })
    DisposableEffect(state.preview) { val preview = state.preview; onDispose { preview?.cache?.evictAll() } }
}

@Composable
internal fun ICloudOriginalRead(provider: ReadOnlyLibrary, id: String) {
    val cloud = provider as? AlbumReadOnlyLibrary ?: return
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember(id) { mutableStateOf(false) }
    var status by remember(id) { mutableStateOf("") }
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        if (uri != null) {
            busy = true
            scope.launch {
                try {
                    withContext(Dispatchers.IO) {
                        val bytes = cloud.read(id, 32 * 1024 * 1024)
                        try { context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) } ?: error("Output unavailable") }
                        finally { bytes.fill(0) }
                    }
                    status = "Original read saved. This is not remote verification for cleanup."
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { status = "Original read failed or exceeds 32 MiB. The chosen output may be empty/incomplete; retry after refreshing the library." }
                finally { busy = false }
            }
        }
    }
    TextButton(enabled = !busy, onClick = { save.launch("icloud-original") }) { Text(if (busy) "Reading original…" else "Save original (up to 32 MiB)") }
    if (status.isNotEmpty()) Text(status, color = androidx.compose.ui.graphics.Color.White, modifier = Modifier.padding(12.dp))
}
