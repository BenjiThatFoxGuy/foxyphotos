package gay.benjifox.foxyphotos

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import gay.benjifox.foxyphotos.core.backup.BackupCondition
import gay.benjifox.foxyphotos.core.media.MediaStoreFolderScanner
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable internal fun BackupPolicyControls() {
    val context = LocalContext.current
    val policy = remember { AndroidBackupPolicy(context) }
    val scope = rememberCoroutineScope()
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) { while (true) { delay(2000); tick++ } }
    var dialog by remember { mutableStateOf("") }
    var duration by remember { mutableStateOf<Long?>(null) }
    var covered by remember { mutableStateOf<Set<BackupCondition>>(emptySet()) }
    var error by remember { mutableStateOf<String?>(null) }
    tick
    val reasons = policy.reasons()
    if (reasons.isNotEmpty()) Text(reasons.joinToString(" · "), style = MaterialTheme.typography.bodySmall)
    val pause = policy.paused()
    Row {
        TextButton(onClick = { dialog = "pause" }) { Text("Pause for…") }
        if (pause) TextButton(onClick = {
            policy.resume(); BackupScheduling.refresh(context, false)
            if (!BackupRuntime.state.value.active && policy.prefs.getBoolean("request-pending", false)) BackupRuntime.command(context, BackupService.START)
            tick++
        }) { Text("Resume") }
        TextButton(onClick = { dialog = "override" }) { Text("Back up anyway") }
    }
    val activeOverride = policy.timerActive("override") || policy.prefs.getBoolean("override-batch", false)
    if (activeOverride) {
        val until = if (policy.prefs.getBoolean("override-batch", false)) "This batch" else "Until " + java.time.Instant.ofEpochMilli(
            policy.prefs.getLong("override-created", 0) + policy.prefs.getLong("override-duration", 0)).atZone(java.time.ZoneId.systemDefault()).toLocalTime().withSecond(0).withNano(0)
        Text("$until · " + (policy.prefs.getStringSet("override-covered", emptySet()) ?: emptySet()).joinToString { name -> BackupCondition.valueOf(name).label }, style = MaterialTheme.typography.bodySmall)
        TextButton(onClick = { policy.endOverride(); tick++ }) { Text("End override") }
    }
    if (policy.prefs.getString("app-mode", "Simple") == "Modern") {
        Row {
            TextButton(onClick = { dialog = "immediate" }) { Text("Upload immediately…") }
            if (policy.timerActive("immediate")) TextButton(onClick = { policy.clearTimer("immediate"); BackupScheduling.refresh(context); tick++ }) { Text("Stop watching") }
        }
    }
    if (policy.prefs.getBoolean("request-pending", false)) TextButton(onClick = {
        BackupRuntime.command(context, BackupService.CANCEL); tick++
    }) { Text("Stop backup") }
    error?.let { Text(it) }
    if (dialog in listOf("pause", "override", "immediate")) AlertDialog(onDismissRequest = { dialog = "" },
        title = { Text(when (dialog) { "pause" -> "Pause for"; "immediate" -> "Upload immediately"; else -> "Back up anyway" }) },
        text = { Column {
            val kind = dialog
            fun choose(value: Long?) {
                when (kind) {
                    "pause" -> { policy.pause(value); dialog = ""; tick++ }
                    "immediate" -> { policy.timer("immediate", value!!); BackupScheduling.eager(context); dialog = ""; tick++ }
                    else -> { duration = value; dialog = "scope" }
                }
            }
            if (kind != "immediate") TextButton(onClick = { choose(null) }) { Text(if (kind == "pause") "Until I resume" else "This batch") }
            listOf("15 minutes" to 900000L, "1 hour" to 3600000L, "4 hours" to 14400000L).forEach { (label, ms) -> TextButton(onClick = { choose(ms) }) { Text(label) } }
        } }, confirmButton = {})
    if (dialog == "scope") AlertDialog(onDismissRequest = { dialog = "" }, title = { Text("Exception scope") },
        text = { Column {
            TextButton(onClick = { covered = policy.rawBlockers(); dialog = "confirm" }) { Text("Current blockers only") }
            TextButton(onClick = { covered = BackupCondition.entries.toSet(); dialog = "confirm" }) { Text("All backup conditions") }
        } }, confirmButton = {})
    if (dialog == "confirm") AlertDialog(onDismissRequest = { dialog = "" }, title = { Text("Allow these exceptions?") },
        text = { Column {
            Text(covered.joinToString(" · ") { it.label }.ifEmpty { "No current policy blockers" })
            if (BackupCondition.CELLULAR in covered || BackupCondition.ROAMING in covered) Text("Allows cellular data and/or roaming charges. Confirm only if you accept those costs.")
        } }, dismissButton = { TextButton(onClick = { dialog = "" }) { Text("Cancel") } }, confirmButton = {
            TextButton(onClick = {
                dialog = ""
                scope.launch {
                    try {
                        val ids = if (duration == null) policy.request() ?: MediaStoreFolderScanner(context.contentResolver)
                            .scanSelectedFolders(policy.prefs.getStringSet("enabled-folders", emptySet()) ?: emptySet()).map { it.uri.toString() }.toSet() else null
                        if (ids != null && !BackupRuntime.state.value.active && policy.request() == null) policy.requestItems(ids)
                        policy.override(covered, duration, ids)
                        if (!BackupRuntime.state.value.active && !policy.paused()) BackupRuntime.command(context, BackupService.START)
                        tick++
                    } catch (_: Exception) { error = "Could not capture backup batch" }
                }
            }) { Text("Confirm") }
        })
}

@Composable internal fun BackupConditionSettings() {
    val context = LocalContext.current
    val prefs = remember { AndroidBackupPolicy(context).prefs }
    Column {
        Text("Backup conditions", style = MaterialTheme.typography.titleMedium)
        listOf(Triple("allow-wifi", "Wi-Fi", true), Triple("allow-cellular", "Cellular", false),
            Triple("allow-metered", "Metered Wi-Fi", false), Triple("allow-roaming", "Roaming (requires cellular)", false),
            Triple("allow-data-saver", "While Data Saver is on", false), Triple("allow-power-saver", "While power saver is on", false),
            Triple("require-charging", "Only while charging", false)).forEach { (key, label, default) ->
            var checked by remember { mutableStateOf(prefs.getBoolean(key, default)) }
            Row { Checkbox(checked, { checked = it; prefs.edit().putBoolean(key, it).apply(); BackupScheduling.refresh(context, false) }); Text(label) }
        }
    }
}
