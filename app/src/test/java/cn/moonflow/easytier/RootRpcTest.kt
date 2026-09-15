package cn.moonflow.easytier

import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.UUID

class RootRpcTest {
    @Test fun rejectsMalformedElfAndDetectsNativeArchitecture() {
        assertNull(RootArchitecture.fromHeader("not an executable".toByteArray()))
        val header = ByteArray(20)
        byteArrayOf(127, 69, 76, 70, 2, 1).copyInto(header)
        header[18] = 62
        assertEquals(RootArchitecture.X86_64, RootArchitecture.fromHeader(header))
        header[18] = 183.toByte()
        assertEquals(RootArchitecture.ARM64, RootArchitecture.fromHeader(header))
        header[4] = 1
        assertNull(RootArchitecture.fromHeader(header))
    }

    @Test fun protobufHandlesUnsignedUuidAndUnknownFields() {
        val id = "ffffffff-ffff-ffff-ffff-ffffffffffff"
        assertEquals(id, RpcMessage.uuid(id).uuid())
        val encoded = RpcMessage.Builder().text(1, "中文").number(127, -1L).number(2, 0xffffffffL).build()
        assertEquals("中文", encoded.text(1))
        assertEquals(0xffffffffL, encoded.number(2))
        assertEquals(-1L, encoded.number(127))
        assertThrows(IllegalArgumentException::class.java) { RpcMessage(byteArrayOf(10, 127)).text(1) }
    }

    /** Optional real Core round trip. Forward only an isolated test Core with no peers. */
    @Test fun officialCoreLifecycleAndPersistentConnection() {
        val port = System.getenv("MOONTIER_TEST_RPC_PORT")?.toIntOrNull()
        assumeTrue("No isolated official Core configured", port != null)
        val rpc = RootRpc(port!!)
        val manager = RootRpcManager(rpc)
        val id = UUID.randomUUID().toString()
        val config = NetworkConfig(id = id, networkName = "moontier-local-test", networkSecret = "", dhcp = false,
            ipv4 = "10.254.253.1", hostname = "moontier-rpc-test", peerUrls = emptyList(), listenerUrls = emptyList(),
            noTun = true, disableUdpHolePunching = true, disableTcpHolePunching = true, disableIpv6 = true)
        try {
            assertTrue(manager.list().isEmpty())
            // Force multi-fragment responses through a long hostname in ValidateConfig.
            val large = config.copy(hostname = "x".repeat(6000), aclToml = """
                [[acl.acl_v1.chains]]
                name = "test-forward"
                chain_type = 3
                enabled = true
                default_action = 2
                [[acl.acl_v1.chains.rules]]
                name = "test-allow"
                enabled = true
                protocol = 5
                action = 1
                source_ips = ["10.254.253.2/32"]
            """.trimIndent())
            val validated = rpc.call(0, RpcMessage.Builder().message(1, manager.networkConfig(large)).build())
            assertTrue(validated.text(1).contains(large.hostname))
            assertTrue(validated.text(1).contains("test-allow"))
            assertTrue(validated.text(1).contains("10.254.253.2/32"))
            manager.run(config)
            repeat(5) { assertEquals(id, manager.list().single().uuid()) }
            val snapshot = manager.snapshot()
            assertEquals(id, snapshot.getJSONArray("instance_ids").getString(0))
            assertTrue(snapshot.getJSONObject("running").getJSONObject("info").getJSONObject("map").has(id))
            manager.delete(id)
            assertTrue(manager.list().isEmpty())
        } finally {
            runCatching { manager.delete(id) }
            manager.close()
        }
    }
}
