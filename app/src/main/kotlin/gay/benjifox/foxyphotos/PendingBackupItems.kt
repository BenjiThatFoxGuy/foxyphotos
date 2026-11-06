package gay.benjifox.foxyphotos

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Only confirmation/work paths hash content; never used for gallery rendering. */
internal suspend fun <Item, Hash> pendingItemIds(
    items: List<Item>,
    id: (Item) -> String,
    hash: suspend (Item) -> Hash,
    isCommitted: (Hash) -> Boolean,
): Set<String> {
    val seen = mutableSetOf<Hash>()
    return buildSet {
        for (item in items) {
            currentCoroutineContext().ensureActive()
            try {
                val digest = hash(item)
                currentCoroutineContext().ensureActive()
                if (!isCommitted(digest) && seen.add(digest)) add(id(item))
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { add(id(item)) } // Work reports unreadable items truthfully.
        }
    }
}
