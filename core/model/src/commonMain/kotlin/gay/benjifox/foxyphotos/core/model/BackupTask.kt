package gay.benjifox.foxyphotos.core.model

/**
 * Identifies a media item inside a source library.
 *
 * Plain data classes rather than value classes: `@JvmInline` is absent from the
 * common standard library, so value classes cannot be declared in shared code
 * and still compile for every target.
 */
data class MediaItemId(val value: String) {
    init {
        require(value.isNotBlank()) { "Media item ID cannot be blank." }
    }
}

data class DestinationId(val value: String) {
    init {
        require(value.isNotBlank()) { "Destination ID cannot be blank." }
    }
}

data class BackupTaskId(val value: String) {
    init {
        require(value.isNotBlank()) { "Backup task ID cannot be blank." }
    }
}

data class BackupTask(
    val id: BackupTaskId,
    val mediaItemId: MediaItemId,
    val destinationId: DestinationId,
)
