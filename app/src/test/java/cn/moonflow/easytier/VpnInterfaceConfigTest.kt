package cn.moonflow.easytier

import org.junit.Assert.*
import org.junit.Test

class VpnInterfaceConfigTest {
    @Test fun equivalentRoutesAndStatisticsDoNotChangeTunnelIdentity() {
        val config = NetworkConfig()
        val routes = linkedSetOf("172.16.0.0/16", "192.168.1.0/24")
        val a = buildVpnConfigJson(config, "10.0.0.2/24", routes, setOf("8.8.8.8"), true, 2, 1)
        val b = buildVpnConfigJson(config, "10.0.0.2/24", routes.toList().asReversed().toSet(), setOf("8.8.8.8"), true, 2, 2)
        assertEquals(vpnInterfaceSignature(a), vpnInterfaceSignature(b))
        val changed = buildVpnConfigJson(config.copy(mtu = 1300), "10.0.0.2/24", routes, setOf("8.8.8.8"), true, 2, 2)
        assertNotEquals(vpnInterfaceSignature(a), vpnInterfaceSignature(changed))
        val excluded = buildVpnConfigJson(config, "10.0.0.2/24", routes, setOf("1.1.1.1"), true, 2, 1)
        assertNotEquals(vpnInterfaceSignature(a), vpnInterfaceSignature(excluded))
        assertEquals(vpnInterfaceSignature(a, false), vpnInterfaceSignature(excluded, false))
    }
}
