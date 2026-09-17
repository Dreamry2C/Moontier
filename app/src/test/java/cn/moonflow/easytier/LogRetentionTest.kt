package cn.moonflow.easytier

import java.io.File
import java.util.UUID
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class LogRetentionTest {
    @Test fun oldSettingsKeepExistingDefaultsAndCustomValuesSurviveToggle() {
        val defaults = AppSettings.fromJson(JSONObject())
        assertFalse(defaults.customLogRetention)
        assertEquals(10L * 1024 * 1024, defaults.coreLogLimitBytes())
        assertEquals(512L * 1024, defaults.appLogLimitBytes())
        val custom = defaults.copy(customLogRetention = true, coreLogLimitMiB = "32", appLogLimitMiB = "2.5")
        val restored = AppSettings.fromJson(JSONObject(custom.toJson().toString()))
        assertEquals(custom, restored)
        assertEquals(32L * 1024 * 1024, restored.coreLogLimitBytes())
        assertEquals(2621440L, restored.appLogLimitBytes())
        val disabled = AppSettings.fromJson(restored.copy(customLogRetention = false).toJson())
        assertEquals(defaults.coreLogLimitBytes(), disabled.coreLogLimitBytes())
        assertEquals(defaults.appLogLimitBytes(), disabled.appLogLimitBytes())
        assertEquals("32", disabled.coreLogLimitMiB)
        assertEquals("2.5", disabled.appLogLimitMiB)
    }

    @Test fun zeroAndBlankAreUnlimitedWhileMalformedAndOverflowingValuesAreRejected() {
        val unlimited = AppSettings(customLogRetention = true, coreLogLimitMiB = "0", appLogLimitMiB = "")
        val restored = AppSettings.fromJson(unlimited.toJson())
        assertEquals(0L, restored.coreLogLimitBytes())
        assertEquals(0L, restored.appLogLimitBytes())
        assertEquals(0L, LogRetention.parseMiB("  "))
        assertEquals(524288L, LogRetention.parseMiB("0.5"))
        assertEquals(4294967296L, LogRetention.parseMiB("4096"))
        for (invalid in listOf("-1", "NaN", "Infinity", ".", "1.2.3", "1e10", "99999999999999999")) {
            assertNull(invalid, LogRetention.parseMiB(invalid))
        }
    }

    @Test fun appLogRetainsExactTailAndUnlimitedDoesNotAlterTheFile() {
        val dir = File("build/test-log-fixtures/${UUID.randomUUID()}").apply { mkdirs() }
        try {
            val bytes = ByteArray(3 * 1024 * 1024) { (it % 251).toByte() }
            val log = File(dir, "app.log").apply { writeBytes(bytes) }
            LogFiles.trimToLimit(log, 0)
            assertArrayEquals(bytes, log.readBytes())
            LogFiles.trimToLimit(log, bytes.size.toLong())
            assertArrayEquals(bytes, log.readBytes())
            LogFiles.trimToLimit(log, 2L * 1024 * 1024)
            assertArrayEquals(bytes.copyOfRange(bytes.size - 1024 * 1024, bytes.size), log.readBytes())
            LogFiles.trimToLimit(log, LogRetention.DEFAULT_APP_BYTES)
            assertArrayEquals(bytes.copyOfRange(bytes.size - 256 * 1024, bytes.size), log.readBytes())
        } finally { dir.deleteRecursively() }
    }
}
