package cn.moonflow.easytier

import org.json.JSONArray
import org.json.JSONObject

/** Typed projection of the official manager API; the transport lives in RootRpc. */
internal class RootRpcManager(private val rpc: RootRpc = RootRpc()) : AutoCloseable {
    override fun close() = rpc.close()

    fun list(): List<RpcMessage> = rpc.call(4).messages(1)

    fun delete(id: String) { rpc.call(5, RpcMessage.Builder().message(1, RpcMessage.uuid(id)).build()) }

    fun run(config: NetworkConfig) {
        rpc.call(1, RpcMessage.Builder().message(1, RpcMessage.uuid(config.id))
            .message(2, networkConfig(config)).number(3, 1).number(4, 1).build(), 15000)
    }

    fun snapshot(): JSONObject {
        val ids = list()
        val metaRequest = RpcMessage.Builder().apply { ids.forEach { message(1, it) } }.build()
        val metas = rpc.call(7, metaRequest).messages(1).map { meta ->
            JSONObject().put("instance_id", meta.message(1).uuid()).put("network_name", meta.text(2))
                .put("instance_name", meta.text(4)).put("source", meta.number(5))
        }
        val map = JSONObject()
        rpc.call(3).message(1).messages(1).forEach { entry ->
            val info = entry.message(2)
            val node = info.message(2)
            val routes = info.messages(4).map { route ->
                JSONObject().put("hostname", route.text(6)).put("ipv4_addr", ipv4(route.message(2)))
                    .put("cost", route.number(4)).put("path_latency", route.number(11))
                    .put("feature_flag", JSONObject().put("is_public_server", route.message(10).number(1) != 0L))
            }
            map.put(entry.text(1), JSONObject()
                .put("my_node_info", JSONObject().put("hostname", node.text(2)).put("virtual_ipv4", ipv4(node.message(1))))
                .put("routes", JSONArray(routes)).put("running", info.number(7) != 0L)
                .put("error_msg", info.text(8))
                .put("events", JSONArray(info.messages(3).takeLast(80).map { event ->
                    // Event details can contain endpoint credentials; show event kind only.
                    val raw = event.data.toString(Charsets.UTF_8)
                    val obj = runCatching { JSONObject(raw) }.getOrNull()
                    obj?.let { "${it.optString("time")} ${it.optJSONObject("event")?.keys()?.asSequence()?.firstOrNull().orEmpty()}" } ?: raw
                })))
        }
        return JSONObject().put("instance_ids", JSONArray(ids.map { it.uuid() }))
            .put("metas", JSONArray(metas)).put("running", JSONObject().put("info", JSONObject().put("map", map)))
    }

    private fun ipv4(value: RpcMessage): JSONObject = JSONObject()
        .put("address", JSONObject().put("addr", value.message(1).number(1)))
        .put("network_length", value.number(2, 24))

    internal fun networkConfig(c: NetworkConfig): RpcMessage = RpcMessage.Builder().apply {
        text(1, c.id); number(2, if (c.dhcp) 1 else 0)
        text(3, c.ipv4.substringBefore('/')); number(4, c.ipv4.substringAfter('/', "24").toLong())
        text(5, c.hostname); text(6, c.networkName); text(7, c.networkSecret)
        number(8, 1); number(16, 1)
        c.peerUrls.cleanItems().forEach { text(10, it) }
        c.proxyCidrs.cleanItems().forEach { text(11, it) }
        c.listenerUrls.cleanItems().forEach { text(17, it) }
        c.relayNetworkWhitelist.cleanItems().forEach { text(31, it) }
        if (c.enableManualRoutes) c.routes.cleanItems().forEach { text(33, it) }
        c.exitNodes.cleanItems().forEach { text(34, it) }
        mapOf(
            19 to c.latencyFirst, 21 to c.useSmoltcp, 22 to c.enableKcpProxy,
            23 to c.disableKcpInput, 24 to c.disableP2p, 25 to c.bindDevice, 26 to c.noTun,
            27 to c.enableExitNode, 28 to c.relayAllPeerRpc, 29 to c.multiThread,
            30 to c.enableRelayNetworkWhitelist, 32 to c.enableManualRoutes,
            35 to c.proxyForwardBySystem, 36 to !c.enableEncryption, 39 to c.disableUdpHolePunching,
            42 to c.enableMagicDns, 43 to c.enablePrivateMode, 45 to c.enableQuicProxy,
            46 to c.disableQuicInput, 47 to c.disableIpv6, 49 to c.disableSymHolePunching,
            51 to c.p2pOnly, 54 to c.disableTcpHolePunching, 58 to c.lazyP2p,
            59 to c.needP2p, 61 to c.disableUpnp
        ).forEach { (field, enabled) -> number(field, if (enabled) 1 else 0) }
        if (c.mtu > 0) number(40, c.mtu.toLong())
        text(53, c.encryptionAlgorithm)
        if (c.aclToml.isNotBlank()) message(56, RootAcl.encode(c.aclToml))
    }.build()
}
