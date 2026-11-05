package gay.benjifox.foxyphotos.core.backup

import gay.benjifox.foxyphotos.core.model.BackupTask
import gay.benjifox.foxyphotos.core.model.BackupTaskId
import gay.benjifox.foxyphotos.core.model.DestinationId
import gay.benjifox.foxyphotos.core.model.MediaItemId

class BackupTaskPlanner(
    private val nextTaskId: () -> BackupTaskId,
) {
    fun plan(
        mediaItemId: MediaItemId,
        destinationIds: Set<DestinationId>,
    ): List<BackupTask> = destinationIds.map { destinationId ->
        BackupTask(
            id = nextTaskId(),
            mediaItemId = mediaItemId,
            destinationId = destinationId,
        )
    }
}
