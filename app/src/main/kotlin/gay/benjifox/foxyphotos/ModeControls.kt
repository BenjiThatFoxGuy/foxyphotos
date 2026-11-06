package gay.benjifox.foxyphotos

import androidx.compose.foundation.layout.Row
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics

@Composable
internal fun ModeChoices(mode: String, onMode: (String) -> Unit) {
    Row {
        listOf("Modern", "Simple").forEach { value ->
            FilterChip(selected = mode == value, onClick = { onMode(value) }, label = { Text(value) })
        }
    }
}

@Composable
internal fun ProfileControl(account: String?) {
    var expanded by remember { mutableStateOf(false) }
    IconButton(onClick = { expanded = true }, modifier = Modifier.semantics { contentDescription = "Account" }) { Text("●") }
    if (expanded) AlertDialog(onDismissRequest = { expanded = false },
        title = { Text("Google Photos") }, text = { Text(account ?: "Not connected") },
        confirmButton = { TextButton(onClick = { expanded = false }) { Text("Close") } })
}
