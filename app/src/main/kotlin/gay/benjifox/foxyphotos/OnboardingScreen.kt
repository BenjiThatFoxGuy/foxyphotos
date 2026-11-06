package gay.benjifox.foxyphotos

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** Presentation only: credentials and permission launchers remain in the activity host. */
@Composable
internal fun OnboardingScreen(
    step: Int,
    mode: String,
    onMode: (String) -> Unit,
    account: String?,
    mediaGranted: Boolean,
    notificationsEnabled: Boolean,
    notificationRequested: Boolean,
    busy: Boolean,
    mediaError: String?,
    credentialForm: @Composable () -> Unit,
    onBack: () -> Unit,
    onContinue: () -> Unit,
    onMedia: () -> Unit,
    onMediaSettings: () -> Unit,
    onNotifications: () -> Unit,
    onDone: () -> Unit,
) {
    val titles = listOf("Welcome!", "Connect Google Photos", "Photos access", "Get Notified!", "Make it yours", "You're ready!")
    val descriptions = listOf(
        "FoxyPhotos backs up photos and videos from your device to Google Photos.",
        "Paste your Google Photos credential to connect your account.",
        "Allow access to your photos and videos to choose folders to back up.",
        "Allow notifications to follow your backup progress.",
        "Modern for browsing, or Simple for backup controls.",
        "Choose backup folders in Settings, then tap Back up when you're ready.",
    )
    Scaffold { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).imePadding(),
            contentPadding = PaddingValues(24.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("FoxyPhotos", style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary)
                    Text("Step ${step + 1} of 6", style = MaterialTheme.typography.labelMedium)
                    LinearProgressIndicator(progress = { (step + 1) / 6f }, modifier = Modifier.fillMaxWidth())
                }
            }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(titles[step], style = MaterialTheme.typography.headlineLarge)
                    Text(descriptions[step], style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    when (step) {
                        0 -> Button(onClick = onContinue, modifier = Modifier.fillMaxWidth()) { Text("Continue") }
                        1 -> {
                            if (account == null) credentialForm() else {
                                Text("Connected", style = MaterialTheme.typography.titleMedium)
                                Text(account)
                                Button(onClick = onContinue, modifier = Modifier.fillMaxWidth()) { Text("Continue") }
                            }
                            if (account == null) TextButton(onClick = onContinue, enabled = !busy) { Text("Not now") }
                        }
                        2 -> {
                            if (mediaGranted) {
                                Text("Photos access allowed")
                                Button(onClick = onContinue, modifier = Modifier.fillMaxWidth()) { Text("Continue") }
                            } else {
                                mediaError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                                Button(onClick = onMedia, modifier = Modifier.fillMaxWidth()) { Text("Allow photos access") }
                                TextButton(onClick = onMediaSettings) { Text("Open Settings") }
                                TextButton(onClick = onContinue) { Text("Not now") }
                            }
                        }
                        3 -> {
                            if (notificationsEnabled) {
                                Text("Notifications allowed")
                                Button(onClick = onContinue, modifier = Modifier.fillMaxWidth()) { Text("Continue") }
                            } else {
                                Button(onClick = onNotifications, modifier = Modifier.fillMaxWidth()) {
                                    Text(if (notificationRequested) "Open notification settings" else "Allow notifications")
                                }
                                TextButton(onClick = onContinue) { Text("Not now") }
                            }
                        }
                        4 -> {
                            ModeChoices(mode, onMode)
                            Text("Modern is recommended for local photos and videos.", style = MaterialTheme.typography.bodySmall)
                            Button(onClick = onContinue, modifier = Modifier.fillMaxWidth()) { Text("Continue") }
                        }
                        5 -> Button(onClick = onDone, modifier = Modifier.fillMaxWidth()) { Text("Done") }
                    }
                    if (step > 0) TextButton(onClick = onBack, enabled = !busy) { Text("Back") }
                }
            }
        }
    }
}
