package gay.benjifox.foxyphotos.core.updater

import org.junit.Assert.*
import org.junit.Test

class ReleaseUpdateTest {
    private val release = ReleaseUpdate("gay.benjifox.foxyphotos", "1.2", 67, 29, "v1.2",
        "app-release.apk", "https://github.com/BenjiThatFoxGuy/foxyphotos/releases/download/v1.2/app-release.apk", 123, "a".repeat(64), "b".repeat(64))
    @Test fun onlyNewerCompatibleUpdates() {
        assertTrue(release.available("gay.benjifox.foxyphotos", 66, 29))
        assertFalse(release.available("gay.benjifox.foxyphotos", 67, 29))
        assertFalse(release.available("gay.benjifox.foxyphotos.debug", 66, 29))
        assertFalse(release.available("gay.benjifox.foxyphotos", 66, 28))
    }
    @Test fun rejectsArbitraryOriginsAndPaths() {
        for (url in listOf("http://github.com/a", "https://evil.example/a", "https://github.com/other/repo/releases/download/v1/a.apk")) {
            assertThrows(IllegalArgumentException::class.java) { release.copy(url = url).validate() }
        }
        assertThrows(IllegalArgumentException::class.java) { release.copy(filename = "../a.apk").validate() }
        release.validate()
    }
}
