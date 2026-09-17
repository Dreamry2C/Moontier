package cn.moonflow.easytier

import org.junit.Assert.*
import org.junit.Test

class LogPreviewTest {
    @Test fun newestRecordComesLastAndPartialLeadingRecordIsDropped() {
        val old = "2026-09-16T03:38:28.914+08:00 EVENT/keepalive old"
        val current = "2026-09-17T01:22:45.123+08:00 EVENT/launcher current"
        val source = "04724af name=old partial record\n$old\n$current\n"
        assertEquals("$old\n$current", LogPreview.chronological(source))
        assertEquals(current, LogPreview.chronological(source, current.length))
    }

    @Test fun multilineErrorKeepsItsStackInOrderAndDoesNotReverseIndividualLines() {
        val error = "2026-09-17T01:20:00.001+08:00 ERROR/root failure\njava.io.IOException: example\n\tat frameOne\n\tat frameTwo"
        val latest = "2026-09-17T01:21:00.002+08:00 EVENT/root recovered"
        val earlier = "2026-09-17T01:19:00.000+08:00 EVENT/root starting"
        assertEquals("$earlier\n\n$error\n\n$latest", LogPreview.chronological("$earlier\n$error\n$latest\n"))
        val singleError = "2026-09-17T01:20:00.001+08:00 ERROR/root invalid EVENT/keepalive message"
        assertEquals("$earlier\n\n$singleError\n\n$latest", LogPreview.chronological("$earlier\n$singleError\n$latest\n"))
    }

    @Test fun longLatestRecordStartsWithTimestampAndReportsPreviewTruncation() {
        val start = "2026-09-17T01:22:45.123+08:00 ERROR/root "
        val preview = LogPreview.chronological(start + "long message ".repeat(10000), 256)
        assertTrue(preview.startsWith(start))
        assertTrue(preview.endsWith("完整内容请导出日志。"))
        assertTrue(preview.length <= 256)
        assertEquals("", LogPreview.chronological("\n"))
        assertFalse(LogPreview.chronological("fragment without header").contains("fragment without header"))
    }
}
