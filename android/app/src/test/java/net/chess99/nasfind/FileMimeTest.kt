package net.chess99.nasfind

import org.junit.Assert.*
import org.junit.Test

class FileMimeTest {
    @Test fun hostAliasesDoNotChangeAndroidFileTypes() {
        assertEquals("video/x-msvideo", FileMime.resolve("clip.AVI", "video/vnd.avi"))
        assertEquals("audio/wav", FileMime.resolve("sound.wav", "audio/x-wav"))
        assertEquals("video/x-matroska", FileMime.resolve("clip.mkv", "application/octet-stream"))
        assertEquals("application/vnd.openxmlformats-officedocument.wordprocessingml.document", FileMime.resolve("report.docx"))
        assertEquals("text/plain", FileMime.resolve("unknown", "Text/Plain; charset=utf-8"))
        assertEquals("application/octet-stream", FileMime.resolve("unknown", "not mime"))
    }
    @Test fun mimeRestrictionsAndAppleDoubleHeaderAreExplicit() {
        assertTrue(FileMime.accepts("image/jpeg", listOf("image/*")))
        assertFalse(FileMime.accepts("video/mp4", listOf("image/*")))
        assertTrue(FileMime.accepts("application/pdf", listOf("image/*", "application/pdf")))
        assertTrue(FileMime.isAppleDouble(byteArrayOf(0, 5, 22, 7, 0)))
        assertFalse(FileMime.isAppleDouble("RIFF".toByteArray()))
        assertFalse(FileMime.isAppleDouble(byteArrayOf(0, 5)))
    }
}
