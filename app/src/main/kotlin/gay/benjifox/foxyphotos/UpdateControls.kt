package gay.benjifox.foxyphotos

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
internal fun UpdateControls(updater: AppUpdater) {
    val state by updater.state.collectAsStateWithLifecycle()
    var automatic by remember { mutableStateOf(updater.automatic) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("App updates", style = MaterialTheme.typography.titleMedium)
        Text(state.text)
        state.progress?.let { LinearProgressIndicator(progress = { it }, modifier = Modifier.fillMaxWidth()) }
        Row {
            if (state.busy) TextButton(onClick = updater::cancel) { Text("Cancel") }
            else {
                TextButton(onClick = { updater.check() }) { Text("Check for updates") }
                if (state.release != null) {
                    TextButton(onClick = { if (state.ready) updater.install() else updater.download() }) {
                        Text(if (state.ready) "Install update" else "Download update")
                    }
                }
            }
        }
        Row {
            Checkbox(automatic, onCheckedChange = { automatic = it; updater.setAutomatic(it) })
            Text("Check on launch", modifier = Modifier.padding(top = 12.dp))
        }
    }
}
