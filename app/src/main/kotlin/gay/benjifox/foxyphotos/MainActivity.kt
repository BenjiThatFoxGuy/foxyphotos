package gay.benjifox.foxyphotos

import android.Manifest
import android.content.Context
import android.content.Intent
import androidx.compose.material3.Switch
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.activity.compose.BackHandler
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
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

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@androidx.compose.runtime.Composable
private fun FoxyPhotosApp() {
    val context = LocalContext.current
    val updater = remember { AppUpdater.get(context) }
    val updateState by updater.state.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { updater.launchCheck() }
    val preferences = remember {
        context.getSharedPreferences("foxyphotos.backup-settings", Context.MODE_PRIVATE)
    }
    val credentialStore = remember { AndroidKeystoreCredentialStore(context.applicationContext) }
    val googleAuthClient = remember { GoogleAuthClient() }
    val diagnostics = remember { AppDiagnostics.get(context) }
    var icloudEnabled by remember { mutableStateOf(preferences.getBoolean("experimental-icloud-library", false)) }
    var combinedEnabled by remember { mutableStateOf(preferences.getBoolean("experimental-google-library", false)) }
    var showLogs by remember { mutableStateOf(false) }
    var screen by rememberSaveable { mutableStateOf("main") }
    val gallerySavedState = androidx.compose.runtime.saveable.rememberSaveableStateHolder()
    val galleryGridState = androidx.compose.foundation.lazy.grid.rememberLazyGridState()
    var mode by remember { mutableStateOf(preferences.getString("app-mode", "Simple") ?: "Simple") }
    fun selectMode(value: String) {
        mode = value
        preferences.edit().putString("app-mode", value).apply()
        BackupScheduling.refresh(context)
    }
    val scanner = remember { MediaStoreFolderScanner(context.contentResolver) }
    val scope = rememberCoroutineScope()
    var credentialInput by remember { mutableStateOf("") }
    var onboarding by rememberSaveable { mutableStateOf(!preferences.getBoolean("onboarding-completed", false)) }
    var onboardingStep by rememberSaveable { mutableStateOf(0) }
    var accountLoaded by remember { mutableStateOf(false) }
    var notificationRequested by remember { mutableStateOf(preferences.getBoolean("notification-permission-requested", false)) }
    var mediaGranted by remember { mutableStateOf(hasMediaAccess(context)) }
    var configuredAccount by remember { mutableStateOf<String?>(null) }
    var setupError by remember { mutableStateOf<String?>(null) }
    var folders by remember { mutableStateOf<List<MediaFolder>>(emptyList()) }
    var selectedFolders by remember { mutableStateOf(preferences.getStringSet("enabled-folders", emptySet()) ?: emptySet()) }
    val backupState by BackupRuntime.state.collectAsStateWithLifecycle()
    val isRunning = backupState.active
    val backupCounts = backupState.counts

    var activityStatus by remember { mutableStateOf("Ready to connect") }
    var scanError by remember { mutableStateOf<String?>(null) }
    var savingCredential by remember { mutableStateOf(false) }
    fun notificationsAllowed() = NotificationManagerCompat.from(context).areNotificationsEnabled() &&
        (Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(context,
            Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)
    var notificationsEnabled by remember { mutableStateOf(notificationsAllowed()) }
    val notificationPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        notificationsEnabled = notificationsAllowed()
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                updater.onResume()
                notificationsEnabled = notificationsAllowed()
                mediaGranted = hasMediaAccess(context)
                BackupScheduling.refresh(context, false)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(Unit) {
        val savedCredential = credentialStore.loadCredential(CredentialReference("google-native"))
        if (savedCredential != null) {
            try {
                val parsed = GoogleCredentialParser.parse(savedCredential.decodeToString())
                configuredAccount = parsed.accountEmail
                activityStatus = "Ready"
                // Migrate only installs that predate onboarding, not unfinished first runs.
                if (!preferences.contains("onboarding-started") && !preferences.contains("onboarding-completed")) {
                    preferences.edit().putBoolean("onboarding-completed", true).apply()
                    onboarding = false
                }
            } catch (_: IllegalArgumentException) {
                activityStatus = "Reconnect Google Photos"
            } finally {
                savedCredential.fill(0)
            }
        }
        accountLoaded = true
        if (onboarding) preferences.edit().putBoolean("onboarding-started", true).apply()
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
                if (!preferences.contains("enabled-folders") && folders.isNotEmpty()) {
                    persistFolders(folders.map(MediaFolder::relativePath).toSet())
                }
                activityStatus = "${folders.size} folders · ${folders.sumOf(MediaFolder::itemCount)} items"
            } catch (error: SecurityException) {
                scanError = "Photo permission is required to scan folders."
                activityStatus = "Waiting for photo permission"
            } catch (_: IOException) {
                scanError = "Could not load folders. Reopen to try again."
            }
        }
    }
    fun runSelectedFolderBackup() {
        if (!isRunning) BackupRuntime.command(context, BackupService.START)
    }

    val requiredPermissions = if (Build.VERSION.SDK_INT >= 34) {
        arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
    } else if (Build.VERSION.SDK_INT >= 33) {
        arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
    } else {
        arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { granted ->
        mediaGranted = hasMediaAccess(context)
        if (mediaGranted) scanFolders() else scanError = "Photo access is off. You can try again or use Settings."
    }

    val credentialForm: @androidx.compose.runtime.Composable () -> Unit = {
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
                                    if (onboarding) onboardingStep = 2
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
    if (!accountLoaded) return
    if (onboarding) {
        BackHandler(enabled = onboardingStep > 0) { if (!savingCredential) onboardingStep-- }
        OnboardingScreen(
            step = onboardingStep,
            mode = mode,
            onMode = ::selectMode,
            account = configuredAccount,
            mediaGranted = mediaGranted,
            notificationsEnabled = notificationsEnabled,
            notificationRequested = notificationRequested,
            busy = savingCredential,
            mediaError = scanError,
            credentialForm = credentialForm,
            onBack = { onboardingStep-- },
            onContinue = { onboardingStep++ },
            onMedia = { permissionLauncher.launch(requiredPermissions) },
            onMediaSettings = {
                context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    android.net.Uri.parse("package:${context.packageName}")))
            },
            onNotifications = {
                if (Build.VERSION.SDK_INT >= 33 && !notificationRequested) {
                    notificationRequested = true
                    preferences.edit().putBoolean("notification-permission-requested", true).apply()
                    notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                } else {
                    context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                        .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
                }
            },
            onDone = {
                preferences.edit().putBoolean("onboarding-completed", true).apply()
                onboarding = false
                credentialInput = ""
            },
        )
        return
    }

    if (showLogs) {
        DiagnosticLogViewer(diagnostics, onClose = { showLogs = false })
        return
    }

    BackHandler(enabled = screen != "main") { screen = if (screen == "folders" || screen == "connect") "settings" else "main" }
    LaunchedEffect(screen) { if (screen == "folders") scanFolders() }
    val controls: @androidx.compose.runtime.Composable () -> Unit = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(if (backupState.phase == BackupPhase.IDLE) activityStatus else backupState.phase.title,
                style = MaterialTheme.typography.titleMedium)
            if (backupState.phase != BackupPhase.IDLE) Text(
                "${backupCounts.uploaded} uploaded · ${backupCounts.alreadyPresent} present · ${backupCounts.failed} failed · ${backupCounts.uncertain} uncertain",
                style = MaterialTheme.typography.bodySmall)
            if (configuredAccount == null) {
                TextButton(onClick = { screen = "connect" }) { Text("Connect Google Photos") }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Button(enabled = backupState.phase != BackupPhase.PAUSING && backupState.phase != BackupPhase.CANCELLING &&
                        (isRunning || selectedFolders.isNotEmpty()), onClick = {
                        if (isRunning) BackupRuntime.command(context, BackupService.PAUSE) else { AndroidBackupPolicy(context).resume(); runSelectedFolderBackup() }
                    }) { Text(when {
                        backupState.phase == BackupPhase.PAUSING -> "Pausing…"
                        backupState.phase == BackupPhase.CANCELLING -> "Cancelling…"
                        isRunning -> "Pause"
                        backupState.phase == BackupPhase.PAUSED -> "Resume"
                        else -> "Back up"
                    }) }
                    if (isRunning) TextButton(enabled = backupState.phase != BackupPhase.CANCELLING,
                        onClick = { BackupRuntime.command(context, BackupService.CANCEL) }) { Text("Cancel") }
                    Text("${selectedFolders.size} folders", style = MaterialTheme.typography.bodySmall)
                }
            }
            BackupPolicyControls()
        }
    }
    Scaffold { padding ->
        Column(Modifier.fillMaxSize().padding(padding).imePadding()) {
            if (screen == "main" && mode == "Modern") {
                androidx.compose.material3.TopAppBar(
                    windowInsets = androidx.compose.foundation.layout.WindowInsets(0, 0, 0, 0),
                    title = {
                        Column {
                            Text("FoxyPhotos", maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                if (backupState.phase == BackupPhase.IDLE) {
                                    if (configuredAccount == null) "Not connected" else "Local library"
                                } else backupState.phase.title,
                                style = MaterialTheme.typography.labelSmall,
                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                        }
                    },
                    actions = {
                        if (combinedEnabled) TextButton(onClick = { screen = "combined" }) { Text("Combined") }
                        if (icloudEnabled) TextButton(onClick = { screen = "icloud-library" }) { Text("iCloud") }
                        ProfileControl(configuredAccount, onSettings = { screen = "settings" }, backupControls = {
                            if (updateState.release != null) TextButton(onClick = { screen = "settings" }) { Text("Update available") }
                            if (backupState.total > 0) {
                                val processed = backupCounts.uploaded + backupCounts.alreadyPresent + backupCounts.failed + backupCounts.uncertain
                                Text("$processed of ${backupState.total} processed", style = MaterialTheme.typography.bodySmall)
                                androidx.compose.material3.LinearProgressIndicator(
                                    progress = { (processed.toFloat() / backupState.total).coerceIn(0f, 1f) },
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                            controls()
                        })
                    },
                )
            } else Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                if (screen != "main") TextButton(onClick = { screen = if (screen == "folders" || screen == "connect") "settings" else "main" }) { Text("Back") }
                Text(when (screen) { "settings" -> "Settings"; "folders" -> "Backup folders"; "connect" -> "Connect"; "combined" -> "Combined library"; "icloud-library" -> "iCloud library"; else -> "FoxyPhotos" },
                    modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                if (screen == "main") {
                    if (combinedEnabled) TextButton(onClick = { screen = "combined" }) { Text("Combined") }
                        if (icloudEnabled) TextButton(onClick = { screen = "icloud-library" }) { Text("iCloud") }
                    ProfileControl(configuredAccount)
                    IconButton(onClick = { screen = "settings" }, modifier = Modifier.semantics { contentDescription = "Settings" }) { Text(if (updateState.release != null) "⚙ •" else "⚙", style = MaterialTheme.typography.headlineMedium) }
                }
            }
            if (screen == "icloud-library" && icloudEnabled) {
                Box(Modifier.weight(1f)) { CombinedLibrary(onPermission = { permissionLauncher.launch(requiredPermissions) }, includeICloud = true) }
            } else if (screen == "combined" && combinedEnabled) {
                Box(Modifier.weight(1f)) { CombinedLibrary(onPermission = { permissionLauncher.launch(requiredPermissions) }) }
            } else if (screen == "main" && mode == "Modern") {
                Box(Modifier.weight(1f)) {
                    gallerySavedState.SaveableStateProvider("gallery") {
                        LocalGallery(Modifier.fillMaxSize(), gridState = galleryGridState, onPermission = { permissionLauncher.launch(requiredPermissions) })
                    }
                }
            } else LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                when (screen) {
                    "settings" -> {
                        item { UpdateControls(updater) }
                        item {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("Experimental local + Google + iCloud library", Modifier.weight(1f))
                                Switch(icloudEnabled, modifier = Modifier.semantics { contentDescription = "Enable experimental iCloud library" }, onCheckedChange = {
                                    icloudEnabled = it
                                    preferences.edit().putBoolean("experimental-icloud-library", it).apply()
                                })
                            }
                            if (icloudEnabled) {
                                TextButton(onClick = { screen = "icloud-library" }) { Text("Open iCloud combined library") }
                                ICloudSessionSetup()
                            }
                        }
                        item { Text("App mode", style = MaterialTheme.typography.titleMedium); ModeChoices(mode, ::selectMode) }
                        item {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("Experimental local + Google library", Modifier.weight(1f))
                                Switch(combinedEnabled, modifier = Modifier.semantics { contentDescription = "Enable experimental combined library" }, onCheckedChange = {
                                    combinedEnabled = it
                                    preferences.edit().putBoolean("experimental-google-library", it).apply()
                                })
                            }
                            Text("Read-only Google pages and previews. Off by default; uses your saved Google connection.", style = MaterialTheme.typography.bodySmall)
                            if (combinedEnabled) TextButton(onClick = { screen = "combined" }) { Text("Open combined library") }
                        }
                        item { BackupConditionSettings() }
                        item { TextButton(onClick = { screen = "folders" }) { Text("Backup folders") } }
                        item { TextButton(onClick = { showLogs = true }) { Text("View logs...") } }
                        item { TextButton(onClick = { onboardingStep = 0; onboarding = true }) { Text("Setup") } }
                        item { TextButton(onClick = { screen = "connect" }) { Text(if (configuredAccount == null) "Connect Google Photos" else "Reconnect Google Photos") } }
                    }
                    "connect" -> item { credentialForm() }
                    "folders" -> {
                        item {
                            Text("${selectedFolders.size} selected")
                            TextButton(enabled = selectedFolders.isNotEmpty(), onClick = { persistFolders(emptySet()) }) { Text("Clear selection") }
                            if (!mediaGranted) TextButton(onClick = { permissionLauncher.launch(requiredPermissions) }) { Text("Allow photos access") }
                            scanError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                            if (folders.isEmpty() && scanError == null) Text("No folders available")
                        }
                        items(folders, key = { it.relativePath }) { folder ->
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(folder.relativePath in selectedFolders, onCheckedChange = { checked ->
                                    persistFolders(if (checked) selectedFolders + folder.relativePath else selectedFolders - folder.relativePath)
                                })
                                Column(Modifier.weight(1f)) {
                                    Text(folder.relativePath, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    Text("${folder.itemCount} items", style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }
                    else -> {
                        item { Text("Simple", style = MaterialTheme.typography.labelLarge); controls() }
                        if (!notificationsEnabled) item {
                            TextButton(onClick = { context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)) }) { Text("Enable notifications") }
                        }
                    }
                }
            }
        }
    }
}
