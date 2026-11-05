package gay.benjifox.foxyphotos

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import gay.benjifox.foxyphotos.core.security.AndroidKeystoreCredentialStore
import gay.benjifox.foxyphotos.provider.contract.CredentialReference
import gay.benjifox.foxyphotos.provider.google.GoogleCredentialParser
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                FoxyPhotosApp()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@androidx.compose.runtime.Composable
private fun FoxyPhotosApp() {
    val context = LocalContext.current
    val credentialStore = remember { AndroidKeystoreCredentialStore(context.applicationContext) }
    val scope = rememberCoroutineScope()
    var source by remember { mutableStateOf("All") }
    var credentialInput by remember { mutableStateOf("") }
    var configuredAccount by remember { mutableStateOf<String?>(null) }
    var setupError by remember { mutableStateOf<String?>(null) }

    Scaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFF101015))
                .padding(padding)
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("Photos", style = MaterialTheme.typography.headlineLarge)
                Text(
                    text = configuredAccount?.let { "Google Photos · $it" } ?: "Set up a backup destination",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = if (configuredAccount == null) "Backups are not configured" else "Backup is paused",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        text = if (configuredAccount == null) {
                            "Import your Google Photos credential to enable direct backup."
                        } else {
                            "Folder selection and the persistent queue are the next setup steps."
                        },
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("All", "Google", "Local").forEach { label ->
                    FilterChip(
                        selected = source == label,
                        onClick = { source = label },
                        label = { Text(label) },
                    )
                }
            }

            if (configuredAccount == null) {
                OutlinedTextField(
                    value = credentialInput,
                    onValueChange = { credentialInput = it; setupError = null },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Google Photos credential") },
                    supportingText = {
                        Text("Imported locally. FoxyPhotos never writes it to the database or logs.")
                    },
                    isError = setupError != null,
                )
                setupError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                Button(
                    enabled = credentialInput.isNotBlank(),
                    onClick = {
                        scope.launch {
                            val secret = credentialInput.encodeToByteArray()
                            try {
                                val parsed = GoogleCredentialParser.parse(credentialInput)
                                credentialStore.saveCredential(CredentialReference("google-native"), secret)
                                configuredAccount = parsed.accountEmail
                                credentialInput = ""
                            } catch (error: IllegalArgumentException) {
                                setupError = error.message ?: "That credential is not valid."
                            } finally {
                                secret.fill(0)
                            }
                        }
                    },
                ) {
                    Text("Save Google credential")
                }
            } else {
                EmptyLibrary(source)
            }
        }
    }
}

@androidx.compose.runtime.Composable
private fun EmptyLibrary(source: String) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(3),
        modifier = Modifier.fillMaxSize(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) {
            Text(
                text = "No local photos scanned yet · $source",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 12.dp),
            )
        }
        items(emptyList<Int>()) { }
    }
}
