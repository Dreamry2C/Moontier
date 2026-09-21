package cn.moonflow.easytier

import org.junit.Assert.*
import org.junit.Test

class RootProcessStatusTest {
    @Test fun failedRootQueriesAreNeverClassifiedAsStopped() {
        assertEquals(42L, RootProcessStatus.parse(ShellResult(0, "vendor banner\nRUNNING 42\n")))
        assertEquals(0L, RootProcessStatus.parse(ShellResult(0, "STOPPED\n")))
        for (result in listOf(ShellResult(1, "Permission denied"), ShellResult(-1, "timeout", true),
            ShellResult(0, ""), ShellResult(0, "RUNNING 0"), ShellResult(0, "unexpected"))) {
            assertThrows(IllegalStateException::class.java) { RootProcessStatus.parse(result) }
        }
    }
}
