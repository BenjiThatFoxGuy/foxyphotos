package gay.benjifox.foxyphotos

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import gay.benjifox.foxyphotos.core.backup.*
import gay.benjifox.foxyphotos.core.model.*
import gay.benjifox.foxyphotos.core.security.AndroidKeystoreCredentialStore
import gay.benjifox.foxyphotos.provider.icloud.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import java.util.concurrent.atomic.AtomicBoolean

/** A process-wide owner prevents staging cleanup or journal replacement by a second screen. */
private val manualCopyOwner = Mutex()

@Composable
internal fun ManualCopyControls(source: ICloudLibraryClient?, album: LibraryAlbum?, items: List<LibraryItem>) {
    val context = LocalContext.current
    val storage = remember { ManualCopyStorage(context.applicationContext) }
    var enabled by remember { mutableStateOf(storage.enabled()) }
    var open by remember { mutableStateOf(false) }
    Row {
        Checkbox(enabled, { enabled = it; storage.setEnabled(it) })
        Text("Experimental manual iCloud → Google Copy (off by default)", Modifier.padding(top = 12.dp))
    }
    if (enabled) {
        TextButton(enabled = source != null && album != null, onClick = { open = true }) { Text("Copy / Move from this iCloud album") }
        if (album == null) Text("Choose an iCloud album first. Only explicitly selected, loaded items are copied.")
    }
    if (open && source != null && album != null) {
        ManualCopyDialog(source, album, items, storage) { open = false }
    }
}

