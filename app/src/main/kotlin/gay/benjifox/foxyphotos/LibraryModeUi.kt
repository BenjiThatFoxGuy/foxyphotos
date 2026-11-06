package gay.benjifox.foxyphotos

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import gay.benjifox.foxyphotos.core.model.LibraryMode

@Composable
internal fun LibraryModeMenu(mode: LibraryMode, onSelect: (LibraryMode) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { expanded = true }, modifier = Modifier.semantics { contentDescription = "Library mode" }) {
            Text("Library ▾")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            LibraryMode.entries.forEach { choice ->
                DropdownMenuItem(text = {
                    Column {
                        Text(if (mode == choice) "✓ ${choice.title}" else choice.title)
                        Text(choice.sources, style = MaterialTheme.typography.bodySmall)
                    }
                }, onClick = { expanded = false; onSelect(choice) })
            }
        }
    }
}

@Composable
internal fun LibraryModeChoices(mode: LibraryMode, onSelect: (LibraryMode) -> Unit) {
    Column {
        Text("Library mode", style = MaterialTheme.typography.titleMedium)
        LibraryMode.entries.forEach { choice ->
            TextButton(onClick = { onSelect(choice) }) {
                Column {
                    Text(if (mode == choice) "✓ ${choice.title}" else choice.title)
                    Text(choice.sources, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}
