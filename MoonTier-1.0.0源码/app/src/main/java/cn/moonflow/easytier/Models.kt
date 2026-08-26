package cn.moonflow.easytier

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class NetworkConfig(
    val id: String = UUID.randomUUID().toString(),
    val label: String = "默认配置",
    val instanceName: String = "MoonTier-Default",
    val hostname: String = "",
    val networkName: String = "default",
    val networkSecret: String = "",
    val dhcp: Boolean = true,
    val ipv4: String = "",
    val peerUrls: List<String> = emptyList(),
    val stunServers: List<String> = emptyList(),
    val listenerUrls: List<String> = emptyList(),
    val proxyCidrs: List<String> = emptyList(),
    val enableManualRoutes: Boolean = false,
    val routes: List<String> = emptyList(),
    val exitNodes: List<String> = emptyList(),
    val enableRelayNetworkWhitelist: Boolean = false,
    val relayNetworkWhitelist: List<String> = emptyList(),
    val proxyForwardBySystem: Boolean = false,
    val enableExitNode: Boolean = false,
    val enableMagicDns: Boolean = false,
    val enablePrivateMode: Boolean = true,
    val enableEncryption: Boolean = true,
    val enableKcpProxy: Boolean = false,
    val disableKcpInput: Boolean = false,
    val enableQuicProxy: Boolean = false,
    val disableQuicInput: Boolean = false,
    val disableRelayKcp: Boolean = false,
    val disableRelayQuic: Boolean = false,
    val enableRelayForeignNetworkKcp: Boolean = false,
    val enableRelayForeignNetworkQuic: Boolean = false,
    val latencyFirst: Boolean = false,
    val noTun: Boolean = false,
    val useSmoltcp: Boolean = false,
    val bindDevice: Boolean = true,
    val multiThread: Boolean = true,
    val disableUdpHolePunching: Boolean = false,
    val disableTcpHolePunching: Boolean = false,
    val disableUpnp: Boolean = false,
    val needP2p: Boolean = false,
    val lazyP2p: Boolean = false,
    val p2pOnly: Boolean = false,
    val disableP2p: Boolean = false,
    val disableSymHolePunching: Boolean = false,
    val disableIpv6: Boolean = false,
    val relayAllPeerRpc: Boolean = false,
    val mtu: Int = 0,
    val rpcPortal: String = "",
    val defaultProtocol: String = "tcp",
    val encryptionAlgorithm: String = "aes-gcm",
    val aclToml: String = "",
    val isDefault: Boolean = true,
    val isRunning: Boolean = false
) {
    val displayName: String
        get() = label.ifBlank { networkName.ifBlank { "未命名配置" } }

    fun toJson(): JSONObject = JSONObject()
        .put("config_id", id)
        .put("network_label", label)
        .put("instance_name", instanceName)
        .put("hostname", hostname)
        .put("network_name", networkName)
        .put("network_secret", networkSecret)
        .put("dhcp", dhcp)
        .put("ipv4", ipv4)
        .put("servers", peerUrls.toJsonArray())
        .put("stun_servers", stunServers.toJsonArray())
        .put("listen_addresses", listenerUrls.toJsonArray())
        .put("proxy_networks", proxyCidrs.toJsonArray())
        .put("enable_manual_routes", enableManualRoutes)
        .put("custom_routes", routes.toJsonArray())
        .put("exit_nodes", exitNodes.toJsonArray())
        .put("foreign_network_whitelist_enabled", enableRelayNetworkWhitelist)
        .put("foreign_network_whitelist", relayNetworkWhitelist.toJsonArray())
        .put("system_forwarding", proxyForwardBySystem)
        .put("enable_exit_node", enableExitNode)
        .put("accept_dns", enableMagicDns)
        .put("private_mode", enablePrivateMode)
        .put("enable_encryption", enableEncryption)
        .put("enable_kcp_proxy", enableKcpProxy)
        .put("disable_kcp_input", disableKcpInput)
        .put("enable_quic_proxy", enableQuicProxy)
        .put("disable_quic_input", disableQuicInput)
        .put("disable_relay_kcp", disableRelayKcp)
        .put("disable_relay_quic", disableRelayQuic)
        .put("enable_relay_foreign_network_kcp", enableRelayForeignNetworkKcp)
        .put("enable_relay_foreign_network_quic", enableRelayForeignNetworkQuic)
        .put("latency_first", latencyFirst)
        .put("no_tun", noTun)
        .put("use_smoltcp", useSmoltcp)
        .put("bind_device", bindDevice)
        .put("multi_thread", multiThread)
        .put("disable_udp_hole_punching", disableUdpHolePunching)
        .put("disable_tcp_hole_punching", disableTcpHolePunching)
        .put("disable_upnp", disableUpnp)
        .put("need_p2p", needP2p)
        .put("lazy_p2p", lazyP2p)
        .put("p2p_only", p2pOnly)
        .put("disable_p2p", disableP2p)
        .put("disable_sym_hole_punching", disableSymHolePunching)
        .put("disable_ipv6", disableIpv6)
        .put("relay_all_peer_rpc", relayAllPeerRpc)
        .put("mtu", mtu)
        .put("rpc_portal", rpcPortal)
        .put("default_protocol", defaultProtocol)
        .put("encryption_algorithm", encryptionAlgorithm)
        .put("acl_toml", aclToml)
        .put("is_default", isDefault)
        .put("is_running", isRunning)

    companion object {
        fun defaultConfig(default: Boolean = true): NetworkConfig = NetworkConfig(
            id = UUID.randomUUID().toString(),
            label = "默认配置",
            isDefault = default
        )

        fun fromJson(obj: JSONObject): NetworkConfig = NetworkConfig(
            id = obj.optString("config_id").ifBlank { UUID.randomUUID().toString() },
            label = obj.optString("network_label", "默认配置"),
            instanceName = obj.optString("instance_name", "MoonTier-Default"),
            hostname = obj.optString("hostname", ""),
            networkName = obj.optString("network_name", "default"),
            networkSecret = obj.optString("network_secret", ""),
            dhcp = obj.optBoolean("dhcp", true),
            ipv4 = obj.optString("ipv4", ""),
            peerUrls = obj.optStringArrayCompat("peer_urls", "servers"),
            stunServers = obj.optStringArrayCompat("stun_servers", "stunServers"),
            listenerUrls = obj.optStringArrayCompat("listener_urls", "listen_addresses"),
            proxyCidrs = obj.optStringArrayCompat("proxy_cidrs", "proxy_networks"),
            enableManualRoutes = obj.optBoolean("enable_manual_routes", false),
            routes = obj.optStringArrayCompat("routes", "custom_routes"),
            exitNodes = obj.optStringArray("exit_nodes"),
            enableRelayNetworkWhitelist = obj.optBooleanCompat(
                "enable_relay_network_whitelist",
                "foreign_network_whitelist_enabled",
                false
            ),
            relayNetworkWhitelist = obj.optStringArrayCompat(
                "relay_network_whitelist",
                "foreign_network_whitelist"
            ),
            proxyForwardBySystem = obj.optBooleanCompat(
                "proxy_forward_by_system",
                "system_forwarding",
                false
            ),
            enableExitNode = obj.optBoolean("enable_exit_node", false),
            enableMagicDns = obj.optBooleanCompat("enable_magic_dns", "accept_dns", false),
            enablePrivateMode = obj.optBooleanCompat("enable_private_mode", "private_mode", true),
            enableEncryption = obj.optBoolean("enable_encryption", true),
            enableKcpProxy = obj.optBoolean("enable_kcp_proxy", false),
            disableKcpInput = obj.optBoolean("disable_kcp_input", false),
            enableQuicProxy = obj.optBoolean("enable_quic_proxy", false),
            disableQuicInput = obj.optBoolean("disable_quic_input", false),
            disableRelayKcp = obj.optBoolean("disable_relay_kcp", false),
            disableRelayQuic = obj.optBoolean("disable_relay_quic", false),
            enableRelayForeignNetworkKcp = obj.optBoolean("enable_relay_foreign_network_kcp", false),
            enableRelayForeignNetworkQuic = obj.optBoolean("enable_relay_foreign_network_quic", false),
            latencyFirst = obj.optBoolean("latency_first", false),
            noTun = obj.optBoolean("no_tun", false),
            useSmoltcp = obj.optBoolean("use_smoltcp", false),
            bindDevice = obj.optBoolean("bind_device", true),
            multiThread = obj.optBoolean("multi_thread", true),
            disableUdpHolePunching = obj.optBoolean("disable_udp_hole_punching", false),
            disableTcpHolePunching = obj.optBoolean("disable_tcp_hole_punching", false),
            disableUpnp = obj.optBoolean("disable_upnp", false),
            needP2p = obj.optBoolean("need_p2p", false),
            lazyP2p = obj.optBoolean("lazy_p2p", false),
            p2pOnly = obj.optBoolean("p2p_only", false),
            disableP2p = obj.optBoolean("disable_p2p", false),
            disableSymHolePunching = obj.optBoolean("disable_sym_hole_punching", false),
            disableIpv6 = obj.optBoolean("disable_ipv6", false),
            relayAllPeerRpc = obj.optBoolean("relay_all_peer_rpc", false),
            mtu = obj.optInt("mtu", 0),
            rpcPortal = obj.optString("rpc_portal", ""),
            defaultProtocol = obj.optString("default_protocol", "tcp"),
            encryptionAlgorithm = obj.optString("encryption_algorithm", "aes-gcm"),
            aclToml = obj.optString("acl_toml", ""),
            isDefault = obj.optBoolean("is_default", false),
            isRunning = obj.optBoolean("is_running", false)
        )
    }
}

