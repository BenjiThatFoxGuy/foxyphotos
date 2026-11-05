package gay.benjifox.foxyphotos

import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DiagnosticShareFilesTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun `creates independent text attachments only inside dedicated cache`() {
        val cache = temporary.root
        val first = DiagnosticShareFiles.create(cache, "first sanitized export")
        val second = DiagnosticShareFiles.create(cache, "second sanitized export")
        assertEquals("first sanitized export", first.readText())
        assertEquals("second sanitized export", second.readText())
        assertNotEquals(first, second)
        assertEquals(java.io.File(cache, "diagnostic-shares"), first.parentFile)
        assertEquals("txt", first.extension)
    }

    @Test fun `prunes expired shares but never other cache files`() {
        val cache = temporary.root
        val other = java.io.File(cache, "unrelated.txt").apply { writeText("keep") }
        val old = DiagnosticShareFiles.create(cache, "expired")
        old.setLastModified(System.currentTimeMillis() - 25L * 60 * 60 * 1000)
        val fresh = DiagnosticShareFiles.create(cache, "fresh")
        assertFalse(old.exists())
        assertTrue(fresh.exists())
        assertTrue(other.exists())
    }

    @Test fun `bounds temporary attachment count while retaining newest export`() {
        val cache = temporary.root
        val files = (1..20).map { DiagnosticShareFiles.create(cache, "export $it") }
        assertEquals(8, java.io.File(cache, "diagnostic-shares").listFiles()!!.size)
        assertTrue(files.last().exists())
        assertEquals("export 20", files.last().readText())
    }
}
