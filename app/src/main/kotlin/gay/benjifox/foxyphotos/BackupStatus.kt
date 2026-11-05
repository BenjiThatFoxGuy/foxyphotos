package gay.benjifox.foxyphotos

internal fun backupStatusLabel(isRunning: Boolean, paused: Boolean): String = when {
    isRunning && paused -> "Pausing backup · finishing current item"
    isRunning -> "Backup running"
    paused -> "Backup paused"
    else -> "Backup idle"
}
