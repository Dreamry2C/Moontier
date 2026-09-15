package cn.moonflow.easytier

import java.net.URI

internal object ConfigServerStatus {
    fun configured(value: String): Boolean = runCatching {
        val uri = URI(value.trim())
        uri.scheme?.lowercase() in setOf("tcp", "udp", "ws", "wss") && !uri.host.isNullOrBlank() &&
            (uri.port in 1..65535 || (uri.port == -1 && uri.scheme in setOf("ws", "wss"))) &&
            uri.path.orEmpty().trim('/').isNotBlank()
    }.getOrDefault(false)

    data class Evidence(val connected: Boolean = false, val error: String = "", val logs: List<String> = emptyList())

    fun evidence(enabled: Boolean, url: String, lines: List<String>): Evidence {
        if (!enabled || !configured(url)) return Evidence()
        // Ignore every previous Core run, including old successful connections.
        val session = lines.drop(lines.indexOfLast { it.startsWith("--- MoonTier manager start ") } + 1)
        val relevant = session.filter {
            it.contains("easytier::web_client::") || (it.contains("CORE:") && (
                it.contains("Successfully connected to ") || it.contains("Failed to connect to the server") ||
                it.contains("Failed to reconnect secure tunnel") || it.contains("Noise handshake failed") ||
                it.contains("secure-mode enabled but") || it.contains("GetFeature rpc")))
        }
        val success = relevant.indexOfLast { it.contains("Successfully connected to ") }
        val failure = relevant.indexOfLast {
            it.contains("Failed to connect to the server") || it.contains("Failed to reconnect secure tunnel") ||
                it.contains("Noise handshake failed") || it.contains("heartbeat failed") || it.contains("secure-mode enabled but")
        }
        return Evidence(
            connected = success >= 0 && success > failure,
            error = if (failure >= 0 && failure > success) "网页控制台连接失败，核心正在重试" else "",
            logs = relevant.takeLast(80)
        )
    }
}
