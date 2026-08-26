package cn.moonflow.easytier

object Ipv4 {

    private val addressLiteralPattern = Regex("""(?<![0-9.])(\d{1,3}(?:\.\d{1,3}){3})(?![0-9.])""")

    fun intToAddress(value: Long): String {
        val v = value and 0xFFFFFFFFL
        return "${(v ushr 24) and 255}.${(v ushr 16) and 255}.${(v ushr 8) and 255}.${v and 255}"
    }

    fun parseAddress(ip: String): Long? {
        val parts = ip.trim().split(".")
        if (parts.size != 4) return null
        var out = 0L
        for (part in parts) {
            val octet = part.toIntOrNull() ?: return null
            if (octet !in 0..255) return null
            out = (out shl 8) or octet.toLong()
        }
        return out
    }

    fun hostFromUrl(value: String): String {
        val trimmed = value.trim()
        return trimmed
            .substringAfter("://", trimmed)
            .substringBefore("/")
            .substringBeforeLast(":", trimmed)
            .trim('[', ']')
    }

    fun addressLiterals(value: String): Set<String> =
        addressLiteralPattern.findAll(value)
            .map { it.groupValues[1] }
            .filter { parseAddress(it) != null }
            .toCollection(LinkedHashSet())

    fun cidrToRange(cidr: String): LongRange? {
        val parts = cidr.split("/")
        if (parts.size != 2) return null
        val base = parseAddress(parts[0]) ?: return null
        val prefix = parts[1].toIntOrNull() ?: return null
        if (prefix !in 0..32) return null
        val mask = if (prefix == 0) 0L else (0xFFFFFFFFL shl (32 - prefix)) and 0xFFFFFFFFL
        val start = base and mask
        val end = start or (mask xor 0xFFFFFFFFL)
        return start..end
    }

    fun subnetRoute(cidr: String): String? {
        val parts = cidr.split("/")
        if (parts.size != 2) return null
        val range = cidrToRange(cidr) ?: return null
        val prefix = parts[1].toIntOrNull() ?: return null
        return "${intToAddress(range.first)}/$prefix"
    }

}

fun buildVpnConfigJson(
    config: NetworkConfig,
    cidr: String,
    peerProxyCidrs: Set<String>,
    directCoreIps: Set<String>,
    exitNodeAutoRoutes: Boolean,
    exitNodeConfiguredCount: Int = config.exitNodes.count { it.isNotBlank() },
    exitNodeReachableCount: Int = 0
): String {
    val parts = cidr.split("/")
    val address = parts.getOrNull(0).orEmpty().ifBlank { "10.0.0.2" }
    val prefix = parts.getOrNull(1)?.toIntOrNull()?.coerceIn(1, 32) ?: 24
    val routes = LinkedHashSet<String>()

    Ipv4.subnetRoute("$address/$prefix")?.let { routes += it }
    if (config.enableManualRoutes) {
        routes += config.routes.cleanItems()
    } else {
        routes += config.proxyCidrs.cleanItems()
        routes += peerProxyCidrs
    }
    if (exitNodeAutoRoutes) {
        routes += "0.0.0.0/0"
    }
    if (config.enableMagicDns) routes += "100.100.100.101/32"

    return org.json.JSONObject()
        .put("instanceName", config.instanceName)
        .put("address", address)
        .put("prefixLength", prefix)
        .put("mtu", if (config.mtu > 0) config.mtu else 1400)
        .put("dns", if (config.enableMagicDns) "100.100.100.101" else "")
        .put("exitNodeAutoRoutes", exitNodeAutoRoutes)
        .put("exitNodeCount", exitNodeConfiguredCount)
        .put("exitNodeReachableCount", exitNodeReachableCount)
        .put("directCoreIps", directCoreIps.toList().toJsonArray())
        .put("routes", routes.toList().cleanItems().toJsonArray())
        .toString()
}
