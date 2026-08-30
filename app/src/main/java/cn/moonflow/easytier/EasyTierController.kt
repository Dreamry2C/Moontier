package cn.moonflow.easytier

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject

class EasyTierController(
    private val context: Context,
    private val store: ConfigStore,
    private val requestVpn: (String) -> Unit
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var pollJob: Job? = null
    private var runningConfig: NetworkConfig? = null
    private var vpnSignature = ""
    private var directCoreIps = emptySet<String>()
    private var lastLogKey = ""
    private var missedPolls = 0
    private var stalledPolls = 0
    private val _pollIntervalMs = kotlinx.coroutines.flow.MutableStateFlow(30000L)

    var state by mutableStateOf(RuntimeState())
        private set

    fun start(config: NetworkConfig) {
        if (state.starting || state.stopping) return
        Log.i(TAG, "FFI start requested id=${config.id} running=${state.running}")
        AppDiagnostics.event("ffi", "start requested id=${config.id} name=${config.displayName}")
        state = state.copy(starting = true, statusText = "正在启动 ${config.displayName}")
        scope.launch {
            val startedAt = SystemClock.elapsedRealtime()
            val previousConfigId = state.runningConfigId
            if (state.running) stop(wait = true)

            val normalized = config
            val runtimeConfig = normalized.withRuntimeHostname(context)
            runningConfig = runtimeConfig
            directCoreIps = emptySet()
            vpnSignature = ""
            lastLogKey = ""
            missedPolls = 0
            stalledPolls = 0
            state = RuntimeState(starting = true, statusText = "正在启动 ${normalized.displayName}")

            val toml = TomlCodec.build(runtimeConfig)
            val configuredLogLevel = CoreLogLevel.normalize(store.loadSettings().coreLogLevel)
            val parseCode = runCatching {
                withContext(Dispatchers.IO) {
                    NativeEasyTier.setLogLevel(configuredLogLevel)
                    NativeEasyTier.parseConfig(toml)
                }
            }.getOrElse { error ->
                failStart("配置解析异常: ${error.message ?: "未知错误"}")
                return@launch
            }
            if (parseCode != 0) {
                failStart("配置解析失败: ${NativeEasyTier.getLastError().orEmpty()}")
                return@launch
            }

            val code = runCatching {
                withContext(Dispatchers.IO) { NativeEasyTier.runNetworkInstance(toml) }
            }.getOrElse { error ->
                failStart("启动异常: ${error.message ?: "未知错误"}")
                return@launch
            }
            if (code != 0) {
                failStart("启动失败: ${NativeEasyTier.getLastError().orEmpty()}")
                return@launch
            }
            Log.i(TAG, "FFI core ${runtimeConfig.id} started in ${SystemClock.elapsedRealtime() - startedAt}ms")
            AppDiagnostics.event("ffi", "core started id=${runtimeConfig.id} level=$configuredLogLevel elapsed=${SystemClock.elapsedRealtime() - startedAt}ms")

            state = state.copy(
                running = true,
                starting = false,
                runningConfigId = runtimeConfig.id,
                statusText = "实例运行中: ${normalized.displayName}",
                logs = listOf("EasyTier 日志级别: ${CoreLogLevel.label(configuredLogLevel)}", "网络实例启动成功")
            )
            if (previousConfigId.isNotBlank() && previousConfigId != runtimeConfig.id) {
                store.updateConfigRunningState(previousConfigId, false)
            }
            store.updateConfigRunningState(runtimeConfig.id, true)
            pollJob?.cancel()
            pollJob = scope.launch { pollLoop() }
        }
    }

    fun stop() {
        scope.launch { stop(wait = false) }
    }

    fun attachIfRunning(configs: List<NetworkConfig>) {
        if (state.running || state.starting || state.stopping || pollJob != null) return
        scope.launch {
            val raw = withContext(Dispatchers.IO) { NativeEasyTier.collectNetworkInfos(8).orEmpty() }
            val outer = runCatching { JSONObject(raw) }.getOrNull() ?: return@launch
            val config = configs.firstOrNull { outer.optJSONObject(it.instanceName) != null } ?: return@launch
            runningConfig = config
            directCoreIps = emptySet()
            vpnSignature = ""
            lastLogKey = ""
            missedPolls = 0
            stalledPolls = 0
            state = RuntimeState(
                running = true,
                runningConfigId = config.id,
                statusText = "正在恢复运行状态",
                logs = listOf("已附着到现有 EasyTier 实例")
            )
            store.updateConfigRunningState(config.id, true)
            pollJob = scope.launch { pollLoop() }
        }
    }

    fun onVpnPermissionDenied() {
        AppDiagnostics.warn("vpn", "VPN permission denied by user")
        scope.launch {
            stopRuntime(
                statusText = "VPN 授权已取消",
                logMessage = "VPN 授权已取消，网络实例已停止",
                wait = false
            )
        }
    }

    fun onVpnServiceStartFailed(error: Throwable) {
        AppDiagnostics.error("vpn", "VPN service start failed", error)
        scope.launch {
            stopRuntime(
                statusText = "启动失败",
                logMessage = "VPN 服务启动失败: ${error.message ?: "未知错误"}",
                wait = false
            )
        }
    }

    fun updateLogLevel(level: String) {
        if (CoreLogLevel.normalize(level) == CoreLogLevel.OFF) {
            state = state.copy(logs = emptyList())
        }
    }

    private suspend fun stop(wait: Boolean) {
        if (state.running || state.starting || state.stopping) {
            state = state.copy(stopping = true, statusText = "正在停止")
        }
        stopRuntime(
            statusText = "EasyTier 未运行",
            logMessage = "网络实例已停止",
            wait = wait
        )
    }

    private suspend fun stopRuntime(statusText: String, logMessage: String, wait: Boolean) {
        AppDiagnostics.event("ffi", "network stopped: $logMessage")
        val stoppedConfigId = state.runningConfigId.ifBlank { runningConfig?.id.orEmpty() }
        val poll = pollJob
        pollJob = null
        poll?.cancel()
        // 等待轮询循环完全退出，避免与 FFI 互斥锁竞争
        runCatching { kotlinx.coroutines.withTimeout(2000L) { poll?.join() } }
        val stopIntent = Intent(context, EasyTierVpnService::class.java)
            .setAction(EasyTierVpnService.ACTION_STOP)
        runCatching { context.startService(stopIntent) }
        context.stopService(Intent(context, EasyTierVpnService::class.java))
        withContext(NonCancellable) {
            runCatching {
                withTimeout(3000L) {
                    withContext(Dispatchers.IO) { NativeEasyTier.stopAllInstances() }
                }
            }
        }
        runningConfig = null
        vpnSignature = ""
        directCoreIps = emptySet()
        missedPolls = 0
        stalledPolls = 0
        if (stoppedConfigId.isNotBlank()) {
            store.updateConfigRunningState(stoppedConfigId, false)
        }
        state = RuntimeState(statusText = statusText, logs = (state.logs + logMessage).takeLast(240))
        if (wait) delay(180)
    }

    fun release() {
        pollJob?.cancel()
    }

    private fun failStart(message: String) {
        AppDiagnostics.error("ffi", message)
        val configId = runningConfig?.id.orEmpty()
        pollJob?.cancel()
        pollJob = null
        runningConfig = null
        vpnSignature = ""
        directCoreIps = emptySet()
        missedPolls = 0
        stalledPolls = 0
        if (configId.isNotBlank()) {
            store.updateConfigRunningState(configId, false)
        }
        state = RuntimeState(statusText = "启动失败", logs = listOf(message))
    }

    private suspend fun pollLoop() {
        _pollIntervalMs.collectLatest { interval ->
            while (true) {
                val config = runningConfig ?: return@collectLatest
                pollOnce(config)
                delay(if (state.localCidr.isBlank()) 500L else interval)
            }
        }
    }

    private suspend fun pollOnce(config: NetworkConfig) {
        val raw = withContext(Dispatchers.IO) { NativeEasyTier.collectNetworkInfos(8).orEmpty() }
        if (raw.isBlank()) {
            convergeMissingInstance("状态读取为空，网络实例可能已退出")
            return
        }

        val outer = runCatching { JSONObject(raw) }.getOrNull()
        if (outer == null) {
            convergeMissingInstance("状态 JSON 解析失败，网络实例可能已退出")
            return
        }
        val info = outer.optJSONObject(config.instanceName) ?: firstJsonObject(outer)
        if (info == null) {
            convergeMissingInstance("未找到运行实例，网络实例已退出")
            return
        }
        missedPolls = 0
        stalledPolls = 0
        val localCidr = extractLocalCidr(info)
        val proxyCidrs = collectPeerProxyCidrs(info)
        val nodes = parseNodes(info)
        val settings = store.loadSettings()
        val logs = filterCoreLogs(collectNewLogs(info), settings.coreLogLevel)

        var next = state.copy(
            running = true,
            starting = false,
            stopping = false,
            runningConfigId = config.id,
            statusText = if (localCidr.isBlank()) "等待虚拟 IP" else "已连接: $localCidr",
            localCidr = localCidr,
            nodes = nodes
        )
        if (logs.isNotEmpty()) next = next.copy(logs = (next.logs + logs).takeLast(240))
        state = next
        if (state.running && localCidr.isBlank() && nodes.isEmpty()) {
            stalledPolls++
        } else {
            stalledPolls = 0
        }

        if (localCidr.isNotBlank() && !config.noTun) {
            if (directCoreIps.isEmpty()) {
                directCoreIps = withContext(Dispatchers.IO) { collectDirectCoreIps(config) }
            }
            val overlayRange = Ipv4.cidrToRange(localCidr)
            val discoveredCoreIps = collectDynamicCoreIps(info).filterNot { ip ->
                Ipv4.parseAddress(ip)?.let { address -> overlayRange?.contains(address) == true } == true
            }.toSet()
            if (discoveredCoreIps.isNotEmpty()) {
                directCoreIps = (directCoreIps + discoveredCoreIps).take(MAX_EXCLUDED_CORE_IPS).toSet()
            }
            val configuredExitNodes = config.exitNodes.cleanItems()
            val reachableExitNodes = configuredExitNodes.filter {
                normalizeExitNode(it) in collectRouteIps(info)
            }
            val exitNodeRoutesActive = settings.exitNodeAutoRoutes &&
                reachableExitNodes.isNotEmpty() &&
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
            val vpnJson = buildVpnConfigJson(
                config = config,
                cidr = localCidr,
                peerProxyCidrs = proxyCidrs,
                directCoreIps = directCoreIps,
                exitNodeAutoRoutes = exitNodeRoutesActive,
                exitNodeConfiguredCount = configuredExitNodes.size,
                exitNodeReachableCount = reachableExitNodes.size
            )
            if (vpnJson != vpnSignature) {
                vpnSignature = vpnJson
                val routeCount = runCatching { JSONObject(vpnJson).optJSONArray("routes")?.length() ?: 0 }.getOrDefault(0)
                val exitRouteState = when {
                    exitNodeRoutesActive -> "出口节点自动路由已开启，可达 ${reachableExitNodes.size}/${configuredExitNodes.size}"
                    settings.exitNodeAutoRoutes && reachableExitNodes.isNotEmpty() -> "当前 Android 版本无法排除核心传输端点，未下发公网路由"
                    settings.exitNodeAutoRoutes && configuredExitNodes.isNotEmpty() -> "出口节点未可达，暂不下发公网路由"
                    configuredExitNodes.isNotEmpty() -> "出口节点已配置，自动路由未开启"
                    else -> "未配置出口节点"
                }
                state = state.copy(
                    logs = (state.logs + "VPN 路由下发: $routeCount 条，$exitRouteState").takeLast(240)
                )
                requestVpn(vpnJson)
            }
        }
    }

    private suspend fun convergeMissingInstance(message: String) {
        missedPolls += 1
        val threshold = if (state.localCidr.isBlank()) 60 else 2
        if (missedPolls < threshold) return
        AppDiagnostics.warn("ffi", "instance disappeared: $message")
        val stoppedConfig = runningConfig
        val poll = pollJob
        pollJob = null
        runningConfig = null
        vpnSignature = ""
        directCoreIps = emptySet()
        missedPolls = 0
        stalledPolls = 0
        // 停止 VPN 服务
        val stopIntent = Intent(context, EasyTierVpnService::class.java)
            .setAction(EasyTierVpnService.ACTION_STOP)
        runCatching { context.startService(stopIntent) }
        context.stopService(Intent(context, EasyTierVpnService::class.java))
        // 等待轮询循环退出后释放原生实例
        poll?.cancel()
        runCatching { kotlinx.coroutines.withTimeout(2000L) { poll?.join() } }
        withContext(NonCancellable) {
            runCatching {
                withTimeout(3000L) {
                    withContext(Dispatchers.IO) { NativeEasyTier.stopAllInstances() }
                }
            }
        }
        if (stoppedConfig != null) {
            store.updateConfigRunningState(stoppedConfig.id, false)
        }
        state = RuntimeState(statusText = "实例已退出", logs = (state.logs + message).takeLast(240))
    }

    private fun firstJsonObject(obj: JSONObject): JSONObject? {
        val keys = obj.keys()
        while (keys.hasNext()) {
            val value = obj.opt(keys.next())
            if (value is JSONObject) return value
        }
        return null
    }

    private fun normalizeExitNode(value: String): String =
        value.trim().substringBefore("/")

    private fun collectDirectCoreIps(config: NetworkConfig): Set<String> {
        val out = LinkedHashSet<String>()
        (config.peerUrls + config.stunServers).forEach { url ->
            val host = Ipv4.hostFromUrl(url)
            if (host.isBlank()) return@forEach
            if (Ipv4.parseAddress(host) != null) {
                out += host
            } else {
                out += store.resolveHostIps(host).filter { Ipv4.parseAddress(it) != null }
            }
        }
        return out.take(32).toSet()
    }

    private fun collectDynamicCoreIps(info: JSONObject): Set<String> {
        val out = LinkedHashSet<String>()

        fun addAddresses(value: String) {
            out += Ipv4.addressLiterals(value)
        }

        fun visit(value: Any?, key: String = "", depth: Int = 0) {
            if (depth > 8) return
            when (value) {
                is JSONObject -> value.keys().forEach { childKey ->
                    visit(value.opt(childKey), childKey, depth + 1)
                }
                is JSONArray -> {
                    for (index in 0 until value.length()) visit(value.opt(index), key, depth + 1)
                }
                is String -> if (TRANSPORT_ENDPOINT_KEY.containsMatchIn(key)) addAddresses(value)
            }
        }

        // Connection snapshots expose remote/resolved endpoints after peer discovery.
        visit(info)

        // Hole-punch candidates are also emitted as events before a connection is established.
        val events = info.optJSONArray("events") ?: JSONArray()
        for (index in 0 until events.length()) {
            val event = events.optString(index)
            if (P2P_EVENT_HINT.containsMatchIn(event)) addAddresses(event)
        }
        return out.take(MAX_EXCLUDED_CORE_IPS).toSet()
    }

    private fun collectRouteIps(info: JSONObject): Set<String> {
        val out = LinkedHashSet<String>()
        val routes = info.optJSONArray("routes") ?: JSONArray()
        for (i in 0 until routes.length()) {
            val route = routes.optJSONObject(i) ?: continue
            val addr = route.optJSONObject("ipv4_addr")
                ?.optJSONObject("address")
                ?.optLong("addr", 0L) ?: 0L
            if (addr != 0L) out += Ipv4.intToAddress(addr)
        }
        return out
    }

    private fun extractLocalCidr(info: JSONObject): String {
        val ipv4 = info.optJSONObject("my_node_info")
            ?.optJSONObject("virtual_ipv4")
            ?: return ""
        val addr = ipv4.optJSONObject("address")?.optLong("addr", 0L) ?: 0L
        if (addr == 0L) return ""
        val prefix = ipv4.optInt("network_length", 24).coerceIn(1, 32)
        return "${Ipv4.intToAddress(addr)}/$prefix"
    }

    private fun collectPeerProxyCidrs(info: JSONObject): Set<String> {
        val out = LinkedHashSet<String>()
        val routes = info.optJSONArray("routes") ?: JSONArray()
        for (i in 0 until routes.length()) {
            val item = routes.optJSONObject(i) ?: continue
            val cidrs = item.optJSONArray("proxy_cidrs") ?: JSONArray()
            for (j in 0 until cidrs.length()) {
                val cidr = cidrs.optString(j).trim()
                if (cidr.isNotBlank()) out += cidr
            }
        }
        return out
    }

    private fun parseNodes(info: JSONObject): List<NodeInfo> {
        val out = ArrayList<NodeInfo>()
        val my = info.optJSONObject("my_node_info")
        my?.let {
            val ipv4 = extractLocalCidr(info).substringBefore("/")
            out += NodeInfo(
                hostname = it.optString("hostname").ifBlank { "本机" },
                ip = ipv4,
                role = "本机",
                latencyMs = 0,
                local = true
            )
        }

        val routes = info.optJSONArray("routes") ?: JSONArray()
        for (i in 0 until routes.length()) {
            val route = routes.optJSONObject(i) ?: continue
            val addr = route.optJSONObject("ipv4_addr")
                ?.optJSONObject("address")
                ?.optLong("addr", 0L) ?: 0L
            val ip = if (addr == 0L) "" else Ipv4.intToAddress(addr)
            val isServer = route.optJSONObject("feature_flag")?.optBoolean("is_public_server", false) == true
            val cost = route.optInt("cost", 1)
            val connectionType = when {
                isServer -> "服务器"
                cost > 1 -> "中继($cost)"
                else -> "直连"
            }
            out += NodeInfo(
                hostname = route.optString("hostname").ifBlank { if (isServer) "公共服务器" else connectionType },
                ip = ip,
                role = connectionType,
                latencyMs = route.optInt("path_latency", -1),
                local = false
            )
        }
        return out.distinctBy { "${it.local}-${it.ip}-${it.hostname}" }
    }

    private fun collectNewLogs(info: JSONObject): List<String> {
        val events = info.optJSONArray("events") ?: JSONArray()
        val parsed = ArrayList<Pair<String, String>>()
        for (i in 0 until events.length()) {
            val item = events.optString(i)
            val obj = runCatching { JSONObject(item) }.getOrNull() ?: continue
            val time = obj.optString("time")
            val event = obj.optJSONObject("event")
            val message = event?.optString("msg").orEmpty()
                .ifBlank { event?.optString("message").orEmpty() }
                .ifBlank { event?.toString().orEmpty() }
            if (time.isNotBlank() && message.isNotBlank()) parsed += time to message
        }

        val fresh = parsed
            .sortedBy { it.first }
            .filter { lastLogKey.isBlank() || it.first > lastLogKey }
        if (fresh.isNotEmpty()) lastLogKey = fresh.last().first
        return fresh.map { it.second }
    }

    private fun filterCoreLogs(logs: List<String>, level: String): List<String> = when (CoreLogLevel.normalize(level)) {
        CoreLogLevel.OFF -> emptyList()
        CoreLogLevel.NORMAL -> logs.filter { IMPORTANT_CORE_LOG.containsMatchIn(it) }
        else -> logs
    }

    private fun NetworkConfig.withRuntimeHostname(context: Context): NetworkConfig {
        if (hostname.isNotBlank() && !hostname.equals("localhost", ignoreCase = true)) return this
        return copy(hostname = defaultRuntimeHostname(context, id))
    }

    private fun defaultRuntimeHostname(context: Context, id: String): String {
        val candidates = listOf(
            runCatching { Settings.Global.getString(context.contentResolver, "device_name") }.getOrNull(),
            runCatching { Settings.Secure.getString(context.contentResolver, "bluetooth_name") }.getOrNull(),
            Build.MODEL,
            Build.DEVICE
        )
        return candidates
            .map { it.orEmpty().trim() }
            .firstOrNull { it.isNotBlank() && !it.equals("localhost", ignoreCase = true) }
            ?: "Android-${id.take(8)}"
    }
    fun updatePollingConditions(isForeground: Boolean, expanded: Boolean) {
        _pollIntervalMs.value = when {
            !isForeground -> 300000L
            isForeground && expanded -> 500L
            else -> 30000L
        }
    }

    companion object {
        private const val TAG = "MoonTierCore"
        private const val MAX_EXCLUDED_CORE_IPS = 64
        private val TRANSPORT_ENDPOINT_KEY = Regex(
            "(?:remote|resolved|endpoint|stun|mapped|public).*?(?:addr|address|url|endpoint)?$",
            RegexOption.IGNORE_CASE
        )
        private val P2P_EVENT_HINT = Regex(
            "hole.?punch|direct connect|udp|tcp|quic|stun|endpoint|candidate",
            RegexOption.IGNORE_CASE
        )
        private val IMPORTANT_CORE_LOG = Regex(
            "error|warn|fail|panic|closed|stop|disconnect|exit|timeout",
            RegexOption.IGNORE_CASE
        )
    }
}
