package net.chess99.nasfind

import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class LocalCopiesTest {
    @Test fun copiesAreIsolatedByServerPathAndVersion() {
        val root = Files.createTempDirectory("copies-test").toFile()
        try {
            val cache = LocalCopies(root)
            val first = cache.file("http://nas", "docs/a.pdf", "a.pdf", 10, "1")
            assertEquals(first, cache.file("http://nas", "docs/a.pdf", "a.pdf", 10, "1"))
            assertNotEquals(first, cache.file("http://other", "docs/a.pdf", "a.pdf", 10, "1"))
            assertNotEquals(first, cache.file("http://nas", "docs/a.pdf", "a.pdf", 10, "2"))
            assertEquals(root.canonicalFile, cache.file("http://nas", "docs/a", "../name", 10, "1").parentFile!!.canonicalFile)
            first.writeText("cached")
            first.setLastModified(System.currentTimeMillis() - 8 * 24 * 3600_000L)
            val active = cache.file("http://nas", "docs/b.pdf", "b.pdf", 10, "1").apply { writeText("active") }
            cache.trim()
            assertFalse(first.exists()); assertTrue(active.exists())
        } finally { root.deleteRecursively() }
    }
}
