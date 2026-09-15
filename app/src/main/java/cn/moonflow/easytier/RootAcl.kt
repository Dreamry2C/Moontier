package cn.moonflow.easytier

import org.tomlj.Toml
import org.tomlj.TomlArray
import org.tomlj.TomlTable

/** Preserve imported ACLs when creating an instance through the official manager API. */
internal object RootAcl {
    private val schemas = mapOf(
        "acl" to mapOf("acl_v1" to 2),
        "acl_v1" to mapOf("chains" to 1, "group" to 2),
        "chains" to mapOf("name" to 1, "chain_type" to 2, "description" to 3, "enabled" to 4, "rules" to 5, "default_action" to 6),
        "rules" to mapOf("name" to 1, "description" to 2, "priority" to 3, "enabled" to 4, "protocol" to 5,
            "ports" to 6, "source_ips" to 7, "destination_ips" to 8, "source_ports" to 9, "action" to 10,
            "rate_limit" to 11, "burst_limit" to 12, "stateful" to 13, "source_groups" to 14, "destination_groups" to 15),
        "group" to mapOf("declares" to 1, "members" to 2),
        "declares" to mapOf("group_name" to 1, "group_secret" to 2)
    )

    fun encode(text: String): RpcMessage {
        val parsed = Toml.parse(text)
        require(!parsed.hasErrors()) { "ACL TOML 格式无效" }
        return encodeTable("acl", requireNotNull(parsed.getTable("acl")) { "缺少 ACL 配置" })
    }

    private fun encodeTable(type: String, table: TomlTable): RpcMessage {
        val schema = schemas.getValue(type)
        return RpcMessage.Builder().apply {
            for (key in table.keySet()) {
                val field = requireNotNull(schema[key]) { "不支持的 ACL 字段：$key" }
                fun value(item: Any?) {
                    when (item) {
                        is String -> text(field, item)
                        is Long -> number(field, item)
                        is Boolean -> number(field, if (item) 1 else 0)
                        is TomlTable -> message(field, encodeTable(key, item))
                        else -> error("ACL 字段类型无效：$key")
                    }
                }
                val item = table.get(key)
                if (item is TomlArray) for (index in 0 until item.size()) value(item.get(index)) else value(item)
            }
        }.build()
    }
}
