package cn.moonflow.easytier

import java.io.ByteArrayOutputStream
import java.io.FilterInputStream
import java.util.Date
import java.util.TimeZone
import org.junit.Assert.*
import org.junit.Test

class LogTimeTest {
    @Test fun newTimestampsUseFixedUtc8RegardlessOfDeviceTimezone() {
        val original = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("America/Los_Angeles"))
            assertEquals("1970-01-01T08:00:00.000+08:00", LogTime.now(Date(0)))
        } finally { TimeZone.setDefault(original) }
    }

    @Test fun offsetsRollDatesCorrectlyAndKeepNanoseconds() {
        assertEquals("2026-09-17T01:01:23.123456789+08:00 INFO 中文", LogTime.normalize("2026-09-16T17:01:23.123456789Z INFO 中文"))
        assertEquals("2027-01-01T04:00:00+08:00 INFO", LogTime.normalize("2026-12-31T20:00:00Z INFO"))
        assertEquals("2026-09-16T23:00:00+08:00 INFO", LogTime.normalize("2026-09-17T01:00:00+10:00 INFO"))
        assertEquals("2026-09-17T16:00:00+08:00 INFO", LogTime.normalize("2026-09-17T01:00:00-0700 INFO"))
        val local = "2026-09-17T01:01:23.123456789+08:00 INFO"
        assertEquals(local, LogTime.normalize(local))
        assertEquals("2026-09-17T01:01:23.123+08:00 EVENT/app", LogTime.normalize("2026-09-17 01:01:23.123 EVENT/app"))
        assertEquals("--- MoonTier manager start 2026-09-17T01:00:00+08:00 ---", LogTime.normalize("--- MoonTier manager start 2026-09-16T17:00:00Z ---"))
    }

    @Test fun malformedDatesAndTimestampsInsideMessagesRemainUntouched() {
        for (line in listOf("2026-02-30T17:00:00Z INFO", "2026-09-16T17:00:00+99:00 INFO",
            "2026-09-16T17:00:00", "payload time=2026-09-16T17:00:00Z", "plain message")) {
            assertEquals(line, line, LogTime.normalize(line))
        }
    }

    @Test fun streamingPreservesLongLinesUtf8CrLfAndBytesAcrossReadBoundaries() {
        val payload = "中文长行".repeat(100000).toByteArray() + byteArrayOf(0xff.toByte(), 0)
        val input = "2026-09-16T17:01:23.123456789Z INFO ".toByteArray() + payload +
            "\r\n2026-09-17 01:02:03.004 EVENT/app\nlast line without newline".toByteArray()
        val expected = "2026-09-17T01:01:23.123456789+08:00 INFO ".toByteArray() + payload +
            "\r\n2026-09-17T01:02:03.004+08:00 EVENT/app\nlast line without newline".toByteArray()
        val fragmented = object : FilterInputStream(input.inputStream()) {
            override fun read(bytes: ByteArray, offset: Int, length: Int): Int = super.read(bytes, offset, minOf(length, 7))
        }
        val output = ByteArrayOutputStream()
        fragmented.use { LogTime.copyNormalized(it, output) }
        assertArrayEquals(expected, output.toByteArray())
    }
}
