package gay.benjifox.foxyphotos.core.backup

import gay.benjifox.foxyphotos.core.model.DestinationId
import gay.benjifox.foxyphotos.core.model.MediaItemId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupTaskPlannerTest {
    @Test
    fun `creates separate tasks when one media item has two destinations`() {
        val mediaItemId = MediaItemId("media-123")
        val googleDestinationId = DestinationId("google")
        val hubDestinationId = DestinationId("hub")

        val tasks = BackupTaskPlanner().plan(
            mediaItemId = mediaItemId,
            destinationIds = setOf(googleDestinationId, hubDestinationId),
        )

        assertEquals(2, tasks.size)
        assertEquals(setOf(googleDestinationId, hubDestinationId), tasks.map { it.destinationId }.toSet())
        assertTrue(tasks.all { it.mediaItemId == mediaItemId })
        assertEquals(2, tasks.map { it.id }.toSet().size)
    }
}