@Composable
private fun ManualCopyDialog(source: ICloudLibraryClient, album: LibraryAlbum, items: List<LibraryItem>, storage: ManualCopyStorage, close: () -> Unit) {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    val diagnostics = remember { AppDiagnostics.get(context) }
    val previews = remember(source) { GooglePreviewSession(source) }
    DisposableEffect(previews) { onDispose { previews.cache.evictAll() } }
    var batch by remember { mutableStateOf<ManualCopyBatch?>(null) }
    var selected by remember { mutableStateOf(emptySet<String>()) }
    var sourceAccount by remember { mutableStateOf<String?>(null) }
    var destination by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var ready by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("Loading private queue and account identities…") }
    var task by remember { mutableStateOf<Job?>(null) }
    val stopped = remember { AtomicBoolean(false) }
    val cancelled = remember { AtomicBoolean(false) }
    LaunchedEffect(Unit) {
        try {
            batch = withContext(Dispatchers.IO) { storage.load() }
            val accounts = withContext(Dispatchers.IO) { source.accountScope() to AndroidManualCopy.credential(context).accountEmail }
            sourceAccount = accounts.first
            destination = accounts.second
            ready = true
            status = "Select up to 20 loaded items. One original staged at a time, at most 32 MiB each. No automatic background work."
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { status = "Queue or connection unavailable. Reconnect in Settings and reopen. No writes started." }
    }
    fun start() {
        if (busy || !ready || !manualCopyOwner.tryLock()) return
        busy = true; stopped.set(false); cancelled.set(false)
        task = scope.launch {
            var working = batch
            try {
                diagnostics.record(DiagnosticEvent(DiagnosticComponent.LIBRARY, DiagnosticStep.COPY_START))
                withContext(Dispatchers.IO) {
                    val durable = storage.load()
                    check(working?.id == durable?.id)
                    working = durable
                    batch = durable
                    AndroidManualCopy.clearAbandonedStage(context)
                    // Read the CURRENT encrypted imports, not merely the stale browsing client's identity.
                    val repository = ICloudSessionRepository(AndroidKeystoreCredentialStore(context))
                    val currentSession = repository.load() ?: error("Missing source")
                    val currentSource = ICloudLibraryClient(currentSession).accountScope()
                    val credential = AndroidManualCopy.credential(context)
                    check(currentSource == sourceAccount && source.accountScope() == currentSource)
                    check(credential.accountEmail == destination)
                    val existing = working
                    if (existing != null) {
                        check(!existing.cancelled && existing.matchesAccounts(currentSource, credential.accountEmail))
                    } else {
                        working = storage.create(currentSource, credential.accountEmail, album.id, album.title, selected.toList())
                        batch = working
                    }
                    val initial = requireNotNull(working)
                    val port = AndroidManualCopy(context, source, credential, initial,
                        allowed = { !stopped.get() }, progress = { status = it })
                    runManualCopy(initial.entries, port, { index, entry ->
                        val current = requireNotNull(working)
                        val rows = current.entries.toMutableList().also { it[index] = entry }
                        val next = current.update(rows)
                        storage.save(next)
                        working = next
                        batch = next
                    }, paused = { stopped.get() })
                }
                status = "Pass finished. Review individual outcomes below; album membership is not verified."
                diagnostics.record(DiagnosticEvent(DiagnosticComponent.LIBRARY, DiagnosticStep.COPY_FINISHED))
            } catch (e: CancellationException) {
                diagnostics.record(DiagnosticEvent(DiagnosticComponent.LIBRARY, DiagnosticStep.COPY_PAUSED))
                status = "Paused. In-flight writes may be uncertain. Resume is explicit; uncertain uploads only get a presence lookup."
                throw e
            } catch (_: Exception) {
                status = "Stopped safely: verify both connections/accounts, load the original album pages again, then retry. No raw provider error is displayed."
                diagnostics.record(DiagnosticEvent(DiagnosticComponent.LIBRARY, DiagnosticStep.FAILED))
            } finally {
                withContext(NonCancellable + Dispatchers.IO) {
                    try {
                        if (cancelled.get()) working?.let {
                            val next = it.update(cancelled = true)
                            storage.save(next); batch = next
                        }
                        AndroidManualCopy.clearAbandonedStage(context)
                    } catch (_: Exception) { status = "Could not persist cancellation. Queue retained; do not assume cancellation was saved." }
                }
                busy = false
                manualCopyOwner.unlock()
            }
        }
    }
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycle) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_STOP) { stopped.set(true); task?.cancel() }
        }
        lifecycle.lifecycle.addObserver(observer)
        onDispose { lifecycle.lifecycle.removeObserver(observer); stopped.set(true); task?.cancel() }
    }
    Dialog(onDismissRequest = { if (!busy) close() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) {
            LazyColumn(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                item {
                    Text("Manual iCloud → Google", style = MaterialTheme.typography.titleLarge)
                    Text("Source album: ${batch?.albumTitle ?: album.title}")
                    Text("Google destination: ${batch?.destinationAccount ?: destination ?: "Unavailable"}")
                    Text("Copy keeps every iCloud original. Album preservation is requested using a confirmed Google album identity; upload confirmation and album membership confirmation are separate. Membership remains unconfirmed in this prototype.")
                    Button(enabled = false, onClick = {}) { Text("Move — unavailable") }
                    Text("Move requires verified destination bytes, confirmed album membership and safe source deletion. None is authorized by an upload receipt.", style = MaterialTheme.typography.bodySmall)
                    Text(status)
                    if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                }
                val current = batch
                if (current == null) {
                    item { Text("${selected.size} / 20 selected · ${items.size} loaded. Load more in the album browser before selecting if necessary.") }
                    itemsIndexed(items, key = { _, it -> it.id }) { index, item ->
                        Row {
                            Checkbox(item.id in selected, enabled = !busy && (item.id in selected || selected.size < 20), onCheckedChange = { checked ->
                                selected = if (checked) selected + item.id else selected - item.id
                            })
                            Box(Modifier.size(64.dp)) { GooglePreview(previews, item, source = "iCloud") }
                            Text("Item ${index + 1} · ${if (item.video) "Video" else "Photo"} · ${java.util.Date(item.timestampMillis)}", Modifier.padding(top = 12.dp))
                        }
                    }
                    item { Button(enabled = ready && !busy && selected.isNotEmpty(), onClick = { start() }) { Text("Copy selected to Google; request album preservation") } }
                } else {
                    item {
                        if (!current.matchesAccounts(sourceAccount, destination)) Text("Account mismatch or unavailable. Resume blocked. Restore the original source and Google destination connections.", color = MaterialTheme.colorScheme.error)
                        if (current.cancelled) Text("Cancelled. Confirmed / uncertain receipts are retained permanently on this device. No rollback or source deletion.")
                    }
                    itemsIndexed(current.entries) { index, entry ->
                        Text("${index + 1}. ${entry.phase.name.replace('_', ' ')} · ${entry.note}")
                    }
                    item {
                        Button(enabled = ready && !busy && !current.cancelled && current.matchesAccounts(sourceAccount, destination), onClick = { start() }) { Text("Resume / retry safe work") }
                        Text("After reopening, select the same source album and load its pages before retrying reads. Uncertain uploads never reupload, even if no presence match is found.", style = MaterialTheme.typography.bodySmall)
                        TextButton(enabled = !busy, onClick = {
                            if (manualCopyOwner.tryLock()) try {
                                storage.close(); batch = null; selected = emptySet(); status = "Queue closed; historical receipts retained."
                            } catch (_: Exception) { status = "Could not close queue." }
                            finally { manualCopyOwner.unlock() }
                        }) { Text("Close batch; keep receipts") }
                    }
                }
                item {
                    Row {
                        TextButton(enabled = busy, onClick = { stopped.set(true); task?.cancel() }) { Text("Pause") }
                        TextButton(enabled = batch != null, onClick = {
                            if (busy) { cancelled.set(true); stopped.set(true); task?.cancel() }
                            else if (manualCopyOwner.tryLock()) try {
                                storage.load()?.let { storage.save(it.update(cancelled = true)); batch = it.update(cancelled = true) }
                            } catch (_: Exception) { status = "Could not save cancellation; queue retained." }
                            finally { manualCopyOwner.unlock() }
                        }) { Text("Cancel batch") }
                        TextButton(enabled = !busy, onClick = close) { Text("Close") }
                    }
                }
            }
        }
    }
}
