package cn.moonflow.easytier

import org.junit.Assert.*
import org.junit.Test

class PollingPolicyTest {
    @Test fun backgroundAndLongWaitsCannotUseTheStartupFastLoop() {
        for (age in listOf(0L, 20_000L, 120_000L)) {
            assertEquals(300_000L, PollingPolicy.vpn(300_000, true, age, false))
            assertEquals(300_000L, PollingPolicy.vpn(300_000, true, age, true))
        }
        assertEquals(500L, PollingPolicy.vpn(30_000, true, 0, false))
        assertEquals(30_000L, PollingPolicy.vpn(500, true, 120_000, false))
        assertEquals(30_000L, PollingPolicy.vpn(30_000, false, 0, false))
        assertEquals(30_000L, PollingPolicy.root(false, true, true, true, 0, 0))
        assertEquals(60_000L, PollingPolicy.root(true, true, false, false, 0, 0))
        assertEquals(60_000L, PollingPolicy.root(true, true, true, false, 0, 20))
    }
}
