package gay.benjifox.foxyphotos

import android.content.ClipData
import android.content.Intent
import androidx.core.content.FileProvider
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun DiagnosticLogViewer(diagnostics: AppDiagnostics, onClose: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var snapshot by remember { mutableStateOf(DiagnosticSnapshot()) }
    var status by remember { mutableStateOf("loading logs") }
    var confirmClear by remember { mutableStateOf(false) }
    var exportText by remember { mutableStateOf<String?>(null) }
    var exporting by remember { mutableStateOf(false) }
    var sharing by remember { mutableStateOf(false) }

    suspend fun refresh() {
        try {
            snapshot = diagnostics.snapshot()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            status = "could not read diagnostic storage"
        }
    }

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        val text = exportText
        exportText = null
        if (uri == null || text == null) {
            exporting = false
            status = "export cancelled"
        } else scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val stream = context.contentResolver.openOutputStream(uri, "wt")
                        ?: throw java.io.IOException()
                    stream.bufferedWriter(Charsets.UTF_8).use { it.write(text) }
                }
                status = "logs exported to your chosen document"
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                status = "could not export logs; the destination may contain a partial file"
            } finally { exporting = false }
        }
    }

    LaunchedEffect(diagnostics) {
        while (true) {
            try {
                refresh()
                if (status == "loading logs") status = "live updates every second; oldest events first"
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                status = "could not read diagnostic storage"
            }
            delay(1000)
        }
    }
    BackHandler(onBack = onClose)

    Scaffold { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(onClick = onClose) { Text("back") }
                Text("diagnostic logs", style = MaterialTheme.typography.titleLarge)
            }
            Text(DIAGNOSTIC_PRIVACY_NOTICE, style = MaterialTheme.typography.bodySmall)
            Text("newest 400 events, up to 256 kib. logging is best effort; older events may be missing.", style = MaterialTheme.typography.bodySmall)
            Text(status, style = MaterialTheme.typography.bodySmall)
            if (!snapshot.storageHealthy) Text("storage unavailable; logs are currently memory-only. clear could not be persisted if storage is still unavailable.", color = MaterialTheme.colorScheme.error)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { scope.launch { refresh() } }) { Text("refresh") }
                Button(onClick = { confirmClear = true }) { Text("clear") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(enabled = !exporting, onClick = {
                    exporting = true
                    scope.launch {
                        try {
                            exportText = diagnostics.exportText()
                            exportLauncher.launch("foxyphotos-diagnostics.txt")
                        } catch (cancelled: CancellationException) {
                            exporting = false
                            throw cancelled
                        } catch (_: Exception) {
                            exportText = null
                            exporting = false
                            status = "could not prepare log export"
                        }
                    }
                }) { Text("export") }
                Button(enabled = !sharing, onClick = {
                    sharing = true
                    scope.launch {
                        try {
                            val text = diagnostics.exportText()
                            val file = withContext(Dispatchers.IO) {
                                DiagnosticShareFiles.create(context.cacheDir, text)
                            }
                            val uri = FileProvider.getUriForFile(
                                context, "${context.packageName}.diagnostics.fileprovider", file,
                            )
                            val attachment = ClipData.newRawUri("diagnostic logs", uri)
                            val send = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_STREAM, uri)
                                clipData = attachment
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            context.startActivity(Intent.createChooser(send, "Share diagnostic logs").apply {
                                clipData = attachment
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            })
                            status = "share sheet opened with temporary log attachment"
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            status = "could not share logs"
                        } finally { sharing = false }
                    }
                }) { Text("share") }
            }
            Text("share creates a temporary cache attachment, not a saved document.", style = MaterialTheme.typography.bodySmall)
            HorizontalDivider()
            if (snapshot.lines.isEmpty()) Text("no diagnostic events yet. check for updates or run a backup, then check here.")
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                itemsIndexed(snapshot.lines) { _, line ->
                    SelectionContainer {
                        Text(line, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                    }
                    HorizontalDivider()
                }
            }
        }
    }
    if (confirmClear) AlertDialog(
        onDismissRequest = { confirmClear = false },
        title = { Text("clear diagnostic logs?") },
        text = { Text("this clears retained app logs, not exported documents. a running upload can add new events immediately.") },
        confirmButton = {
            TextButton(onClick = {
                confirmClear = false
                scope.launch {
                    try {
                        status = if (diagnostics.clear()) "retained logs cleared" else "memory cleared, but stored logs could not be cleared"
                        refresh()
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        status = "could not clear diagnostic storage"
                    }
                }
            }) { Text("clear logs") }
        },
        dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("cancel") } },
    )
}
