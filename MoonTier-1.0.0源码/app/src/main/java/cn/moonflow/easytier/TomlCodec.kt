package cn.moonflow.easytier

import android.util.Log

object TomlCodec {
    fun build(config: NetworkConfig): String {
        val out = ArrayList<String>()
        out += "instance_id = \"${esc(config.id)}\""
        out += "network_label = \"${esc(config.label)}\""
        out += "instance_name = \"${esc(config.instanceName)}\""
        if (config.rpcPortal.isNotBlank()) out += "rpc_portal = \"${esc(config.rpcPortal)}\""
        if (config.hostname.isNotBlank()) out += "hostname = \"${esc(config.hostname)}\""
        out += "dhcp = ${config.dhcp}"
        if (!config.dhcp && config.ipv4.isNotBlank()) out += "ipv4 = \"${esc(config.ipv4)}\""
        if (config.listenerUrls.isNotEmpty()) out += "listeners = ${array(config.listenerUrls)}"
        if (config.stunServers.isNotEmpty()) out += "stun_servers = ${array(config.stunServers)}"
        if (config.enableManualRoutes) out += "routes = ${array(config.routes)}"
        if (config.exitNodes.isNotEmpty()) out += "exit_nodes = ${array(config.exitNodes)}"

        out += ""
        out += "[network_identity]"
        out += "network_name = \"${esc(config.networkName)}\""
        out += "network_secret = \"${esc(config.networkSecret)}\""

        out += ""
        out += "[flags]"
        out += "enable_encryption = ${config.enableEncryption}"
        out += "enable_ipv6 = ${!config.disableIpv6}"
        out += "latency_first = ${config.latencyFirst}"
        out += "enable_exit_node = ${config.enableExitNode}"
        out += "no_tun = ${config.noTun}"
        out += "use_smoltcp = ${config.useSmoltcp}"
        out += "enable_kcp_proxy = ${config.enableKcpProxy}"
        out += "disable_kcp_input = ${config.disableKcpInput}"
        out += "enable_quic_proxy = ${config.enableQuicProxy}"
        out += "disable_quic_input = ${config.disableQuicInput}"
        out += "disable_relay_kcp = ${config.disableRelayKcp}"
        out += "disable_relay_quic = ${config.disableRelayQuic}"
        out += "enable_relay_foreign_network_kcp = ${config.enableRelayForeignNetworkKcp}"
        out += "enable_relay_foreign_network_quic = ${config.enableRelayForeignNetworkQuic}"
        out += "bind_device = ${config.bindDevice}"
        out += "private_mode = ${config.enablePrivateMode}"
        out += "disable_p2p = ${config.disableP2p}"
        out += "need_p2p = ${config.needP2p}"
        out += "lazy_p2p = ${config.lazyP2p}"
        out += "p2p_only = ${config.p2pOnly}"
        out += "multi_thread = ${config.multiThread}"
        out += "accept_dns = ${config.enableMagicDns}"
        out += "disable_sym_hole_punching = ${config.disableSymHolePunching}"
        out += "relay_all_peer_rpc = ${config.relayAllPeerRpc}"
        out += "disable_udp_hole_punching = ${config.disableUdpHolePunching}"
        out += "disable_tcp_hole_punching = ${config.disableTcpHolePunching}"
        out += "disable_upnp = ${config.disableUpnp}"
        out += "proxy_forward_by_system = ${config.proxyForwardBySystem}"
        if (config.mtu > 0) out += "mtu = ${config.mtu}"
        if (config.enableRelayNetworkWhitelist) {
            out += "relay_network_whitelist = \"${esc(config.relayNetworkWhitelist.cleanItems().joinToString(" "))}\""
        }
        out += "default_protocol = \"${esc(config.defaultProtocol)}\""
        out += "encryption_algorithm = \"${esc(config.encryptionAlgorithm)}\""

        config.peerUrls.cleanItems().forEach {
            out += ""
            out += "[[peer]]"
            out += "uri = \"${esc(it)}\""
        }

        config.proxyCidrs.cleanItems().forEach {
            out += ""
            out += "[[proxy_network]]"
            out += "cidr = \"${esc(it)}\""
        }
        val aclToml = config.aclToml.trim()
        if (aclToml.isNotBlank()) {
            out += ""
            out += aclToml
        }
        return out.joinToString("\n")
    }

