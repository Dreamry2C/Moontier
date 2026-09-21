package cn.moonflow.easytier

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class LogPreviewCacheTest {
    @Test fun unchangedLogsAreCachedButWritesAndTruncationInvalidateThem() {
        val file = File.createTempFile("log-preview", ".log")
        var renders = 0
        val cache = LogPreviewCache { renders++; it }
        try {
            file.writeText("first")
            assertEquals("first", cache.preview(file))
            repeat(20) { assertEquals("first", cache.preview(file)) }
            assertEquals(1, renders)
            val time = file.lastModified()
            file.writeText("other")
            file.setLastModified(time)
            cache.invalidate() // same size/timestamp is still observable for our writer
            assertEquals("other", cache.preview(file))
            file.writeText("")
            assertEquals("", cache.preview(file))
            assertEquals(3, renders)
        } finally { file.delete() }
    }

    @Test fun cachedResultIsNotPublishedWhenAWriterChangesItDuringRendering() {
        val file = File.createTempFile("log-preview-race", ".log")
        lateinit var cache: LogPreviewCache
        var renders = 0
        cache = LogPreviewCache { text ->
            if (++renders == 1) { file.writeText("new"); cache.invalidate() }
            text
        }
        try {
            file.writeText("old")
            assertEquals("old", cache.preview(file))
            assertEquals("new", cache.preview(file))
            assertEquals("new", cache.preview(file))
            assertEquals(2, renders)
        } finally { file.delete() }
    }
}
