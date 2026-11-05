package gay.benjifox.foxyphotos

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import gay.benjifox.foxyphotos.core.database.SharedPreferencesUploadedContentLedger
import gay.benjifox.foxyphotos.core.media.MediaFolder
import gay.benjifox.foxyphotos.core.media.MediaStoreFolderScanner
import gay.benjifox.foxyphotos.core.security.AndroidKeystoreCredentialStore
import gay.benjifox.foxyphotos.provider.contract.CredentialReference
import gay.benjifox.foxyphotos.provider.google.GoogleAuthClient
import gay.benjifox.foxyphotos.provider.google.GoogleAuthenticationException
import gay.benjifox.foxyphotos.provider.google.GoogleCredentialParser
import gay.benjifox.foxyphotos.provider.google.GoogleNativeUploadClient
import gay.benjifox.foxyphotos.provider.google.GoogleUploadOutcome
import gay.benjifox.foxyphotos.provider.google.GoogleUploadRequest
import gay.benjifox.foxyphotos.provider.google.GoogleUploadUncertainException
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
    val uploadedContentLedger = remember { SharedPreferencesUploadedContentLedger(context.applicationContext) }
    val googleAuthClient = remember { GoogleAuthClient() }
    val googleUploadClient = remember { GoogleNativeUploadClient() }
    val scanner = remember { MediaStoreFolderScanner(context.contentResolver) }
    val scope = rememberCoroutineScope()
    var credentialInput by remember { mutableStateOf("") }
    var configuredAccount by remember { mutableStateOf<String?>(null) }
    var setupError by remember { mutableStateOf<String?>(null) }
    var folders by remember { mutableStateOf<List<MediaFolder>>(emptyList()) }
    var selectedFolders by remember { mutableStateOf(preferences.getStringSet("enabled-folders", emptySet()) ?: emptySet()) }
    var paused by remember { mutableStateOf(preferences.getBoolean("paused", true)) }
    var activityStatus by remember { mutableStateOf("Waiting for setup") }
    var scanError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        val savedCredential = credentialStore.loadCredential(CredentialReference("google-native"))
        if (savedCredential != null) {
            try {
                val parsed = GoogleCredentialParser.parse(savedCredential.decodeToString())
                configuredAccount = parsed.accountEmail
                activityStatus = "Saved Google credential · scan folders to choose backup sources"
            } catch (_: IllegalArgumentException) {
                activityStatus = "Saved Google credential needs replacement"
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
                activityStatus = "Scanning local folders"
                folders = scanner.scan()
                scanError = null
                if (selectedFolders.isEmpty() && folders.isNotEmpty()) {
                    persistFolders(folders.map(MediaFolder::relativePath).toSet())
                }
                activityStatus = "Scan complete · ${folders.sumOf(MediaFolder::itemCount)} local items in ${folders.size} folders"
            } catch (error: SecurityException) {
                scanError = "Photo permission is required to scan folders."
                activityStatus = "Waiting for photo permission"
            }
        }
    }
    fun runSelectedFolderBackup() {
        scope.launch {
            if (paused) {
                activityStatus = "Backup is paused"
                return@launch
            }
            val secret = credentialStore.loadCredential(CredentialReference("google-native"))
            if (secret == null) {
                activityStatus = "Google credential is missing"
                return@launch
            }
            try {
                val credential = GoogleCredentialParser.parse(secret.decodeToString())
                val media = scanner.scanSelectedFolders(selectedFolders)
                if (media.isEmpty()) {
                    activityStatus = "No media found in selected folders"
                    return@launch
                }
                var uploaded = 0
                var skipped = 0
                media.forEachIndexed { index, item ->
                    if (paused) {
                        activityStatus = "Backup paused after $uploaded uploaded and $skipped skipped"
                        return@launch
                    }
                    activityStatus = "Hashing ${index + 1} of ${media.size} · ${item.displayName}"
                    val hashed = scanner.hash(item)
                    if (uploadedContentLedger.contains(hashed.sha256)) {
                        skipped += 1
                        return@forEachIndexed
                    }
                    activityStatus = "Backing up ${index + 1} of ${media.size} · ${item.displayName}"
                    when (val result = googleUploadClient.upload(
                        GoogleUploadRequest(
                            credential = credential,
                            fileName = item.displayName,
                            sizeBytes = item.sizeBytes,
                            modifiedEpochSeconds = item.modifiedEpochSeconds,
                            sha1 = hashed.sha1,
                            openSource = {
                                context.contentResolver.openInputStream(item.uri)
                                    ?: throw IllegalStateException("Selected media is no longer readable.")
                            },
                        ),
                    )) {
                        is GoogleUploadOutcome.Committed -> {
                            uploadedContentLedger.recordCommitted(hashed.sha256, result.mediaKey)
                            uploaded += 1
                        }
                        is GoogleUploadOutcome.Present -> {
                            uploadedContentLedger.recordCommitted(hashed.sha256, result.mediaKey)
                            skipped += 1
                        }
                    }
                }
                activityStatus = "Backup complete · $uploaded uploaded · $skipped already present"
            } catch (_: GoogleUploadUncertainException) {
                activityStatus = "Could not confirm the last upload · it will be checked again before retrying"
            } catch (error: Exception) {
                activityStatus = error.message ?: "Backup stopped with an unexpected error"
            } finally {
                secret.fill(0)
            }
        }
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

    Scaffold { padding ->
        Column(
            modifier = Modifier.fillMaxSize().background(Color(0xFF101015)).padding(padding).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("FoxyPhotos", style = MaterialTheme.typography.headlineLarge)
            Text(
                configuredAccount?.let { "Google Photos · $it" } ?: "Set up your Google destination",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Current status", style = MaterialTheme.typography.titleMedium)
                    Text(activityStatus)
                    if (configuredAccount != null) {
                        Text(
                            text = if (paused) {
                                "Backup paused · ${selectedFolders.size} folders selected"
                            } else {
                                "Backup running or ready · ${selectedFolders.size} folders selected"
                            },
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            if (configuredAccount == null) {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Connect Google Photos", style = MaterialTheme.typography.titleMedium)
                        Text("FoxyPhotos validates the imported credential with Google before encrypting it locally.")
                        OutlinedTextField(
                            value = credentialInput,
                            onValueChange = { credentialInput = it; setupError = null },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("Google Photos credential") },
                            isError = setupError != null,
                        )
                        setupError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                        Button(enabled = credentialInput.isNotBlank(), onClick = {
                            scope.launch {
                                val secret = credentialInput.encodeToByteArray()
                                try {
                                    val parsed = GoogleCredentialParser.parse(credentialInput)
                                    googleAuthClient.exchange(parsed)
                                    credentialStore.saveCredential(CredentialReference("google-native"), secret)
                                    configuredAccount = parsed.accountEmail
                                    activityStatus = "Google credential validated · scan folders to choose backup sources"
                                    credentialInput = ""
                                } catch (error: GoogleAuthenticationException) {
                                    setupError = error.message ?: "Google could not validate that credential."
                                } catch (_: IOException) {
                                    setupError = "Could not reach Google. Check your connection and try again."
                                } catch (error: IllegalArgumentException) {
                                    setupError = error.message ?: "That credential is not valid."
                                } finally {
                                    secret.fill(0)
                                }
                            }
                        }) { Text("Validate and save credential") }
                    }
                }
            } else {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(if (paused) "Backup paused" else "Backup enabled", style = MaterialTheme.typography.titleMedium)
                        Text("Only enabled folders will be scanned and uploaded.")
                        Button(onClick = {
                            paused = !paused
                            preferences.edit().putBoolean("paused", paused).apply()
                            if (paused) {
                                activityStatus = "Backup paused by you"
                            } else {
                                activityStatus = "Starting selected-folder backup"
                                runSelectedFolderBackup()
                            }
                        }) { Text(if (paused) "Resume backup" else "Pause backup") }
                        Button(
                            enabled = !paused && selectedFolders.isNotEmpty(),
                            onClick = ::runSelectedFolderBackup,
                        ) { Text("Back up selected folders") }
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {
                        val permitted = requiredPermissions.any {
                            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
                        }
                        if (permitted) scanFolders() else permissionLauncher.launch(requiredPermissions)
                    }) { Text(if (folders.isEmpty()) "Scan folders" else "Rescan folders") }
                    Button(enabled = folders.isNotEmpty(), onClick = { persistFolders(emptySet()) }) {
                        Text("Clear selection")
                    }
                }
                scanError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                Text("Folders to back up", style = MaterialTheme.typography.titleMedium)
                if (folders.isEmpty()) {
                    Text("Scan your device to choose which folders FoxyPhotos may back up.")
                } else {
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(folders, key = MediaFolder::relativePath) { folder ->
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Checkbox(
                                    checked = folder.relativePath in selectedFolders,
                                    onCheckedChange = { checked ->
                                        persistFolders(if (checked) selectedFolders + folder.relativePath else selectedFolders - folder.relativePath)
                                    },
                                )
                                Column {
                                    Text(folder.relativePath)
                                    Text(
                                        "${folder.itemCount} items · ${folder.imageCount} photos · ${folder.videoCount} videos",
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