    fun parse(text: String): NetworkConfig {
        var currentSection = ""
        val root = LinkedHashMap<String, String>()
        val network = LinkedHashMap<String, String>()
        val flags = LinkedHashMap<String, String>()
        val peers = ArrayList<String>()
        val proxyCidrs = ArrayList<String>()
        val aclToml = extractAclToml(text)

        logicalLines(text).forEach { raw ->
            val line = raw.trim()
            if (line.startsWith("[[") && line.endsWith("]]")) {
                currentSection = line.removePrefix("[[").removeSuffix("]]").trim()
                return@forEach
            }
            if (line.startsWith("[") && line.endsWith("]")) {
                currentSection = line.removePrefix("[").removeSuffix("]").trim()
                return@forEach
            }

            val index = line.indexOf('=')
            if (index <= 0) return@forEach
            val key = line.substring(0, index).trim()
            val value = line.substring(index + 1).trim()
            when (currentSection) {
                "" -> root[key] = value
                "network_identity" -> network[key] = value
                "flags" -> flags[key] = value
                "peer" -> if (key == "uri") peers += scalar(value)
                "proxy_network" -> if (key == "cidr") proxyCidrs += scalar(value)
            }
        }

        val id = scalar(root["instance_id"].orEmpty()).ifBlank { java.util.UUID.randomUUID().toString() }
        val name = scalar(network["network_name"].orEmpty()).ifBlank { scalar(root["network"].orEmpty()).ifBlank { "default" } }
        val relayWhitelistRaw = flags["relay_network_whitelist"]?.let { scalar(it) }
        val relayWhitelist = relayWhitelistRaw.orEmpty()
            .split(Regex("\\s+"))
            .filter { it.isNotBlank() && it != "*" }

        return NetworkConfig(
            id = id,
            label = scalar(root["network_label"].orEmpty()).ifBlank { name.ifBlank { "导入配置" } },
            instanceName = scalar(root["instance_name"].orEmpty()).ifBlank { "MoonTier-${id.take(8)}" },
            hostname = scalar(root["hostname"].orEmpty()),
            networkName = name,
            networkSecret = scalar(network["network_secret"].orEmpty()),
            dhcp = bool(root["dhcp"], true),
            ipv4 = scalar(root["ipv4"].orEmpty()),
            peerUrls = peers.cleanItems(),
            stunServers = strings(root["stun_servers"]),
            listenerUrls = strings(root["listeners"]),
            proxyCidrs = proxyCidrs.cleanItems(),
            enableManualRoutes = root.containsKey("routes"),
            routes = strings(root["routes"]),
            exitNodes = strings(root["exit_nodes"]),
            enableRelayNetworkWhitelist = relayWhitelistRaw != null && relayWhitelistRaw != "*",
            relayNetworkWhitelist = relayWhitelist.cleanItems(),
            proxyForwardBySystem = bool(flags["proxy_forward_by_system"], false),
            enableExitNode = bool(flags["enable_exit_node"], false),
            enableMagicDns = bool(flags["accept_dns"], false),
            enablePrivateMode = bool(flags["private_mode"], true),
            enableEncryption = bool(flags["enable_encryption"], true),
            enableKcpProxy = bool(flags["enable_kcp_proxy"], false),
            disableKcpInput = bool(flags["disable_kcp_input"], false),
            enableQuicProxy = bool(flags["enable_quic_proxy"], false),
            disableQuicInput = bool(flags["disable_quic_input"], false),
            disableRelayKcp = bool(flags["disable_relay_kcp"], false),
            disableRelayQuic = bool(flags["disable_relay_quic"], false),
            enableRelayForeignNetworkKcp = bool(flags["enable_relay_foreign_network_kcp"], false),
            enableRelayForeignNetworkQuic = bool(flags["enable_relay_foreign_network_quic"], false),
            latencyFirst = bool(flags["latency_first"], false),
            noTun = bool(flags["no_tun"], false),
            useSmoltcp = bool(flags["use_smoltcp"], false),
            bindDevice = bool(flags["bind_device"], true),
            multiThread = bool(flags["multi_thread"], true),
            disableUdpHolePunching = bool(flags["disable_udp_hole_punching"], false),
            disableTcpHolePunching = bool(flags["disable_tcp_hole_punching"], false),
            disableUpnp = bool(flags["disable_upnp"], false),
            needP2p = bool(flags["need_p2p"], false),
            lazyP2p = bool(flags["lazy_p2p"], false),
            p2pOnly = bool(flags["p2p_only"], false),
            disableP2p = bool(flags["disable_p2p"], false),
            disableSymHolePunching = bool(flags["disable_sym_hole_punching"], false),
            disableIpv6 = !bool(flags["enable_ipv6"], true),
            relayAllPeerRpc = bool(flags["relay_all_peer_rpc"], false),
            mtu = scalar(flags["mtu"].orEmpty()).toIntOrNull() ?: 0,
            rpcPortal = scalar(root["rpc_portal"].orEmpty()),
            defaultProtocol = scalar(flags["default_protocol"].orEmpty()).ifBlank { "tcp" },
            encryptionAlgorithm = scalar(flags["encryption_algorithm"].orEmpty()).ifBlank { "aes-gcm" },
            aclToml = aclToml,
            isDefault = false
        )
    }

