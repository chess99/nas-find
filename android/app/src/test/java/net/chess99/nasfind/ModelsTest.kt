package net.chess99.nasfind

import org.junit.Assert.*
import org.junit.Test

class ModelsTest {
    @Test fun fullSelectionIncludesUnloadedPagesAndKeepsExclusions() {
        val selection = Selection(all = true).toggle(1).toggle(500).toggle(1002)
        assertEquals(1000, selection.count(1003))
        assertTrue(selection.contains(999)); assertFalse(selection.contains(500))
        assertEquals("[[1,2],[500,501],[1002,1003]]", selection.json().getJSONArray("ranges").toString())
        assertTrue(selection.json().getBoolean("all"))
    }
    @Test fun selectedRangesAreMinimalHalfOpenAndOrdered() {
        val selection = Selection(indices = setOf(10, 3, 2, 1, 8, 9))
        assertEquals("[[1,4],[8,11]]", selection.json().getJSONArray("ranges").toString())
        assertEquals(selection, selection.toggle(500).toggle(500))
        assertEquals("[]", Selection().json().getJSONArray("ranges").toString())
    }
    @Test fun textRejectsAmbiguousNamesAndCsvPreservesThem() {
        val path = "目录/含\"引号\n和\\反斜杠.csv"
        assertThrows(IllegalArgumentException::class.java) { textPath(path) }
        assertEquals("\"./目录/含\"\"引号\n和\\反斜杠.csv\"\r\n", csvPath(path))
        assertEquals("\"./=1+1.csv\"\r\n", csvPath("=1+1.csv"))
        assertEquals("目录/中文.txt\r\n", textPath("目录/中文.txt"))
    }
    @Test fun filtersNormalizeWithoutConflatingDirectoryAndArchive() {
        assertEquals(Filters(scope = "文档/旅行", extension = "pdf"), Filters(scope = "文档\\旅行/", extension = ".PDF").normalized())
        assertEquals("", Filters(category = "folder", extension = "pdf").normalized().extension)
        assertEquals("archive", Filters(category = "archive").normalized().category)
        assertThrows(IllegalArgumentException::class.java) { Filters(extension = "pdf,txt").normalized() }
        assertThrows(IllegalArgumentException::class.java) { Filters(scope = "资料/../秘密").normalized() }
    }
    @Test fun originValidationRejectsCredentialAndPathInjection() {
        assertEquals("http://nas.example.internal:8765", normalizeServer(" http://nas.example.internal:8765/ "))
        assertEquals("http://[::1]:8765", normalizeServer("http://[::1]:8765"))
        listOf("file:///a", "https://user:pass@example.com", "https://example.com/a", "https://example.com/?token=a", "http://example.com:99999").forEach {
            assertThrows(IllegalArgumentException::class.java) { normalizeServer(it) }
        }
    }
    @Test fun usableOldIndexRemainsSearchableDuringFailure() {
        assertEquals("更新失败，可搜索", IndexStatus(available = true, error = "failed").label)
        assertEquals("索引不可用", IndexStatus(error = "failed").label)
        assertEquals("更新中，可搜索", IndexStatus(available = true, scanning = true).label)
    }
}
