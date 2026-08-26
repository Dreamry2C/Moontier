package cn.moonflow.easytier

import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.provider.Settings.Secure
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class RootTierController(private val context: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val coreManager = RootCoreManager(context)
    private val store = ConfigStore(context)
    private val rootDir = File(context.filesDir, "root")
    private val scriptFile = File(rootDir, "moontier_root.sh")
    private val configsDir = File(rootDir, "configs")
    private val stagingDir = File(rootDir, "staging")
    private val logsDir = File(rootDir, "logs")
    private val pidsDir = File(rootDir, "pids")
    private val metaDir = File(rootDir, "meta")
    private val managerLogFile = File(logsDir, "manager.log")
    private val managerPidFile = File(pidsDir, "manager.pid")
    private val managerMetaFile = File(metaDir, "manager.json")
    private val managerMutex = Mutex()
    private var pollJob: Job? = null
    private var lastRootCidr = ""
    private var lastRootExitRoute = false

    var state by mutableStateOf(RootTierState())
        private set

    init {
        ensureDirs()
        ensureScript()
        refreshCoreState()
        scope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    managerMutex.withLock { initializeBlocking() }
                }
            }
            result.onSuccess { refreshed ->
                state = state.copy(
                    instances = refreshed.instances,
                    configServer = refreshed.configServer
                )
            }.onFailure { error ->
                Log.e(TAG, "Root manager initialization failed", error)
                if (!isFullConfigServerUrl(state.configServer.serverUrl)) return@onFailure
                state = state.copy(
                    configServer = state.configServer.copy(
                        error = error.message ?: "Root manager 初始化失败"
                    )
                )
            }
            pollJob = scope.launch { pollLoop() }
        }
    }

    fun release() {
        pollJob?.cancel()
        scope.cancel()
    }

    fun diagnosticLogTail(): List<String> = readManagerLogTail(loadManagerOptions())

    fun updateLogLevel(level: String) {
        if (CoreLogLevel.normalize(level) == CoreLogLevel.OFF) {
            managerLogFile.delete()
            state = state.copy(
                instances = state.instances.map { it.copy(logs = emptyList()) },
                configServer = state.configServer.copy(logs = emptyList())
            )
        }
    }

    fun checkCoreUpdate() {
        scope.launch {
            state = state.copy(core = state.core.copy(checking = true, message = ""))
            val latest = withContext(Dispatchers.IO) { coreManager.checkLatest() }
            state = state.copy(
                core = state.core.copy(
                    checking = false,
                    latestVersion = latest ?: state.core.latestVersion,
                    message = if (latest == null) "检查最新版本失败" else "已获取最新版本 $latest"
                )
            )
        }
    }

    fun installCore() {
        scope.launch {
            state = state.copy(core = state.core.copy(installing = true, progress = 0, message = ""))
            AppDiagnostics.event("root", "official core download requested")
            try {
                val tag = withContext(Dispatchers.IO) {
                    coreManager.installLatest { progress, _ ->
                        state = state.copy(core = state.core.copy(progress = progress))
                    }
                }
                state = state.copy(
                    core = RootCoreState(
                        ready = true,
                        installedVersion = tag,
                        latestVersion = tag,
                        progress = 100,
                        message = "已安装官方核心 $tag"
                    )
                )
                AppDiagnostics.event("root", "official core installed: $tag")
            } catch (error: Exception) {
                AppDiagnostics.error("root", "official core installation failed", error)
                state = state.copy(
                    core = state.core.copy(
                        installing = false,
                        message = error.message ?: "下载核心失败"
                    )
                )
            }
        }
    }

    fun installCoreZip(uri: Uri) {
        scope.launch {
            state = state.copy(core = state.core.copy(installing = true, progress = 0, message = ""))
            AppDiagnostics.event("root", "local core ZIP import requested")
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    managerMutex.withLock {
                        val options = loadManagerOptions()
                            ?: optionsFromSettings(store.loadSettings(), enabled = false)
                        val restartManager = managerAlive()
                        if (restartManager) stopManagerBlocking()
                        try {
                            val version = coreManager.installFromZip(uri) { progress, _ ->
                                state = state.copy(core = state.core.copy(progress = progress))
                            }
                            val refreshed = if (restartManager) {
                                ensureManagerBlocking(options)
                                val snapshot = queryManagerSnapshot()
                                applyRootRouting(snapshot)
                                refreshedState(snapshot, options)
                            } else {
                                null
                            }
                            version to refreshed
                        } catch (error: Exception) {
                            if (restartManager && coreManager.isReady()) {
                                runCatching { ensureManagerBlocking(options) }
                                    .onFailure { AppDiagnostics.error("root", "failed to restore manager after ZIP import", it) }
                            }
                            throw error
                        }
                    }
                }
            }
            result.onSuccess { (version, refreshed) ->
                AppDiagnostics.event("root", "local core ZIP installed")
                state = state.copy(
                    core = RootCoreState(
                        ready = true,
                        installedVersion = version,
                        latestVersion = state.core.latestVersion,
                        progress = 100,
                        message = "已导入官方核心"
                    ),
                    instances = refreshed?.instances ?: state.instances,
                    configServer = refreshed?.configServer ?: state.configServer
                )
            }.onFailure { error ->
                AppDiagnostics.error("root", "local core ZIP import failed", error)
                state = state.copy(
                    core = state.core.copy(
                        installing = false,
                        message = error.message ?: "导入核心失败"
                    )
                )
            }
        }
    }

    fun start(config: NetworkConfig) {
        val existing = state.instances.firstOrNull { it.configId == config.id }
        if (existing?.running == true || existing?.starting == true || existing?.stopping == true) return

        Log.i(TAG, "Root manager start requested id=${config.id}")
        AppDiagnostics.event("root", "network start requested id=${config.id} name=${config.displayName}")
        val starting = RootInstanceState(
            configId = config.id,
            displayName = config.displayName,
            instanceName = config.instanceName,
            starting = true,
            logs = listOf("正在通过共享 manager 启动实例")
        )
        state = state.copy(instances = state.instances.filterNot { it.configId == config.id } + starting)
        scope.launch {
            val startedAt = SystemClock.elapsedRealtime()
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    managerMutex.withLock { startBlocking(config) }
                }
            }
            result.onSuccess { refreshed ->
                Log.i(TAG, "Root instance ${config.id} started in ${SystemClock.elapsedRealtime() - startedAt}ms")
                AppDiagnostics.event("root", "network started id=${config.id} elapsed=${SystemClock.elapsedRealtime() - startedAt}ms")
                state = state.copy(
                    instances = refreshed.instances,
                    configServer = refreshed.configServer
                )
            }.onFailure { error ->
                Log.e(TAG, "Root instance ${config.id} failed", error)
                AppDiagnostics.error("root", "network start failed id=${config.id}", error)
                state = state.copy(
                    instances = state.instances.map {
                        if (it.configId == config.id) {
                            it.copy(
                                starting = false,
                                running = false,
                                error = error.message ?: "启动失败"
                            )
                        } else {
                            it
                        }
                    }
                )
            }
        }
    }

    fun stop(configId: String) {
        val instance = state.instances.firstOrNull { it.configId == configId } ?: return
        if (instance.stopping) return
        AppDiagnostics.event("root", "network stop requested id=$configId")
        state = state.copy(
            instances = state.instances.map {
                if (it.configId == configId) it.copy(stopping = true, error = "") else it
            }
        )
        scope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    managerMutex.withLock { stopBlocking(configId) }
                }
            }
            result.onSuccess { refreshed ->
                AppDiagnostics.event("root", "network stopped id=$configId")
                state = state.copy(
                    instances = refreshed.instances,
                    configServer = refreshed.configServer
                )
            }.onFailure { error ->
                AppDiagnostics.error("root", "network stop failed id=$configId", error)
                state = state.copy(
                    instances = state.instances.map {
                        if (it.configId == configId) {
                            it.copy(stopping = false, error = error.message ?: "停止失败")
                        } else {
                            it
                        }
                    }
                )
            }
        }
    }

    fun stopAll() {
        if (state.instances.isEmpty() && !state.configServer.running && !managerPidFile.exists()) return
        AppDiagnostics.event("root", "shared manager stop requested")
        state = state.copy(
            instances = state.instances.map { it.copy(stopping = true, starting = false) },
            configServer = state.configServer.copy(stopping = true, starting = false)
        )
        scope.launch {
            withContext(Dispatchers.IO) {
                managerMutex.withLock { stopManagerBlocking() }
            }
            state = state.copy(
                instances = emptyList(),
                configServer = state.configServer.copy(
                    pid = 0,
                    running = false,
                    starting = false,
                    stopping = false,
                    connected = false,
                    managedNetworks = emptyList()
                )
            )
            AppDiagnostics.event("root", "shared manager stopped")
        }
    }

    fun startConfigServer(settings: AppSettings) {
        val current = state.configServer
        if (current.running || current.starting || current.stopping) return
        state = state.copy(
            configServer = current.copy(
                starting = true,
                stopping = false,
                serverUrl = settings.configServerUrl.trim(),
                error = "",
                logs = listOf("正在把配置服务器接入共享 manager")
            )
        )
        scope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    managerMutex.withLock { startConfigServerBlocking(settings) }
                }
            }
            result.onSuccess { refreshed ->
                state = state.copy(
                    instances = refreshed.instances,
                    configServer = refreshed.configServer
                )
            }.onFailure { error ->
                Log.e(TAG, "Config server start failed", error)
                state = state.copy(
                    configServer = state.configServer.copy(
                        starting = false,
                        running = false,
                        error = error.message ?: "配置服务器启动失败"
                    )
                )
            }
        }
    }

    fun stopConfigServer() {
        val current = state.configServer
        if (!current.running && !current.starting && !current.stopping) return
        state = state.copy(configServer = current.copy(stopping = true, starting = false))
        scope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    managerMutex.withLock { stopConfigServerBlocking() }
                }
            }
            result.onSuccess { refreshed ->
                state = state.copy(
                    instances = refreshed.instances,
                    configServer = refreshed.configServer
                )
            }.onFailure { error ->
                state = state.copy(
                    configServer = state.configServer.copy(
                        stopping = false,
                        error = error.message ?: "配置服务器断开失败"
                    )
                )
            }
        }
    }

    private fun refreshCoreState() {
        state = state.copy(
            core = RootCoreState(
                ready = coreManager.isReady(),
                installedVersion = coreManager.installedVersion()
            )
        )
    }

    private fun initializeBlocking(): RefreshedState {
        val legacyConfigServerWasRunning = migrateLegacyProcesses()
        val settings = store.loadSettings()
        var options = loadManagerOptions() ?: optionsFromSettings(
            settings,
            enabled = legacyConfigServerWasRunning || settings.configServerAutoConnect
        )
        if (legacyConfigServerWasRunning && !options.configServerEnabled) {
            options = optionsFromSettings(settings, enabled = true)
            saveManagerOptions(options)
        }

        if (
            !managerAlive() &&
            settings.rootModeEnabled &&
            coreManager.isReady() &&
            (configsDir.listFiles().orEmpty().any { it.isFile && it.extension == "toml" } || options.configServerEnabled)
        ) {
            prepareEnabledLocalConfigs()
            ensureManagerBlocking(options)
        }

        return if (managerAlive()) {
            refreshedState(queryManagerSnapshot(), options)
        } else {
            RefreshedState(
                instances = emptyList(),
                configServer = RootConfigServerState(serverUrl = options.rawConfigServerUrl)
            )
        }
    }

    private fun startBlocking(config: NetworkConfig): RefreshedState {
        requireRootAndCore()
        val options = loadManagerOptions() ?: optionsFromSettings(store.loadSettings(), enabled = false)
        ensureManagerBlocking(options)

        val runtimeConfig = runtimeRootConfig(config)
        val stageFile = File(stagingDir, "${safeId(config.id)}.toml")
        stageFile.writeText(TomlCodec.build(runtimeConfig))
        val result = runManager("run", stageFile.absolutePath, timeoutMs = 15000)
        stageFile.delete()
        if (!result.success) {
            throw IllegalStateException(managerError(result, "实例启动失败"))
        }

        writeLocalMeta(config)
        return refreshedState(queryManagerSnapshot(), options)
    }

    private fun stopBlocking(configId: String): RefreshedState {
        val options = loadManagerOptions() ?: optionsFromSettings(store.loadSettings(), enabled = false)
        if (managerAlive()) {
            val result = runManager("delete", configId, timeoutMs = 12000)
            if (!result.success) {
                throw IllegalStateException(managerError(result, "实例停止失败"))
            }
        } else {
            File(configsDir, "$configId.toml").delete()
            File(configsDir, "${safeId(configId)}.toml").delete()
        }
        localMetaFile(configId).delete()

        return if (managerAlive()) {
            refreshedState(queryManagerSnapshot(), options)
        } else {
            RefreshedState(
                instances = emptyList(),
                configServer = RootConfigServerState(serverUrl = options.rawConfigServerUrl)
            )
        }
    }

    private fun startConfigServerBlocking(settings: AppSettings): RefreshedState {
        requireRootAndCore()
        val rawUrl = settings.configServerUrl.trim()
        require(isFullConfigServerUrl(rawUrl)) {
            "请填写完整配置服务器 URL，例如 udp://host:22020/admin"
        }
        val desired = optionsFromSettings(settings, enabled = true)
        restartManagerForOptions(desired)
        return refreshedState(queryManagerSnapshot(), desired)
    }

    private fun stopConfigServerBlocking(): RefreshedState {
        val current = loadManagerOptions() ?: optionsFromSettings(store.loadSettings(), enabled = false)
        val desired = current.copy(configServerEnabled = false, resolvedConfigServerUrl = "")
        saveManagerOptions(desired)
        if (managerAlive()) {
            restartManagerForOptions(desired)
            return refreshedState(queryManagerSnapshot(), desired)
        }
        return RefreshedState(
            instances = emptyList(),
            configServer = RootConfigServerState(serverUrl = desired.rawConfigServerUrl)
        )
    }

    private fun restartManagerForOptions(desired: ManagerOptions) {
        val current = loadManagerOptions()
        if (managerAlive() && current?.sameLaunchOptions(desired) == true) {
            saveManagerOptions(desired)
            return
        }
        if (managerAlive()) stopManagerBlocking()
        prepareEnabledLocalConfigs()
        ensureManagerBlocking(desired)
    }

    private fun ensureManagerBlocking(requested: ManagerOptions): Long {
        requireRootAndCore()
        if (managerAlive()) {
            val pid = managerPid()
            val probe = runManager("list", timeoutMs = 5000)
            if (probe.success) return pid
            throw IllegalStateException(managerError(probe, "共享 manager RPC 不可用"))
        }

        val options = if (requested.configServerEnabled) {
            requested.copy(
                resolvedConfigServerUrl = resolveConfigServerEndpoint(
                    requested.rawConfigServerUrl,
                    store
                )
            )
        } else {
            requested.copy(resolvedConfigServerUrl = "")
        }
        saveManagerOptions(options)
        managerPidFile.delete()

        val logLevel = store.loadSettings().coreLogLevel
        val managerLogPath = if (CoreLogLevel.normalize(logLevel) == CoreLogLevel.OFF) {
            "/dev/null"
        } else {
            managerLogFile.absolutePath
        }
        val command = buildString {
            append("RUST_LOG=")
            append(shq(CoreLogLevel.rustFilter(logLevel)))
            append(" ET_CONSOLE_LOG_LEVEL=")
            append(shq(CoreLogLevel.rustLevel(logLevel)))
            append(' ')
            append("sh ")
            append(shq(scriptFile.absolutePath))
            append(" start ")
            append(shq(coreManager.coreFile.absolutePath))
            append(' ')
            append(shq(managerLogPath))
            append(' ')
            append(shq(managerPidFile.absolutePath))
            append(" --config-dir ")
            append(shq(configsDir.absolutePath))
            append(" --rpc-portal ")
            append(shq(MANAGER_RPC_PORTAL))
            append(" --daemon")
            if (options.configServerEnabled) {
                append(" --config-server ")
                append(shq(options.resolvedConfigServerUrl))
                append(" --machine-id ")
                append(shq(options.machineId))
                append(" --hostname ")
                append(shq(options.hostname))
                if (options.secureMode) append(" --secure-mode")
            }
        }
        val result = RootManager.su(command, timeoutMs = 20000)
        if (!result.success) {
            AppDiagnostics.error("root", "shared manager start command failed: ${result.output.takeLast(1200)}")
            throw IllegalStateException(managerError(result, "共享 manager 启动失败"))
        }

        var lastProbe = ShellResult(1, "RPC 尚未就绪")
        repeat(20) {
            lastProbe = runManager("list", timeoutMs = 3500)
            if (lastProbe.success) return managerPid()
            if (!managerAlive()) {
                throw IllegalStateException(managerError(lastProbe, "共享 manager 已退出"))
            }
            Thread.sleep(250)
        }
        stopManagerBlocking()
        throw IllegalStateException(managerError(lastProbe, "共享 manager RPC 启动超时"))
    }

    private fun stopManagerBlocking() {
        removeRootRouting()
        val command = buildString {
            append("sh ")
            append(shq(scriptFile.absolutePath))
            append(" stop ")
            append(shq(coreManager.coreFile.absolutePath))
            append(' ')
            append(shq(managerLogFile.absolutePath))
            append(' ')
            append(shq(managerPidFile.absolutePath))
        }
        RootManager.su(command, timeoutMs = 12000)
        managerPidFile.delete()
    }

    private fun applyRootRouting(snapshot: ManagerSnapshot) {
        val cidr = snapshotLocalCidr(snapshot)
        if (cidr.isBlank()) return
        val settings = store.loadSettings()
        val exitRouteActive = settings.exitNodeAutoRoutes && hasReachableExitNode(snapshot)
        if (cidr == lastRootCidr && exitRouteActive == lastRootExitRoute) return

        val command = buildString {
            append("CIDR=")
            append(shq(cidr))
            append('\n')
            append("IFACE=\$(ip -4 route show table main | awk -v cidr=\"\$CIDR\" '\$1==cidr {for(i=1;i<=NF;i++) if(\$i==\"dev\"){print \$(i+1); exit}}')\n")
            append("[ -z \"\$IFACE\" ] && IFACE=tun0\n")
            append("ip rule del pref 20001 2>/dev/null\n")
            append("ip rule del pref 20002 2>/dev/null\n")
            append("ip route flush table 200 2>/dev/null\n")
            append("ip rule add pref 20001 from all to \"\$CIDR\" lookup main\n")
            if (exitRouteActive) {
                append("ip route add default dev \"\$IFACE\" table 200 2>/dev/null; ip route replace default dev \"\$IFACE\" table 200 2>/dev/null\n")
                append("ip rule add pref 20002 from all uidrange 10000-99999 lookup 200 2>/dev/null || true\n")
            }
            append("true")
        }
        val result = RootManager.su(command, timeoutMs = 10000)
        if (result.success) {
            lastRootCidr = cidr
            lastRootExitRoute = exitRouteActive
        } else {
            Log.w(TAG, "Root policy route apply failed: ${result.output}")
            AppDiagnostics.warn("root", "policy route apply failed: ${result.output.takeLast(1200)}")
        }
    }

    private fun removeRootRouting() {
        if (lastRootCidr.isBlank() && !lastRootExitRoute) return
        RootManager.su(
            "ip rule del pref 20001 2>/dev/null; ip rule del pref 20002 2>/dev/null; ip route flush table 200 2>/dev/null; true",
            timeoutMs = 6000
        )
        lastRootCidr = ""
        lastRootExitRoute = false
    }

    private fun snapshotLocalCidr(snapshot: ManagerSnapshot): String {
        for (id in snapshot.instanceIds) {
            val info = snapshot.runningInfo.optJSONObject(id) ?: continue
            val cidr = ipv4Cidr(
                info.optJSONObjectCompat("my_node_info", "myNodeInfo")
                    ?.optJSONObjectCompat("virtual_ipv4", "virtualIpv4")
            )
            if (cidr.isNotBlank()) return cidr
        }
        return ""
    }

    private fun hasReachableExitNode(snapshot: ManagerSnapshot): Boolean {
        val routeIps = HashSet<String>()
        for (id in snapshot.instanceIds) {
            val info = snapshot.runningInfo.optJSONObject(id) ?: continue
            val routes = info.optJSONArray("routes") ?: continue
            for (index in 0 until routes.length()) {
                val route = routes.optJSONObject(index) ?: continue
                val addr = route.optJSONObjectCompat("ipv4_addr", "ipv4Addr")
                    ?.optJSONObject("address")
                    ?.optLong("addr", 0L) ?: 0L
                if (addr != 0L) routeIps += Ipv4.intToAddress(addr)
            }
        }
        if (routeIps.isEmpty()) return false
        return store.loadConfigs().any { config ->
            config.id in snapshot.instanceIds && config.exitNodes.cleanItems().any { node ->
                normalizeExitNode(node) in routeIps
            }
        }
    }

    private fun normalizeExitNode(value: String): String =
        value.trim().substringBefore("/")

    private suspend fun pollLoop() {
        while (true) {
            refreshStates()
            delay(if (state.instances.any { it.localCidr.isBlank() } || state.configServer.starting) 1000 else 3000)
        }
    }

    private suspend fun refreshStates() {
        if (!managerPidFile.exists() && state.instances.isEmpty() && !state.configServer.running) return
        val result = runCatching {
            withContext(Dispatchers.IO) {
                managerMutex.withLock {
                    if (!managerAlive()) {
                        removeRootRouting()
                        null
                    } else {
                        val options = loadManagerOptions()
                            ?: optionsFromSettings(store.loadSettings(), enabled = false)
                        val snapshot = queryManagerSnapshot()
                        val refreshed = refreshedState(snapshot, options)
                        applyRootRouting(snapshot)
                        refreshed
                    }
                }
            }
        }
        result.onSuccess { refreshed ->
            if (refreshed == null) {
                val wasExpected = state.instances.any { it.stopping } || state.configServer.stopping
                state = state.copy(
                    instances = emptyList(),
                    configServer = state.configServer.copy(
                        pid = 0,
                        running = false,
                        starting = false,
                        stopping = false,
                        connected = false,
                        error = if (wasExpected) "" else "共享 manager 已退出"
                    )
                )
            } else {
                state = state.copy(
                    instances = refreshed.instances,
                    configServer = refreshed.configServer
                )
            }
        }.onFailure { error ->
            Log.w(TAG, "Root manager refresh failed", error)
        }
    }

    private fun queryManagerSnapshot(): ManagerSnapshot {
        val result = runManager("snapshot", timeoutMs = 9000)
        if (!result.success) {
            throw IllegalStateException(managerError(result, "读取 manager 状态失败"))
        }
        val root = parseJsonOutput(result.output)
            ?: throw IllegalStateException("manager 返回了无效状态")
        val ids = root.optJSONArray("instance_ids")?.toStringList().orEmpty().toSet()
        val metas = LinkedHashMap<String, ManagerInstanceMeta>()
        val metaArray = root.optJSONArray("metas") ?: JSONArray()
        for (index in 0 until metaArray.length()) {
            val obj = metaArray.optJSONObject(index) ?: continue
            val id = obj.optString("instance_id")
            if (id.isBlank()) continue
            metas[id] = ManagerInstanceMeta(
                instanceId = id,
                instanceName = obj.optString("instance_name"),
                networkName = obj.optString("network_name"),
                source = obj.optInt("source", 0)
            )
        }
        val running = root.optJSONObject("running")
            ?.optJSONObject("info")
            ?.optJSONObject("map")
            ?: JSONObject()
        return ManagerSnapshot(ids, metas, running)
    }

    private fun refreshedState(snapshot: ManagerSnapshot, options: ManagerOptions): RefreshedState {
        val pid = managerPid()
        val configs = store.loadConfigs().associateBy { it.id }
        val localMeta = loadLocalMetadata().toMutableMap()
        configs.values.forEach { config ->
            if (config.id in snapshot.instanceIds && config.id !in localMeta) {
                localMeta[config.id] = LocalInstanceMeta(
                    config.id,
                    config.displayName,
                    config.instanceName
                )
            }
        }

        val previousById = state.instances.associateBy { it.configId }
        val localIds = localMeta.keys.filterTo(LinkedHashSet()) { id ->
            snapshot.metas[id]?.source != CONFIG_SOURCE_WEB
        }
        val instances = snapshot.instanceIds
            .filter { it in localIds }
            .map { id ->
                val meta = localMeta[id]
                val config = configs[id]
                val previous = previousById[id]
                val info = snapshot.runningInfo.optJSONObject(id)
                val parsed = parseInstanceInfo(info)
                RootInstanceState(
                    configId = id,
                    displayName = meta?.displayName.orEmpty()
                        .ifBlank { config?.displayName.orEmpty() }
                        .ifBlank { snapshot.metas[id]?.networkName.orEmpty() }
                        .ifBlank { id },
                    instanceName = snapshot.metas[id]?.instanceName.orEmpty()
                        .ifBlank { meta?.instanceName.orEmpty() }
                        .ifBlank { config?.instanceName.orEmpty() },
                    pid = pid,
                    running = parsed.running,
                    localCidr = parsed.localCidr,
                    nodes = parsed.nodes,
                    logs = filteredCoreLogs(parsed.logs).let { logs ->
                        if (logs.isNotEmpty() || CoreLogLevel.normalize(store.loadSettings().coreLogLevel) == CoreLogLevel.OFF) {
                            logs
                        } else {
                            previous?.logs.orEmpty()
                        }
                    },
                    error = parsed.error
                )
            }

        val managed = snapshot.instanceIds
            .filter { id -> id !in localIds || snapshot.metas[id]?.source == CONFIG_SOURCE_WEB }
            .map { id ->
                val meta = snapshot.metas[id]
                val parsed = parseInstanceInfo(snapshot.runningInfo.optJSONObject(id))
                RootManagedNetworkState(
                    instanceId = id,
                    instanceName = meta?.instanceName.orEmpty()
                        .ifBlank { meta?.networkName.orEmpty() }
                        .ifBlank { parsed.nodes.firstOrNull { it.local }?.hostname.orEmpty() },
                    localCidr = parsed.localCidr,
                    nodes = parsed.nodes
                )
            }

        val logs = readManagerLogTail(options)
        val connectedAt = logs.indexOfLast {
            it.contains("Successfully connected", ignoreCase = true) ||
                it.contains("connected to config server", ignoreCase = true)
        }
        val disconnectedAt = logs.indexOfLast {
            it.contains("Failed to connect", ignoreCase = true) ||
                it.contains("connection closed", ignoreCase = true) ||
                it.contains("disconnected", ignoreCase = true)
        }
        val configServer = RootConfigServerState(
            pid = pid,
            running = options.configServerEnabled,
            connected = options.configServerEnabled && connectedAt >= 0 && connectedAt > disconnectedAt,
            serverUrl = options.rawConfigServerUrl,
            managedNetworks = managed,
            logs = logs
        )
        return RefreshedState(instances, configServer)
    }

    private fun parseInstanceInfo(info: JSONObject?): ParsedInstanceInfo {
        if (info == null) return ParsedInstanceInfo(running = true)
        val myNode = info.optJSONObjectCompat("my_node_info", "myNodeInfo")
        val virtualIpv4 = myNode?.optJSONObjectCompat("virtual_ipv4", "virtualIpv4")
        val localCidr = ipv4Cidr(virtualIpv4)
        val nodes = ArrayList<NodeInfo>()
        if (myNode != null) {
            nodes += NodeInfo(
                hostname = myNode.optString("hostname").ifBlank { "本机" },
                ip = localCidr.substringBefore("/"),
                role = "本机",
                latencyMs = 0,
                local = true
            )
        }

        val routes = info.optJSONArray("routes") ?: JSONArray()
        for (index in 0 until routes.length()) {
            val route = routes.optJSONObject(index) ?: continue
            val cidr = ipv4Cidr(route.optJSONObjectCompat("ipv4_addr", "ipv4Addr"))
            val isServer = route.optJSONObjectCompat("feature_flag", "featureFlag")
                ?.optBooleanCompat("is_public_server", "isPublicServer") == true
            val cost = route.optInt("cost", 1)
            val role = when {
                isServer -> "服务器"
                cost > 1 -> "中继($cost)"
                else -> "直连"
            }
            nodes += NodeInfo(
                hostname = route.optString("hostname").ifBlank { if (isServer) "公共服务器" else role },
                ip = cidr.substringBefore("/"),
                role = role,
                latencyMs = route.optIntCompat("path_latency", "pathLatency", -1),
                local = false
            )
        }

        val events = info.optJSONArray("events") ?: JSONArray()
        val logs = ArrayList<String>()
        for (index in 0 until events.length()) {
            val raw = events.optString(index)
            val parsedEvent = runCatching { JSONObject(raw) }.getOrNull()
            if (parsedEvent == null) {
                if (raw.isNotBlank()) logs += raw
                continue
            }
            val event = parsedEvent.optJSONObject("event")
            val message = event?.optString("msg").orEmpty()
                .ifBlank { event?.optString("message").orEmpty() }
                .ifBlank { event?.toString().orEmpty() }
            if (message.isNotBlank()) logs += message
        }

        return ParsedInstanceInfo(
            running = info.optBoolean("running", true),
            localCidr = localCidr,
            nodes = nodes.distinctBy { "${it.local}-${it.ip}-${it.hostname}" },
            logs = logs.takeLast(80),
            error = info.optStringCompat("error_msg", "errorMsg")
        )
    }

    private fun ipv4Cidr(obj: JSONObject?): String {
        if (obj == null) return ""
        val address = obj.optJSONObject("address")?.optLong("addr", 0L) ?: 0L
        if (address == 0L) return ""
        val prefix = obj.optIntCompat("network_length", "networkLength", 24).coerceIn(1, 32)
        return "${Ipv4.intToAddress(address)}/$prefix"
    }

    private fun runtimeRootConfig(config: NetworkConfig): NetworkConfig {
        var runtime = config.copy(
            hostname = effectiveHostname(config),
            rpcPortal = ""
        )
        runtime = runtime.copy(
            peerUrls = resolveRootEndpoints(runtime.peerUrls, store),
            stunServers = resolveRootEndpoints(runtime.stunServers, store)
        )
        if (!store.loadSettings().exitNodeAutoRoutes) {
            runtime = runtime.copy(exitNodes = emptyList())
        }
        return runtime
    }

    private fun prepareEnabledLocalConfigs() {
        val configs = store.loadConfigs().associateBy { it.id }
        loadLocalMetadata().forEach { (id, _) ->
            val config = configs[id] ?: return@forEach
            File(configsDir, "$id.toml").writeText(TomlCodec.build(runtimeRootConfig(config)))
        }
    }

    private fun migrateLegacyProcesses(): Boolean {
        var configServerWasRunning = false
        pidsDir.listFiles().orEmpty()
            .filter { it.isFile && it.extension == "pid" && it.name != managerPidFile.name }
            .forEach { pidFile ->
                val pid = runCatching { pidFile.readText().trim().toLong() }.getOrDefault(0)
                if (pidFile.name == "config-server.pid" && isAlive(pid)) {
                    configServerWasRunning = true
                }
                val logFile = File(logsDir, pidFile.nameWithoutExtension + ".log")
                val command = buildString {
                    append("sh ")
                    append(shq(scriptFile.absolutePath))
                    append(" stop ")
                    append(shq(coreManager.coreFile.absolutePath))
                    append(' ')
                    append(shq(logFile.absolutePath))
                    append(' ')
                    append(shq(pidFile.absolutePath))
                }
                RootManager.su(command, timeoutMs = 12000)
                pidFile.delete()
            }
        return configServerWasRunning
    }

    private fun requireRootAndCore() {
        if (!RootManager.probe().available) {
            throw IllegalStateException("未检测到 root，请先在 Root 管理器中授权 MoonTier（KernelSU/Magisk/APatch）")
        }
        if (!coreManager.isReady()) {
            throw IllegalStateException("尚未下载官方 easytier-core，或管理客户端缺失")
        }
    }

    private fun runManager(vararg args: String, timeoutMs: Long): ShellResult {
        val command = buildString {
            append(shq(coreManager.managerClientFile.absolutePath))
            append(" -p ")
            append(shq(MANAGER_RPC_PORTAL))
            args.forEach {
                append(' ')
                append(shq(it))
            }
        }
        return RootManager.su(command, timeoutMs)
    }

    private fun managerError(result: ShellResult, fallback: String): String {
        val output = result.output.lineSequence().lastOrNull { it.isNotBlank() }.orEmpty()
        val log = readManagerLogTail(loadManagerOptions()).lastOrNull().orEmpty()
        return output.ifBlank { log }.ifBlank { fallback }
    }

    private fun managerAlive(): Boolean = isAlive(managerPid())

    private fun managerPid(): Long =
        runCatching { managerPidFile.readText().trim().toLong() }.getOrDefault(0)

    private fun isAlive(pid: Long): Boolean {
        if (pid <= 0) return false
        return RootManager.su("kill -0 $pid", timeoutMs = 3000).success
    }

    private fun writeLocalMeta(config: NetworkConfig) {
        localMetaFile(config.id).writeText(
            JSONObject()
                .put("config_id", config.id)
                .put("display_name", config.displayName)
                .put("instance_name", config.instanceName)
                .toString()
        )
    }

    private fun loadLocalMetadata(): Map<String, LocalInstanceMeta> {
        val result = LinkedHashMap<String, LocalInstanceMeta>()
        metaDir.listFiles().orEmpty()
            .filter { it.isFile && it.extension == "json" && it != managerMetaFile }
            .forEach { file ->
                val obj = runCatching { JSONObject(file.readText()) }.getOrNull() ?: return@forEach
                val id = obj.optString("config_id")
                if (id.isBlank()) return@forEach
                result[id] = LocalInstanceMeta(
                    configId = id,
                    displayName = obj.optString("display_name"),
                    instanceName = obj.optString("instance_name")
                )
            }
        return result
    }

    private fun localMetaFile(configId: String): File = File(metaDir, "${safeId(configId)}.json")

    private fun loadManagerOptions(): ManagerOptions? {
        val obj = runCatching { JSONObject(managerMetaFile.readText()) }.getOrNull() ?: return null
        val rawUrl = obj.optString("raw_config_server_url")
        return ManagerOptions(
            configServerEnabled = obj.optBoolean("config_server_enabled", false) &&
                isFullConfigServerUrl(rawUrl),
            rawConfigServerUrl = rawUrl,
            resolvedConfigServerUrl = obj.optString("resolved_config_server_url"),
            hostname = obj.optString("hostname"),
            machineId = obj.optString("machine_id"),
            secureMode = obj.optBoolean("secure_mode", true)
        )
    }

    private fun saveManagerOptions(options: ManagerOptions) {
        managerMetaFile.writeText(
            JSONObject()
                .put("config_server_enabled", options.configServerEnabled)
                .put("raw_config_server_url", options.rawConfigServerUrl)
                .put("resolved_config_server_url", options.resolvedConfigServerUrl)
                .put("hostname", options.hostname)
                .put("machine_id", options.machineId)
                .put("secure_mode", options.secureMode)
                .toString()
        )
    }

    private fun optionsFromSettings(settings: AppSettings, enabled: Boolean): ManagerOptions {
        val hostname = settings.configServerHostname.trim().ifBlank {
            effectiveDeviceHostname("config-server")
        }
        val machineId = settings.configServerMachineId.trim().ifBlank {
            Secure.getString(context.contentResolver, Secure.ANDROID_ID)
                .orEmpty()
                .trim()
                .ifBlank { "moontier-${Build.DEVICE}" }
        }
        return ManagerOptions(
            configServerEnabled = enabled && isFullConfigServerUrl(settings.configServerUrl),
            rawConfigServerUrl = settings.configServerUrl.trim(),
            hostname = hostname,
            machineId = machineId,
            secureMode = settings.configServerSecureMode
        )
    }

    private fun readManagerLogTail(options: ManagerOptions?): List<String> {
        if (CoreLogLevel.normalize(store.loadSettings().coreLogLevel) == CoreLogLevel.OFF) return emptyList()
        if (!managerLogFile.exists()) return emptyList()
        trimManagerLog()
        val rawUrl = options?.rawConfigServerUrl.orEmpty()
        val resolvedUrl = options?.resolvedConfigServerUrl.orEmpty()
        return runCatching { managerLogFile.readText() }
            .getOrDefault("")
            .lineSequence()
            .filter { it.isNotBlank() }
            .map { redactConfigServerToken(it, rawUrl, resolvedUrl) }
            .toList()
            .takeLast(80)
    }

    private fun trimManagerLog() {
        if (managerLogFile.length() <= MAX_MANAGER_LOG_BYTES) return
        runCatching {
            managerLogFile.writeText(
                managerLogFile.readText().takeLast(MAX_MANAGER_LOG_BYTES / 2)
            )
        }.onFailure { AppDiagnostics.warn("root", "manager log trimming failed", it) }
    }

    private fun filteredCoreLogs(logs: List<String>): List<String> = when (
        CoreLogLevel.normalize(store.loadSettings().coreLogLevel)
    ) {
        CoreLogLevel.OFF -> emptyList()
        CoreLogLevel.NORMAL -> logs.filter { IMPORTANT_CORE_LOG.containsMatchIn(it) }
        else -> logs
    }

    private fun redactConfigServerToken(line: String, rawUrl: String, resolvedUrl: String): String {
        var redacted = line
        listOf(rawUrl, resolvedUrl)
            .filter { it.isNotBlank() }
            .forEach { value -> redacted = redacted.replace(value, sanitizeConfigServerUrl(value)) }
        return CONFIG_SERVER_TOKEN_REGEX.replace(redacted, "\$1***")
    }

    private fun sanitizeConfigServerUrl(value: String): String =
        if (value.contains("/")) value.substringBeforeLast("/") + "/***" else "***"

    private fun parseJsonOutput(output: String): JSONObject? {
        output.lineSequence().toList().asReversed().forEach { line ->
            val trimmed = line.trim()
            if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
                runCatching { JSONObject(trimmed) }.getOrNull()?.let { return it }
            }
        }
        return runCatching { JSONObject(output.trim()) }.getOrNull()
    }

    private fun ensureDirs() {
        configsDir.mkdirs()
        stagingDir.mkdirs()
        logsDir.mkdirs()
        pidsDir.mkdirs()
        metaDir.mkdirs()
        coreManager.ensureDirectories()
    }

    private fun ensureScript() {
        scriptFile.parentFile?.mkdirs()
        context.assets.open("root/moontier_root.sh").use { input ->
            scriptFile.outputStream().use { output -> input.copyTo(output) }
        }
        scriptFile.setExecutable(true, false)
    }

    private fun effectiveHostname(config: NetworkConfig): String {
        if (config.hostname.isNotBlank() && !config.hostname.equals("localhost", ignoreCase = true)) {
            return config.hostname
        }
        return effectiveDeviceHostname(config.id)
    }

    private fun effectiveDeviceHostname(fallbackId: String): String {
        val candidates = listOf(
            runCatching { android.provider.Settings.Global.getString(context.contentResolver, "device_name") }.getOrNull(),
            runCatching { Secure.getString(context.contentResolver, "bluetooth_name") }.getOrNull(),
            Build.MODEL,
            Build.DEVICE
        )
        return candidates
            .map { it.orEmpty().trim() }
            .firstOrNull { it.isNotBlank() && !it.equals("localhost", ignoreCase = true) }
            ?: "Android-${fallbackId.take(8)}"
    }

    private fun resolveRootEndpoints(values: List<String>, store: ConfigStore): List<String> =
        values.flatMap { endpoint ->
            val host = Ipv4.hostFromUrl(endpoint)
            if (host.isBlank() || Ipv4.parseAddress(host) != null || host.contains(':')) {
                listOf(endpoint)
            } else {
                val addresses = store.resolveHostIps(host).filter { Ipv4.parseAddress(it) != null }
                if (addresses.isEmpty()) listOf(endpoint) else addresses.map {
                    replaceEndpointHost(endpoint, host, it)
                }
            }
        }.cleanItems()

    private fun replaceEndpointHost(endpoint: String, host: String, address: String): String {
        val authorityStart = endpoint.indexOf("://").let { if (it >= 0) it + 3 else 0 }
        val hostStart = endpoint.indexOf(host, authorityStart)
        if (hostStart < 0) return endpoint
        return endpoint.substring(0, hostStart) + address + endpoint.substring(hostStart + host.length)
    }

    private fun resolveConfigServerEndpoint(raw: String, store: ConfigStore): String {
        val trimmed = raw.trim()
        if (!isFullConfigServerUrl(trimmed)) {
            throw IllegalArgumentException("请填写完整配置服务器 URL，例如 udp://host:22020/admin")
        }
        val expanded = trimmed
        val scheme = expanded.substringBefore("://", "").lowercase()
        if (scheme == "ws" || scheme == "wss") return expanded
        val host = Ipv4.hostFromUrl(expanded)
        if (host.isBlank() || Ipv4.parseAddress(host) != null || host.contains(':')) return expanded
        val address = store.resolveHostIps(host).firstOrNull { Ipv4.parseAddress(it) != null }
            ?: return expanded
        return replaceEndpointHost(expanded, host, address)
    }

    private fun isFullConfigServerUrl(value: String): Boolean =
        value.trim().contains("://")

    private fun safeId(id: String): String =
        id.replace(Regex("[^A-Za-z0-9_-]"), "_").take(64).ifBlank { "config" }

    private fun shq(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    private data class ManagerOptions(
        val configServerEnabled: Boolean = false,
        val rawConfigServerUrl: String = "",
        val resolvedConfigServerUrl: String = "",
        val hostname: String = "",
        val machineId: String = "",
        val secureMode: Boolean = true
    ) {
        fun sameLaunchOptions(other: ManagerOptions): Boolean =
            configServerEnabled == other.configServerEnabled &&
                rawConfigServerUrl == other.rawConfigServerUrl &&
                hostname == other.hostname &&
                machineId == other.machineId &&
                secureMode == other.secureMode
    }

    private data class LocalInstanceMeta(
        val configId: String,
        val displayName: String,
        val instanceName: String
    )

    private data class ManagerInstanceMeta(
        val instanceId: String,
        val instanceName: String,
        val networkName: String,
        val source: Int
    )

    private data class ManagerSnapshot(
        val instanceIds: Set<String>,
        val metas: Map<String, ManagerInstanceMeta>,
        val runningInfo: JSONObject
    )

    private data class ParsedInstanceInfo(
        val running: Boolean = false,
        val localCidr: String = "",
        val nodes: List<NodeInfo> = emptyList(),
        val logs: List<String> = emptyList(),
        val error: String = ""
    )

    private data class RefreshedState(
        val instances: List<RootInstanceState>,
        val configServer: RootConfigServerState
    )

    companion object {
        private const val TAG = "MoonTierRoot"
        private const val MAX_MANAGER_LOG_BYTES = 512 * 1024
        private val IMPORTANT_CORE_LOG = Regex(
            "error|warn|fail|panic|closed|stop|disconnect|exit|timeout",
            RegexOption.IGNORE_CASE
        )
        private const val MANAGER_RPC_PORTAL = "127.0.0.1:14999"
        private const val CONFIG_SOURCE_WEB = 2
        private val CONFIG_SERVER_TOKEN_REGEX =
            Regex("((?:udp|tcp|ws|wss)://[^\\s/]+/)[^\\s]+", RegexOption.IGNORE_CASE)
    }
}

private fun JSONObject?.optJSONObjectCompat(primary: String, fallback: String): JSONObject? =
    this?.optJSONObject(primary) ?: this?.optJSONObject(fallback)

private fun JSONObject.optBooleanCompat(primary: String, fallback: String): Boolean =
    if (has(primary)) optBoolean(primary) else optBoolean(fallback)

private fun JSONObject.optIntCompat(primary: String, fallback: String, default: Int): Int =
    when {
        has(primary) -> optInt(primary, default)
        has(fallback) -> optInt(fallback, default)
        else -> default
    }

private fun JSONObject.optStringCompat(primary: String, fallback: String): String =
    optString(primary).ifBlank { optString(fallback) }