data class ServerEntry(
    val name: String,
    val address: String
) {
    fun toJson(): JSONObject = JSONObject()
        .put("name", name)
        .put("address", address)

    companion object {
        fun fromJson(obj: JSONObject): ServerEntry {
            val address = obj.optString("address").trim()
            return ServerEntry(
                name = obj.optString("name").ifBlank { serverNameFromAddress(address) },
                address = address
            )
        }
    }
}

fun nextServerName(existing: Collection<ServerEntry>, base: String = "EasyTier 服务器"): String =
    nextServerNameFromNames(existing.map { it.name }, base)

fun nextServerNameFromNames(existingNames: Collection<String>, base: String = "EasyTier 服务器"): String {
    val cleanBase = base.trim().ifBlank { "EasyTier 服务器" }
    val used = existingNames.map { it.trim().lowercase() }.toHashSet()
    if (cleanBase.lowercase() !in used) return cleanBase

    var index = 1
    while ("$cleanBase $index".lowercase() in used) index++
    return "$cleanBase $index"
}

data class AppSettings(
    val autoSyncOfficialServers: Boolean = false,
    val exitNodeAutoRoutes: Boolean = false,
    val darkMode: Boolean = false,
    val rootModeEnabled: Boolean = false,
    val coreAutoUpdate: Boolean = false,
    val coreLogLevel: String = CoreLogLevel.OFF,
    val configServerUrl: String = "",
    val configServerHostname: String = "",
    val configServerMachineId: String = "",
    val configServerSecureMode: Boolean = true,
    val configServerAutoConnect: Boolean = false,
    val bootAutoStart: Boolean = false
) {
    fun toJson(): JSONObject = JSONObject()
        .put("auto_sync_official_servers", autoSyncOfficialServers)
        .put("exit_node_auto_routes", exitNodeAutoRoutes)
        .put("dark_mode", darkMode)
        .put("root_mode_enabled", rootModeEnabled)
        .put("core_auto_update", coreAutoUpdate)
        .put("core_log_level", CoreLogLevel.normalize(coreLogLevel))
        .put("config_server_url", configServerUrl)
        .put("config_server_hostname", configServerHostname)
        .put("config_server_machine_id", configServerMachineId)
        .put("config_server_secure_mode", configServerSecureMode)
        .put("config_server_auto_connect", configServerAutoConnect)
        .put("boot_auto_start", bootAutoStart)

    companion object {
        fun fromJson(obj: JSONObject): AppSettings = AppSettings(
            autoSyncOfficialServers = false,
            exitNodeAutoRoutes = obj.optBoolean("exit_node_auto_routes", false),
            darkMode = obj.optBoolean("dark_mode", false),
            rootModeEnabled = obj.optBoolean("root_mode_enabled", false),
            coreAutoUpdate = obj.optBoolean("core_auto_update", false),
            coreLogLevel = CoreLogLevel.normalize(obj.optString("core_log_level", CoreLogLevel.OFF)),
            configServerUrl = obj.optString("config_server_url", ""),
            configServerHostname = obj.optString("config_server_hostname", ""),
            configServerMachineId = obj.optString("config_server_machine_id", ""),
            configServerSecureMode = obj.optBoolean("config_server_secure_mode", true),
            configServerAutoConnect = obj.optBoolean("config_server_auto_connect", false),
            bootAutoStart = obj.optBoolean("boot_auto_start", false)
        )
    }
}

