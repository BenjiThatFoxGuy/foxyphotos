package gay.benjifox.foxyphotos.core.model

@JvmInline
value class MediaItemId(val value: String) {
    init {
        require(value.isNotBlank()) { "Media item ID cannot be blank." }
    }
}

@JvmInline
value class DestinationId(val value: String) {
    init {
        require(value.isNotBlank()) { "Destination ID cannot be blank." }
    }
}

@JvmInline
value class BackupTaskId(val value: String) {
    init {
        require(value.isNotBlank()) { "Backup task ID cannot be blank." }
    }
}

data class BackupTask(
    val id: BackupTaskId,
    val mediaItemId: MediaItemId,
    val destinationId: DestinationId,
)
