package gay.benjifox.foxyphotos

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

@Composable
internal fun ModeChoices(mode: String, onMode: (String) -> Unit) {
    Row {
        listOf("Modern", "Simple").forEach { value ->
            FilterChip(selected = mode == value, onClick = { onMode(value) }, label = { Text(value) })
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ProfileControl(
    account: String?,
    onSettings: (() -> Unit)? = null,
    backupControls: (@Composable () -> Unit)? = null,
) {
    var expanded by remember { mutableStateOf(false) }
    if (backupControls == null) {
        // Simple keeps its existing account dialog and at-a-glance controls.
        IconButton(onClick = { expanded = true }, modifier = Modifier.semantics { contentDescription = "Account" }) { Text("●") }
        if (expanded) AlertDialog(onDismissRequest = { expanded = false },
            title = { Text("Google Photos") }, text = { Text(account ?: "Not connected") },
            confirmButton = { TextButton(onClick = { expanded = false }) { Text("Close") } })
        return
    }
    // Only initials leave the private account panel, never the address itself.
    val initials = account?.substringBefore('@')?.split('.', '-', '_', ' ')
        ?.filter { it.isNotEmpty() }?.take(2)?.joinToString("") { it.take(1).uppercase() }
        ?.takeIf { it.isNotEmpty() } ?: "FP"
    IconButton(onClick = { expanded = true }, modifier = Modifier.semantics { contentDescription = "Account" }) {
        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.secondaryContainer) {
            Box(Modifier.size(36.dp), contentAlignment = androidx.compose.ui.Alignment.Center) {
                Text(initials, style = MaterialTheme.typography.labelLarge)
            }
        }
    }
    if (expanded) ModalBottomSheet(
        onDismissRequest = { expanded = false },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Google Photos", style = MaterialTheme.typography.titleLarge)
            Text(account ?: "Not connected", style = MaterialTheme.typography.bodyMedium)
            backupControls?.let {
                HorizontalDivider()
                it()
            }
            onSettings?.let { openSettings ->
                HorizontalDivider()
                TextButton(onClick = { expanded = false; openSettings() }, modifier = Modifier.semantics { contentDescription = "Settings" }) {
                    Text("Settings")
                }
            }
            TextButton(onClick = { expanded = false }) { Text("Close") }
        }
    }
}