data class NodeInfo(
    val hostname: String,
    val ip: String,
    val role: String,
    val latencyMs: Int,
    val local: Boolean
)

data class RuntimeState(
    val running: Boolean = false,
    val starting: Boolean = false,
    val stopping: Boolean = false,
    val runningConfigId: String = "",
    val statusText: String = "EasyTier 未运行",
    val localCidr: String = "",
    val nodes: List<NodeInfo> = emptyList(),
    val logs: List<String> = emptyList()
)

data class RootInstanceState(
    val configId: String = "",
    val displayName: String = "",
    val instanceName: String = "",
    val pid: Long = 0,
    val running: Boolean = false,
    val starting: Boolean = false,
    val stopping: Boolean = false,
    val localCidr: String = "",
    val nodes: List<NodeInfo> = emptyList(),
    val logs: List<String> = emptyList(),
    val error: String = ""
)

data class RootCoreState(
    val ready: Boolean = false,
    val installedVersion: String = "",
    val latestVersion: String = "",
    val checking: Boolean = false,
    val installing: Boolean = false,
    val progress: Int = 0,
    val message: String = ""
)

data class RootManagedNetworkState(
    val instanceId: String = "",
    val instanceName: String = "",
    val localCidr: String = "",
    val nodes: List<NodeInfo> = emptyList()
)

