package gay.benjifox.foxyphotos.core.model

import org.junit.Assert.*
import org.junit.Test

class LibraryModeTest {
    @Test fun namedSourcesAreLiteralAndNeverImplicitlyOverlayLocalItems() {
        val expected = mapOf(
            LibraryMode.LOCAL_ONLY to listOf(true, false, false),
            LibraryMode.GOOGLE_PHOTOS to listOf(false, true, false),
            LibraryMode.GOOGLE_AND_ICLOUD to listOf(false, true, true),
            LibraryMode.ICLOUD_ONLY to listOf(false, false, true),
        )
        assertEquals(LibraryMode.entries.toSet(), expected.keys)
        expected.forEach { (mode, sources) ->
            assertEquals(mode.title, sources, listOf(mode.includeLocal, mode.includeGoogle, mode.includeICloud))
        }
    }

    @Test fun persistedModesRoundTripAndUnknownOrPrototypeValuesFailClosed() {
        LibraryMode.entries.forEach { assertEquals(it, LibraryMode.fromStored(it.storedValue)) }
        listOf(null, "", "unknown", "true", "experimental-google-library", "experimental-icloud-library", "Modern", "Simple")
            .forEach { assertEquals(LibraryMode.LOCAL_ONLY, LibraryMode.fromStored(it)) }
    }
}
