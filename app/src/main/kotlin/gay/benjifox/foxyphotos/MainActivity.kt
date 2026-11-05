package gay.benjifox.foxyphotos

import android.Manifest
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.imePadding

import androidx.compose.material3.TextButton
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.runtime.DisposableEffect
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.IconButton
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import gay.benjifox.foxyphotos.core.media.MediaFolder
import gay.benjifox.foxyphotos.core.media.MediaStoreFolderScanner
import gay.benjifox.foxyphotos.core.security.AndroidKeystoreCredentialStore
import gay.benjifox.foxyphotos.provider.contract.CredentialReference
import gay.benjifox.foxyphotos.provider.google.GoogleAuthClient
import gay.benjifox.foxyphotos.provider.google.GoogleAuthenticationException
import gay.benjifox.foxyphotos.provider.google.GoogleCredentialParser
import java.io.IOException
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme(colorScheme = darkColorScheme()) { FoxyPhotosApp() } }
    }
}

@androidx.compose.runtime.Composable
private fun FoxyPhotosApp() {
    val context = LocalContext.current
    val preferences = remember {
        context.getSharedPreferences("foxyphotos.backup-settings", Context.MODE_PRIVATE)
    }
    val credentialStore = remember { AndroidKeystoreCredentialStore(context.applicationContext) }
    val googleAuthClient = remember { GoogleAuthClient() }
    val diagnostics = remember { AppDiagnostics.get(context) }
    var showLogs by remember { mutableStateOf(false) }
    var menuExpanded by remember { mutableStateOf(false) }
    val scanner = remember { MediaStoreFolderScanner(context.contentResolver) }
    val scope = rememberCoroutineScope()
    var credentialInput by remember { mutableStateOf("") }
    var configuredAccount by remember { mutableStateOf<String?>(null) }
    var setupError by remember { mutableStateOf<String?>(null) }
    var folders by remember { mutableStateOf<List<MediaFolder>>(emptyList()) }
    var selectedFolders by remember { mutableStateOf(preferences.getStringSet("enabled-folders", emptySet()) ?: emptySet()) }
    val backupState by BackupRuntime.state.collectAsStateWithLifecycle()
    val isRunning = backupState.active
    val backupCounts = backupState.counts

    var activityStatus by remember { mutableStateOf("Not connected") }
    var scanError by remember { mutableStateOf<String?>(null) }
    var savingCredential by remember { mutableStateOf(false) }
    var notificationsEnabled by remember { mutableStateOf(NotificationManagerCompat.from(context).areNotificationsEnabled()) }
    val notificationPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        notificationsEnabled = NotificationManagerCompat.from(context).areNotificationsEnabled()
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                notificationsEnabled = NotificationManagerCompat.from(context).areNotificationsEnabled()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED &&
            !preferences.getBoolean("notification-permission-requested", false)) {
            preferences.edit().putBoolean("notification-permission-requested", true).apply()
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    LaunchedEffect(Unit) {
        val savedCredential = credentialStore.loadCredential(CredentialReference("google-native"))
        if (savedCredential != null) {
            try {
                val parsed = GoogleCredentialParser.parse(savedCredential.decodeToString())
                configuredAccount = parsed.accountEmail
                activityStatus = "Ready"
            } catch (_: IllegalArgumentException) {
                activityStatus = "Reconnect Google Photos"
            } finally {
                savedCredential.fill(0)
            }
        }
    }

    fun persistFolders(value: Set<String>) {
        selectedFolders = value
        preferences.edit().putStringSet("enabled-folders", value).apply()
    }
    fun scanFolders() {
        scope.launch {
            try {
                activityStatus = "Scanning folders…"
                folders = scanner.scan()
                scanError = null
                if (selectedFolders.isEmpty() && folders.isNotEmpty()) {
                    persistFolders(folders.map(MediaFolder::relativePath).toSet())
                }
                activityStatus = "${folders.size} folders · ${folders.sumOf(MediaFolder::itemCount)} items"
            } catch (error: SecurityException) {
                scanError = "Photo permission is required to scan folders."
                activityStatus = "Waiting for photo permission"
            }
        }
    }
    fun runSelectedFolderBackup() {
        if (!isRunning) BackupRuntime.command(context, BackupService.START)
    }

    val requiredPermissions = if (Build.VERSION.SDK_INT >= 33) {
        arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
    } else {
        arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { granted ->
        if (granted.values.any { it }) scanFolders() else scanError = "Photo permission was not granted."
    }

    if (showLogs) {
        DiagnosticLogViewer(diagnostics, onClose = { showLogs = false })
        return
    }

    Scaffold { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().background(Color(0xFF101015)).padding(padding).imePadding(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "header") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Box {
                    IconButton(onClick = { menuExpanded = true }) {
                        val dotColor = MaterialTheme.colorScheme.onSurface
                        Canvas(Modifier.size(24.dp).semantics { contentDescription = "More options" }) {
                            for (fraction in listOf(0.25f, 0.5f, 0.75f)) {
                                drawCircle(dotColor, radius = 1.8.dp.toPx(), center = Offset(size.width / 2, size.height * fraction))
                            }
                        }
                    }
                    DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                        DropdownMenuItem(text = { Text("View logs...") }, onClick = {
                            menuExpanded = false
                            showLogs = true
                        })
                    }
                }
                Text("FoxyPhotos", style = MaterialTheme.typography.headlineMedium)
            }
            Text(
                configuredAccount ?: "Connect Google Photos",
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            }
            if (!notificationsEnabled) {
                item(key = "notifications") {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Notifications off", style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = {
                            context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
                        }) { Text("Enable") }
                    }
                }
            }
            if (configuredAccount != null) {
                item(key = "status") {
                    Text(if (backupState.phase == BackupPhase.IDLE) activityStatus else backupState.phase.title,
                        style = MaterialTheme.typography.titleMedium)
                    if (backupState.phase != BackupPhase.IDLE) {
                        Text("${backupCounts.uploaded} uploaded · ${backupCounts.alreadyPresent} present · ${backupCounts.failed} failed · ${backupCounts.uncertain} uncertain",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }

            if (configuredAccount == null) {
                item(key = "credentials") {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Google Photos credential", style = MaterialTheme.typography.titleMedium)
                        OutlinedTextField(
                            value = credentialInput,
                            onValueChange = { credentialInput = it; setupError = null },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("Google Photos credential") },
                            isError = setupError != null,
                            minLines = 2,
                            maxLines = 4,
                        )
                        Button(enabled = credentialInput.isNotBlank() && !savingCredential, onClick = {
                            savingCredential = true
                            scope.launch {
                                val secret = credentialInput.encodeToByteArray()
                                try {
                                    val parsed = GoogleCredentialParser.parse(credentialInput)
                                    googleAuthClient.exchange(parsed)
                                    credentialStore.saveCredential(CredentialReference("google-native"), secret)
                                    configuredAccount = parsed.accountEmail
                                    activityStatus = "Ready"
                                    credentialInput = ""
                                } catch (error: GoogleAuthenticationException) {
                                    setupError = error.message ?: "Google could not validate that credential."
                                } catch (_: IOException) {
                                    setupError = "Could not reach Google. Check your connection and try again."
                                } catch (error: IllegalArgumentException) {
                                    setupError = error.message ?: "That credential is not valid."
                                } finally {
                                    secret.fill(0)
                                    savingCredential = false
                                }
                            }
                        }) { Text(if (savingCredential) "Saving…" else "Save") }
                        setupError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    }
                }
                }
            } else {
                item(key = "controls") {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Button(enabled = backupState.phase != BackupPhase.PAUSING && backupState.phase != BackupPhase.CANCELLING &&
                            (isRunning || selectedFolders.isNotEmpty()), onClick = {
                            if (isRunning) BackupRuntime.command(context, BackupService.PAUSE)
                            else runSelectedFolderBackup()
                        }) { Text(when {
                            backupState.phase == BackupPhase.PAUSING -> "Pausing…"
                            backupState.phase == BackupPhase.CANCELLING -> "Cancelling…"
                            isRunning -> "Pause"
                            backupState.phase == BackupPhase.PAUSED -> "Resume"
                            else -> "Back up"
                        }) }
                        if (isRunning) {
                            TextButton(enabled = backupState.phase != BackupPhase.CANCELLING,
                                onClick = { BackupRuntime.command(context, BackupService.CANCEL) }) { Text("Cancel") }
                        }
                        Text("${selectedFolders.size} selected", style = MaterialTheme.typography.bodySmall)
                    }
                }
                item(key = "folder-actions") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = {
                        val permitted = requiredPermissions.any {
                            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
                        }
                        if (permitted) scanFolders() else permissionLauncher.launch(requiredPermissions)
                    }) { Text(if (folders.isEmpty()) "Scan folders" else "Rescan") }
                    TextButton(enabled = selectedFolders.isNotEmpty(), onClick = { persistFolders(emptySet()) }) {
                        Text("Clear selection")
                    }
                }
                scanError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                Text("Folders", style = MaterialTheme.typography.titleMedium)
                }
                if (folders.isEmpty()) {
                    item(key = "empty") { Text("No folders loaded") }
                } else {
                    items(folders, key = { "folder:${it.relativePath}" }) { folder ->
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(
                                checked = folder.relativePath in selectedFolders,
                                onCheckedChange = { checked ->
                                    persistFolders(if (checked) selectedFolders + folder.relativePath else selectedFolders - folder.relativePath)
                                },
                            )
                            Column(Modifier.weight(1f)) {
                                Text(folder.relativePath, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Text("${folder.itemCount} items", style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        }
    }
}
