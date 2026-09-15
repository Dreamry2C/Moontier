package cn.moonflow.easytier

import org.junit.Assert.*
import org.junit.Test

class ConfigServerStatusTest {
    private val url = "tcp://127.0.0.1:22020/test"
    @Test fun validatesCompleteEndpoints() {
        assertTrue(ConfigServerStatus.configured(url))
        assertTrue(ConfigServerStatus.configured("wss://example.com/api/token"))
        listOf("", "tcp://", "tcp://host/token", "udp://host:22020", "http://host:22020/token", "tcp://host:99999/token").forEach {
            assertFalse(it, ConfigServerStatus.configured(it))
        }
    }
    @Test fun localRpcAndPeerFailuresCannotEnableOrFailTheConsole() {
        val noise = listOf("WARN easytier::proto::rpc_impl::bidirect: peer rpc transport read aborted, exiting",
            "ERROR easytier::tunnel::tcp: Failed to connect", "WARN easytier::common::stun: lookup host for stun failed")
        assertEquals(ConfigServerStatus.Evidence(), ConfigServerStatus.evidence(true, url, noise))
        val actualFailure = noise + "WARN CORE: Failed to connect to the server, retrying in 1 seconds..."
        assertEquals(ConfigServerStatus.Evidence(), ConfigServerStatus.evidence(false, url, actualFailure))
        assertEquals(ConfigServerStatus.Evidence(), ConfigServerStatus.evidence(true, "", actualFailure))
        assertTrue(ConfigServerStatus.evidence(true, url, actualFailure).error.isNotEmpty())
    }
    @Test fun usesOnlyTheCurrentSessionAndSpecificWebConnectionEvents() {
        val success = "INFO CORE: Successfully connected to Some(TunnelInfo)"
        assertTrue(ConfigServerStatus.evidence(true, url, listOf(success)).connected)
        assertFalse(ConfigServerStatus.evidence(true, url, listOf(success, "ERROR easytier::web_client::session: heartbeat failed: Timeout")).connected)
        assertFalse(ConfigServerStatus.evidence(true, url, listOf(success, "--- MoonTier manager start now ---")).connected)
    }
}
