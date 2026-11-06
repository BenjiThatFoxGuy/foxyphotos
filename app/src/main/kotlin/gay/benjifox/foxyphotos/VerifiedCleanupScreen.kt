package gay.benjifox.foxyphotos

import android.Manifest
import android.app.Activity
import android.content.ContentValues
import android.os.Build
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import gay.benjifox.foxyphotos.core.backup.*
import gay.benjifox.foxyphotos.core.model.*
import gay.benjifox.foxyphotos.core.security.AndroidKeystoreCredentialStore
import gay.benjifox.foxyphotos.provider.contract.CredentialReference
import gay.benjifox.foxyphotos.provider.google.*
import kotlinx.coroutines.*

private data class CleanupRow(val local: CleanupLocal, val verification: CleanupVerification?, val status: String)

@Composable
internal fun VerifiedCleanupScreen() {
    val context = LocalContext.current
    val storage = remember { CleanupStorage(context) }
    val scope = rememberCoroutineScope()
    var enabled by remember { mutableStateOf(storage.enabled()) }
    var folders by remember { mutableStateOf(storage.folders()) }
    var available by remember { mutableStateOf(emptyList<String>()) }
    var offset by remember { mutableIntStateOf(0) }
    var candidateCount by remember { mutableIntStateOf(0) }
    var rows by remember { mutableStateOf(emptyList<CleanupRow>()) }
    var status by remember { mutableStateOf("Choose cleanup folders independently of backup. Preview downloads up to 20 earlier uploads, at most 256 MiB each. Nothing is removed by preview.") }
    var job by remember { mutableStateOf<Job?>(null) }
    var confirm by remember { mutableStateOf<CleanupRow?>(null) }
    var pending by remember { mutableStateOf<CleanupRow?>(null) }
    val client = remember {
        GoogleLibraryClient(credential = {
            withContext(Dispatchers.IO) {
                val bytes = AndroidKeystoreCredentialStore(context).loadCredential(CredentialReference("google-native"))
                    ?: throw GoogleAuthenticationException("Connect Google Photos first")
                try { GoogleCredentialParser.parse(bytes.decodeToString()) } finally { bytes.fill(0) }
            }
        })
    }
    fun event(step: DiagnosticStep) = AppDiagnostics.get(context).safelyRecord(DiagnosticEvent(DiagnosticComponent.CLEANUP, step))
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        status = if (it) "Original access granted. New successful uploads can record cleanup evidence." else "Original access denied. Files are preserved."
    }
    val authorization = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        val row = pending
        pending = null
        if (result.resultCode != Activity.RESULT_OK || row?.verification == null) {
            status = "Authorization cancelled. Files preserved."
        } else {
            job = scope.launch {
                try {
                    status = "Rechecking original and current cleanup authorization…"
                    val v = row.verification
                    // Download again after system authorization; auth failures never reach local mutation.
                    executeVerifiedCleanup(v,
                        remoteHash = { client.originalHash(v.upload.remoteKey, v.upload.size) },
                        recheck = { storage.recheck(row.local.uri, v) },
                        trash = { withContext(Dispatchers.IO) {
                        currentCoroutineContext().ensureActive()
                        val local = storage.snapshot(row.local.uri)
                        check(local.identity == v.upload.localIdentity && storage.enabled() && local.folder in storage.folders())
                        val changed = context.contentResolver.update(row.local.uri,
                            ContentValues().apply { put(MediaStore.MediaColumns.IS_TRASHED, 1) },
                            "generation_modified = ? AND _size = ? AND is_pending = 0 AND is_trashed = 0",
                            arrayOf(local.generation.toString(), local.size.toString()))
                        check(changed == 1)
                    } })
                    rows = rows.filterNot { it.local.uri == row.local.uri }
                    status = "Moved one verified local item to device trash. Cloud copy unchanged."
                    event(DiagnosticStep.CLEANUP_TRASHED)
                } catch (cancelled: CancellationException) { status = "Cancelled. No further cleanup."; throw cancelled }
                catch (_: Exception) { status = "Cleanup could not be safely completed. Review device trash if interrupted; all other items preserved."; event(DiagnosticStep.FAILED) }
                finally { job = null }
            }
        }
    }
    LaunchedEffect(Unit) {
        try { if (Build.VERSION.SDK_INT >= 30) available = storage.localItems().map { it.second }.distinct().sorted() }
        catch (_: Exception) { status = "Photo access unavailable. Grant photo access from the library first." }
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Text("Verified local cleanup", style = MaterialTheme.typography.titleLarge)
            Row {
                Text("Enable experimental cleanup", Modifier.weight(1f))
                Switch(enabled, modifier = Modifier.semantics { contentDescription = "Enable experimental cleanup" }, onCheckedChange = {
                    enabled = it; storage.prefs.edit().putBoolean("enabled", it).commit()
                    job?.cancel(); rows = emptyList()
                })
            }
            Text("Only exact original-byte matches qualify. Transformed Google downloads stay on this device. Trash is handled by Android; no cloud deletion.")
            TextButton(onClick = { permission.launch(Manifest.permission.ACCESS_MEDIA_LOCATION) }) { Text("Allow original media access") }
            if (Build.VERSION.SDK_INT < 30) Text("Cleanup requires Android 11 or later. Android 10 files are preserved.")
        }
        items(available, key = { it }) { folder ->
            Row {
                Checkbox(folder in folders, enabled = job == null && pending == null, onCheckedChange = { checked ->
                    folders = if (checked) folders + folder else folders - folder
                    storage.prefs.edit().putStringSet("folders", folders).commit(); rows = emptyList(); offset = 0; candidateCount = 0
                })
                Text(folder)
            }
        }
        item {
            Text(status)
            Button(enabled = enabled && folders.isNotEmpty() && job == null && pending == null && Build.VERSION.SDK_INT >= 30, onClick = {
                rows = emptyList()
                job = scope.launch {
                    val run = java.util.UUID.randomUUID().toString()
                    event(DiagnosticStep.CLEANUP_PREVIEW)
                    try {
                        val media = storage.localItems().filter { it.second in storage.folders() }
                        val allCandidates = withContext(Dispatchers.IO) {
                            val all = media.mapNotNull { m -> storage.evidence(m.first)?.takeIf { storage.ended(it.uploadRun) && VerifiedCleanup.candidate(it, run, System.currentTimeMillis()) }?.let { m.first to it } }
                            all
                        }
                        candidateCount = allCandidates.size
                        val candidates = allCandidates.drop(offset).take(20)
                        for ((uri, evidence) in candidates) {
                            currentCoroutineContext().ensureActive()
                            status = "Verifying ${rows.size + 1} of ${candidates.size}…"
                            val local = try { withContext(Dispatchers.IO) { storage.snapshot(uri) } } catch (_: Exception) { continue }
                            var verification: CleanupVerification? = null
                            var label = "Preserved: changed, transformed or unavailable original."
                            try {
                                val v = CleanupVerification(evidence, run, System.currentTimeMillis())
                                check(storage.recheck(uri, v))
                                check(client.originalHash(evidence.remoteKey, evidence.size) == evidence.sha256)
                                check(storage.recheck(uri, v))
                                verification = v; label = "Exact original match · ready for review (10 minutes)"
                            } catch (cancelled: CancellationException) { throw cancelled }
                            catch (_: GoogleAuthenticationException) { label = "Preserved: reconnect Google Photos." }
                            catch (_: Exception) { }
                            rows = rows + CleanupRow(local, verification, label)
                        }
                        status = if (candidates.isEmpty()) "No recent earlier-run upload evidence. Allow original access, back up new items, then preview here. Old upload receipts and presence checks cannot qualify." else "Preview complete. Review each item before moving it to trash. Use the next page to review more candidates."
                    } catch (cancelled: CancellationException) { rows = emptyList(); status = "Cancelled. Files preserved."; throw cancelled }
                    catch (_: Exception) { rows = emptyList(); status = "Preview unavailable. Files and backup queue preserved."; event(DiagnosticStep.FAILED) }
                    finally { job = null }
                }
            }) { Text("Verify earlier uploads") }
            if (candidateCount > offset + 20) TextButton(enabled = job == null && pending == null, onClick = {
                offset += 20; rows = emptyList(); status = "Next candidate page selected. Press Verify earlier uploads."
            }) { Text("Next 20 candidates") }
            if (offset > 0) TextButton(enabled = job == null && pending == null, onClick = {
                offset = 0; rows = emptyList(); status = "First candidate page selected. Press Verify earlier uploads."
            }) { Text("Start over") }
            if (job != null) TextButton(onClick = { job?.cancel() }) { Text("Cancel") }
        }
        items(rows, key = { it.local.uri.toString() }) { row ->
            Text(row.local.name)
            Text(row.status)
            if (row.verification != null) TextButton(enabled = enabled && job == null && pending == null, onClick = { confirm = row }) { Text("Move local item to trash…") }
        }
    }
    confirm?.let { row ->
        AlertDialog(onDismissRequest = { confirm = null }, title = { Text("Move verified local item to device trash?") },
            text = { Text("${row.local.name}\nAndroid will request write access. After approval FoxyPhotos downloads and checks the original again, then moves this local item to trash. Restore using your device gallery before trash expires.") },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("Cancel") } },
            confirmButton = { TextButton(onClick = {
                confirm = null
                try {
                    pending = row
                    val request = if (Build.VERSION.SDK_INT >= 30) MediaStore.createWriteRequest(context.contentResolver, listOf(row.local.uri))
                        else error("System trash authorization unavailable")
                    authorization.launch(IntentSenderRequest.Builder(request.intentSender).build())
                    event(DiagnosticStep.CLEANUP_AUTHORIZE)
                } catch (_: Exception) { pending = null; status = "System authorization unavailable. File preserved." }
            }) { Text("Continue to Android") } })
    }
}