    private fun esc(value: String): String =
        value.replace("\\", "\\\\").replace("\"", "\\\"")

    private fun array(values: List<String>): String =
        values.cleanItems().joinToString(prefix = "[", postfix = "]") { "\"${esc(it)}\"" }

    private fun scalar(value: String): String {
        var text = value.trim()
        if (text.startsWith("\"") && text.endsWith("\"") && text.length >= 2) {
            text = text.substring(1, text.length - 1)
        }
        return text.replace("\\\"", "\"").replace("\\\\", "\\")
    }

    private fun bool(value: String?, default: Boolean): Boolean =
        value?.trim()?.lowercase()?.let {
            when (it) {
                "true" -> true
                "false" -> false
                else -> {
                    Log.w("TomlCodec", "Unexpected bool value: \"$value\", falling back to $default")
                    default
                }
            }
        } ?: default

    private fun strings(value: String?): List<String> {
        val raw = value?.trim().orEmpty()
        if (!raw.startsWith("[") || !raw.endsWith("]")) return emptyList()
        val out = ArrayList<String>()
        var current = StringBuilder()
        var quoted = false
        var escaped = false
        raw.substring(1, raw.length - 1).forEach { ch ->
            if (escaped) {
                current.append(ch)
                escaped = false
                return@forEach
            }
            when {
                ch == '\\' && quoted -> escaped = true
                ch == '"' -> {
                    if (quoted) {
                        out += current.toString()
                        current = StringBuilder()
                    }
                    quoted = !quoted
                }
                quoted -> current.append(ch)
            }
        }
        return out.cleanItems()
    }

    private fun logicalLines(text: String): List<String> {
        val out = ArrayList<String>()
        val current = StringBuilder()
        var brackets = 0
        text.lineSequence().forEach { raw ->
            val line = stripComment(raw).trim()
            if (line.isEmpty()) return@forEach
            if (current.isNotEmpty()) current.append(' ')
            current.append(line)
            brackets += line.count { it == '[' } - line.count { it == ']' }
            if (brackets <= 0) {
                out += current.toString()
                current.clear()
                brackets = 0
            }
        }
        if (current.isNotEmpty()) out += current.toString()
        return out
    }

    private fun extractAclToml(text: String): String {
        val out = ArrayList<String>()
        var capturing = false
        text.lineSequence().forEach { raw ->
            val trimmed = raw.trim()
            if (isSectionHeader(trimmed)) {
                capturing = isAclSectionHeader(trimmed)
            }
            if (capturing) out += raw.trimEnd()
        }
        return out
            .dropWhile { it.isBlank() }
            .dropLastWhile { it.isBlank() }
            .joinToString("\n")
    }

    private fun isSectionHeader(line: String): Boolean =
        (line.startsWith("[[") && line.endsWith("]]")) || (line.startsWith("[") && line.endsWith("]"))

    private fun isAclSectionHeader(line: String): Boolean {
        if (!isSectionHeader(line)) return false
        val section = if (line.startsWith("[[")) {
            line.removePrefix("[[").removeSuffix("]]").trim()
        } else {
            line.removePrefix("[").removeSuffix("]").trim()
        }
        return section.startsWith("acl", ignoreCase = true)
    }

    private fun stripComment(line: String): String {
        var quoted = false
        var escaped = false
        line.forEachIndexed { index, ch ->
            if (escaped) {
                escaped = false
                return@forEachIndexed
            }
            if (ch == '\\' && quoted) {
                escaped = true
                return@forEachIndexed
            }
            if (ch == '"') quoted = !quoted
            if (ch == '#' && !quoted) return line.substring(0, index)
        }
        return line
    }
}