data class RootConfigServerState(
    val pid: Long = 0,
    val running: Boolean = false,
    val starting: Boolean = false,
    val stopping: Boolean = false,
    val connected: Boolean = false,
    val serverUrl: String = "",
    val managedNetworks: List<RootManagedNetworkState> = emptyList(),
    val logs: List<String> = emptyList(),
    val error: String = ""
)

data class RootTierState(
    val core: RootCoreState = RootCoreState(),
    val instances: List<RootInstanceState> = emptyList(),
    val configServer: RootConfigServerState = RootConfigServerState()
)

fun List<String>.cleanItems(): List<String> =
    map { it.trim() }.filter { it.isNotEmpty() }.distinctBy { it.lowercase() }

fun JSONArray.toStringList(): List<String> {
    val out = ArrayList<String>()
    for (i in 0 until length()) {
        val value = optString(i).trim()
        if (value.isNotEmpty()) out.add(value)
    }
    return out.cleanItems()
}

fun JSONObject.optStringArray(key: String): List<String> =
    optJSONArray(key)?.toStringList().orEmpty()

fun JSONObject.optStringArrayCompat(primary: String, fallback: String): List<String> =
    optJSONArray(primary)?.toStringList()
        ?: optJSONArray(fallback)?.toStringList()
        ?: emptyList()

fun JSONObject.optBooleanCompat(primary: String, fallback: String, default: Boolean): Boolean =
    when {
        has(primary) -> optBoolean(primary, default)
        has(fallback) -> optBoolean(fallback, default)
        else -> default
    }

fun List<String>.toJsonArray(): JSONArray {
    val arr = JSONArray()
    cleanItems().forEach { arr.put(it) }
    return arr
}

fun serverNameFromAddress(address: String): String {
    val trimmed = address.trim()
    val withoutScheme = trimmed.substringAfter("://", trimmed)
    val hostPort = withoutScheme.substringBefore("/")
    val host = if (hostPort.startsWith("[")) {
        hostPort.substringAfter("[").substringBefore("]")
    } else {
        hostPort.substringBeforeLast(":", hostPort)
    }
    return host.ifBlank { "EasyTier 服务器" }
}
