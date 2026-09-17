package cn.moonflow.easytier

import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import java.util.zip.ZipInputStream
import org.junit.Assert.*
import org.junit.Test

class DiagnosticsArchiveTest {
    @Test fun allArchiveEntriesUseUtc8WithoutChangingStoredLogs() {
        val dir = File("build/test-log-fixtures/${UUID.randomUUID()}").apply { mkdirs() }
        try {
            val appText = "2026-09-17 01:00:00.123 EVENT/app 中文\n"
            val coreText = "2026-09-16T17:00:00.123456789Z INFO CORE\n2026-09-17T01:01:00+08:00 WARN CORE\n"
            val app = File(dir, "app.log").apply { writeText(appText) }
            val core = File(dir, "core.log").apply { writeText(coreText) }
            val output = ByteArrayOutputStream()
            DiagnosticsArchive.write(output, dir, "generated_at=2026-09-16T17:02:00Z", core, 1) { LogFiles.snapshot(app, it) }
            val entries = LinkedHashMap<String, String>()
            ZipInputStream(output.toByteArray().inputStream()).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    entries[entry.name] = zip.readBytes().toString(Charsets.UTF_8)
                }
            }
            val expectedApp = "2026-09-17T01:00:00.123+08:00 EVENT/app 中文\n"
            val expectedCore = "2026-09-17T01:00:00.123456789+08:00 INFO CORE\n2026-09-17T01:01:00+08:00 WARN CORE\n"
            assertEquals(expectedApp, entries["moontier-app.log"])
            assertEquals(expectedCore, entries["moontier-root-core.log"])
            assertTrue(entries.getValue("moontier-diagnostics.txt").startsWith("generated_at=2026-09-17T01:02:00+08:00"))
            assertTrue(entries.getValue("moontier-diagnostics.txt").endsWith(
                "\n\n[application log - complete snapshot]\n$expectedApp\n\n[root core log - complete snapshot]\n$expectedCore"))
            assertEquals(appText, app.readText())
            assertEquals(coreText, core.readText())
        } finally { dir.deleteRecursively() }
    }

    @Test fun fullExportDoesNotUseOrModifyThePreviewTail() {
        val dir = File("build/test-log-fixtures/${UUID.randomUUID()}").apply { mkdirs() }
        try {
            val app = File(dir, "app.log").apply { writeText("APP-BEGIN\nAPP-END\n") }
            val bytes = ("CORE-BEGIN\n" + "0123456789abcdef\n".repeat(130000) + "CORE-END\n").toByteArray()
            val core = File(dir, "core.log").apply { writeBytes(bytes) }
            assertEquals(LogFiles.PREVIEW_BYTES, LogFiles.tail(core).toByteArray().size)
            assertEquals(bytes.size.toLong(), core.length())
            val output = ByteArrayOutputStream()
            DiagnosticsArchive.write(output, dir, "diagnostic header", core, 1) { LogFiles.snapshot(app, it) }
            val entries = LinkedHashMap<String, ByteArray>()
            ZipInputStream(output.toByteArray().inputStream()).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    entries[entry.name] = zip.readBytes()
                }
            }
            assertEquals(setOf("moontier-app.log", "moontier-root-core.log", "moontier-diagnostics.txt"), entries.keys)
            assertArrayEquals(app.readBytes(), entries.getValue("moontier-app.log"))
            assertArrayEquals(bytes, entries.getValue("moontier-root-core.log"))
            val report = entries.getValue("moontier-diagnostics.txt").toString(Charsets.UTF_8)
            assertTrue(report.startsWith("diagnostic header"))
            assertTrue(report.contains(app.readText()))
            assertTrue(report.endsWith(bytes.toString(Charsets.UTF_8)))
            assertEquals(bytes.size.toLong(), core.length())
            assertEquals(setOf("app.log", "core.log"), dir.list()!!.toSet())
        } finally { dir.deleteRecursively() }
    }

    @Test fun missingLogsProduceExplicitEmptyRawEntries() {
        val dir = File("build/test-log-fixtures/${UUID.randomUUID()}").apply { mkdirs() }
        try {
            val output = ByteArrayOutputStream()
            DiagnosticsArchive.write(output, dir, "logging disabled", File(dir, "absent"), 1) { LogFiles.snapshot(null, it) }
            ZipInputStream(output.toByteArray().inputStream()).use { zip ->
                repeat(2) { assertNotNull(zip.nextEntry); assertEquals(0, zip.readBytes().size) }
                assertEquals("moontier-diagnostics.txt", zip.nextEntry.name)
            }
        } finally { dir.deleteRecursively() }
    }
}
