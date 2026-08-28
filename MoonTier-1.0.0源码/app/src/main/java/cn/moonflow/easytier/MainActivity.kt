package cn.moonflow.easytier

import android.app.Activity
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.view.View
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.activity.compose.BackHandler
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    private lateinit var store: ConfigStore
    private lateinit var controller: EasyTierController
    private lateinit var rootController: RootTierController
    private var pendingVpnConfig: String? = null
    private var pendingExportText: String = ""
    private var pendingDiagnosticText: String = ""
    private var onImportedConfig: ((NetworkConfig) -> Unit)? = null
    private var onFileMessage: ((String, String) -> Unit)? = null

    private val vpnPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val vpnConfig = pendingVpnConfig
        if (result.resultCode == Activity.RESULT_OK) {
            AppDiagnostics.event("vpn", "VPN permission granted")
            vpnConfig?.let { startVpnServiceWith(it) }
        } else if (vpnConfig != null) {
            AppDiagnostics.warn("vpn", "VPN permission denied")
            controller.onVpnPermissionDenied()
        }
        pendingVpnConfig = null
    }

    private val importTomlLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        runCatching {
            contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }.orEmpty()
        }.mapCatching {
            TomlCodec.parse(it)
        }.onSuccess {
            onImportedConfig?.invoke(it)
        }.onFailure {
            onFileMessage?.invoke("导入失败", it.message ?: "无法读取 TOML")
        }
    }

    private val exportTomlLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/toml")
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        runCatching {
            contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { it.write(pendingExportText) }
        }.onSuccess {
            onFileMessage?.invoke("导出完成", "TOML 配置已写入文件")
        }.onFailure {
            onFileMessage?.invoke("导出失败", it.message ?: "无法写入文件")
        }
    }

    private val importCoreZipLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        AppDiagnostics.event("root", "core ZIP selected for import")
        rootController.installCoreZip(uri)
    }

    private val exportDiagnosticsLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain")
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        runCatching {
            contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { it.write(pendingDiagnosticText) }
                ?: error("无法写入日志文件")
        }.onSuccess {
            AppDiagnostics.info("launcher", "diagnostic report exported")
            onFileMessage?.invoke("导出完成", "应用日志已写入文件")
        }.onFailure {
            AppDiagnostics.error("launcher", "diagnostic report export failed", it)
            onFileMessage?.invoke("导出失败", it.message ?: "无法写入日志文件")
        }
    }

    private var isForeground by mutableStateOf(true)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = ConfigStore(applicationContext)
        AppDiagnostics.initialize(applicationContext, store.loadSettings().coreLogLevel)
        installUncaughtExceptionHandler()
        AppDiagnostics.event("launcher", "MainActivity created")
        controller = EasyTierController(applicationContext, store, ::requestVpn)
        rootController = RootTierController(applicationContext)

        window.attributes = window.attributes.apply { preferredRefreshRate = 120f }

        setContent {
            // 动态轮询：前台 30s，后台 2min
            MoonTierApp(
                isForeground = isForeground,
                store = store,
                controller = controller,
                rootController = rootController,
                importToml = { importTomlLauncher.launch(arrayOf("text/*", "application/toml", "application/octet-stream")) },
                importCoreZip = { importCoreZipLauncher.launch(arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream")) },
                exportToml = { config ->
                    pendingExportText = TomlCodec.build(config)
                    exportTomlLauncher.launch("${config.displayName}.toml")
                },
                exportDiagnostics = { report ->
                    pendingDiagnosticText = report
                    exportDiagnosticsLauncher.launch("moontier-diagnostics.txt")
                },
                bindImportHandler = { imported, message ->
                    onImportedConfig = imported
                    onFileMessage = message
                }
            )
        }

        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) { isForeground = true }
            override fun onStop(owner: LifecycleOwner) { isForeground = false }
        })
    }

    override fun onDestroy() {
        AppDiagnostics.event("launcher", "MainActivity destroyed")
        controller.release()
        rootController.release()
        super.onDestroy()
    }

    private fun requestVpn(vpnJson: String) {
        AppDiagnostics.event("vpn", "VPN permission check requested")
        val intent = runCatching { VpnService.prepare(this) }.getOrElse { error ->
            controller.onVpnServiceStartFailed(error)
            return
        }
        if (intent != null) {
            pendingVpnConfig = vpnJson
            runCatching { vpnPermissionLauncher.launch(intent) }
                .onFailure { controller.onVpnServiceStartFailed(it) }
        } else {
            startVpnServiceWith(vpnJson)
        }
    }

    private fun startVpnServiceWith(vpnJson: String) {
        runCatching {
            val intent = Intent(this, EasyTierVpnService::class.java)
                .putExtra(EasyTierVpnService.EXTRA_CONFIG, vpnJson)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent) else startService(intent)
            AppDiagnostics.event("vpn", "VPN service start requested")
        }.onFailure { controller.onVpnServiceStartFailed(it) }
    }

    private fun installUncaughtExceptionHandler() {
        synchronized(MainActivity::class.java) {
            if (crashHandlerInstalled) return
            val previous = Thread.getDefaultUncaughtExceptionHandler()
            Thread.setDefaultUncaughtExceptionHandler { thread, error ->
                AppDiagnostics.error("launcher", "uncaught crash on ${thread.name}", error)
                previous?.uncaughtException(thread, error)
            }
            crashHandlerInstalled = true
        }
    }

    private companion object {
        @Volatile
        var crashHandlerInstalled = false
    }
}

private data class NavItem(val label: String, val icon: ImageVector)

private data class ServerPickerState(
    val title: String,
    val servers: List<ServerEntry>
)

private fun isFullConfigServerUrl(value: String): Boolean =
    value.trim().contains("://")

private data class Palette(
    val background: Color,
    val surface: Color,
    val surfaceVariant: Color,
    val secondaryButton: Color,
    val text: Color,
    val subText: Color,
    val accent: Color,
    val onAccent: Color,
    val border: Color,
    val success: Color,
    val error: Color,
    val overlay: Color
) {
    companion object {
        val Light = Palette(
            background = Color.White,
            surface = Color.White,
            surfaceVariant = Color(0xFFF7F7F5),
            secondaryButton = Color(0xFFF2F2EF),
            text = Color(0xFF0D0D0D),
            subText = Color(0x850D0D0D),
            accent = Color(0xFF0D0D0D),
            onAccent = Color.White,
            border = Color(0x140D0D0D),
            success = Color(0xFF10A37F),
            error = Color(0xFFD92D20),
            overlay = Color(0x12000000)
        )
        val Dark = Palette(
            background = Color(0xFF0D0D0D),
            surface = Color(0xFF171717),
            surfaceVariant = Color(0xFF202020),
            secondaryButton = Color(0xFF2A2A2A),
            text = Color(0xFFF4F4F4),
            subText = Color(0x88F4F4F4),
            accent = Color(0xFFF4F4F4),
            onAccent = Color(0xFF0D0D0D),
            border = Color(0x18FFFFFF),
            success = Color(0xFF19C37D),
            error = Color(0xFFF04438),
            overlay = Color(0x18FFFFFF)
        )
    }
}

@Composable
private fun MoonTierApp(
    isForeground: Boolean,
    store: ConfigStore,
    controller: EasyTierController,
    rootController: RootTierController,
    importToml: () -> Unit,
    importCoreZip: () -> Unit,
    exportToml: (NetworkConfig) -> Unit,
    exportDiagnostics: (String) -> Unit,
    bindImportHandler: (((NetworkConfig) -> Unit, (String, String) -> Unit) -> Unit)
) {
    var settings by remember { mutableStateOf(store.loadSettings()) }
    var nodesExpanded by remember { mutableStateOf(false) }
    var configs by remember { mutableStateOf(store.loadConfigs()) }
    var userServers by remember { mutableStateOf(store.loadUserServers()) }
    var officialServers by remember { mutableStateOf(emptyList<ServerEntry>()) }
    var selectedTab by remember { mutableIntStateOf(0) }
    var editingConfig by remember { mutableStateOf<NetworkConfig?>(null) }
    var dialog by remember { mutableStateOf<Pair<String, String>?>(null) }
    var rootAvailable by remember { mutableStateOf(false) }
    var rootChecking by remember { mutableStateOf(false) }
    var rootSelectedConfigId by remember {
        mutableStateOf(configs.firstOrNull { it.isDefault }?.id ?: configs.firstOrNull()?.id.orEmpty())
    }
    val scope = rememberCoroutineScope()
    val palette = if (settings.darkMode) Palette.Dark else Palette.Light
    val runtime = controller.state

    fun reloadAll() {
        configs = store.loadConfigs()
        if (configs.none { it.id == rootSelectedConfigId }) {
            rootSelectedConfigId = configs.firstOrNull { it.isDefault }?.id ?: configs.firstOrNull()?.id.orEmpty()
        }
        userServers = store.loadUserServers()
        officialServers = emptyList()
        settings = store.loadSettings()
    }

    fun saveSettings(next: AppSettings) {
        val logLevelChanged = CoreLogLevel.normalize(next.coreLogLevel) != CoreLogLevel.normalize(settings.coreLogLevel)
        if (next.rootModeEnabled != settings.rootModeEnabled) {
            if (next.rootModeEnabled) {
                controller.stop()
            } else {
                rootController.stopAll()
            }
        }
        settings = next
        store.saveSettings(next)
        AppDiagnostics.configure(next.coreLogLevel)
        if (logLevelChanged) {
            controller.updateLogLevel(next.coreLogLevel)
            rootController.updateLogLevel(next.coreLogLevel)
        }
        AppDiagnostics.info("launcher", "settings saved mode=${if (next.rootModeEnabled) "root" else "vpn"} log=${next.coreLogLevel}")
    }

    fun refreshRootStatus() {
        scope.launch {
            rootChecking = true
            try {
                rootAvailable = withContext(Dispatchers.IO) {
                    RootManager.probe(refresh = true).available
                }
            } finally {
                rootChecking = false
            }
        }
    }

    LaunchedEffect(isForeground, nodesExpanded) {
        controller.updatePollingConditions(isForeground, nodesExpanded)
    }

    LaunchedEffect(Unit) {
        bindImportHandler(
            { cfg ->
                store.saveConfig(cfg.copy(id = java.util.UUID.randomUUID().toString(), isDefault = configs.isEmpty()))
                reloadAll()
                dialog = "导入完成" to "TOML 配置已加入配置文件列表"
            },
            { title, body -> dialog = title to body }
        )
    }

    LaunchedEffect(Unit) {
        controller.attachIfRunning(configs)
    }

    LaunchedEffect(Unit) {
        rootAvailable = withContext(Dispatchers.IO) {
            RootManager.probe().available
        }
    }

    LaunchedEffect(
        settings.rootModeEnabled,
        settings.configServerAutoConnect,
        settings.configServerUrl,
        rootAvailable,
        rootController.state.core.ready
    ) {
        if (
            settings.rootModeEnabled &&
            settings.configServerAutoConnect &&
            isFullConfigServerUrl(settings.configServerUrl) &&
            rootAvailable &&
            rootController.state.core.ready &&
            !rootController.state.configServer.running &&
            !rootController.state.configServer.starting
        ) {
            rootController.startConfigServer(settings)
        }
    }

    ApplySystemBars(palette, settings.darkMode)

    MaterialTheme(colorScheme = if (settings.darkMode) darkColorScheme() else lightColorScheme()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(palette.background)
        ) {
            AnimatedContent(
                targetState = editingConfig,
                transitionSpec = {
                    (fadeIn(tween(180)) + scaleIn(initialScale = 0.92f, animationSpec = tween(240)) + fadeIn(tween(240)))
                        .togetherWith(fadeOut(tween(130)) + scaleOut(targetScale = 0.92f, animationSpec = tween(180)))
                },
                label = "screen"
            ) { editing ->
                if (editing != null) {
                    ConfigEditorScreenV2(
                        palette = palette,
                        initial = editing,
                        userServers = userServers,
                        officialServers = officialServers,
                        onBack = { editingConfig = null },
                        onSave = {
                            if (it.id == editing.id) {
                                store.saveConfig(it)
                            } else {
                                val current = store.loadConfigs().filterNot { cfg -> cfg.id == editing.id }
                                store.saveConfigs(current + it)
                            }
                            reloadAll()
                            editingConfig = null
                        },
                        onExport = exportToml
                    )
                } else {
                    MainTabs(
                        palette = palette,
                        nodesExpanded = nodesExpanded,
                        onNodesExpandedChange = { nodesExpanded = it },
                        selectedTab = selectedTab,
                        onTab = { selectedTab = it },
                        configs = configs,
                        settings = settings,
                        runtime = runtime,
                        rootController = rootController,
                        rootAvailable = rootAvailable,
                        rootChecking = rootChecking,
                        rootMode = settings.rootModeEnabled,
                        rootSelectedConfigId = rootSelectedConfigId,
                        onRootConfigSelected = { rootSelectedConfigId = it },
                        onRefreshRoot = ::refreshRootStatus,
                        userServers = userServers,
                        officialServers = officialServers,
                        onStart = { controller.start(it) },
                        onStop = { controller.stop() },
                        onEdit = { editingConfig = it },
                        onNewConfig = {
                            editingConfig = NetworkConfig.defaultConfig(default = configs.isEmpty()).copy(
                                label = "新配置",
                                instanceName = "MoonTier-${System.currentTimeMillis().toString().takeLast(6)}"
                            )
                        },
                        onImportToml = importToml,
                        onDeleteConfig = {
                            if (settings.rootModeEnabled) rootController.stop(it.id)
                            if (!settings.rootModeEnabled && runtime.runningConfigId == it.id) controller.stop()
                            store.deleteConfig(it.id)
                            reloadAll()
                        },
                        onDefaultConfig = {
                            store.setDefaultConfig(it.id)
                            reloadAll()
                        },
                        onExportConfig = exportToml,
                        onSaveUserServers = {
                            store.saveUserServers(it)
                            reloadAll()
                        },
                        onAddOfficialServer = { server ->
                            val current = store.loadUserServers()
                            val named = server.copy(name = nextServerName(current, server.name.ifBlank { serverNameFromAddress(server.address) }))
                            store.saveUserServers((current + named).distinctBy { it.address.lowercase() })
                            reloadAll()
                            dialog = "已添加" to server.address
                        },
                        onSettings = {
                            saveSettings(it)
                        },
                        onSyncOfficial = {
                            scope.launch {
                                runCatching { store.syncOfficialServers() }
                                    .onSuccess {
                                        reloadAll()
                                        dialog = "同步完成" to it.message
                                    }.onFailure {
                                        dialog = "同步失败" to (it.message ?: "无法同步官方源")
                                    }
                            }
                        },
                        onDownloadUsers = { source ->
                            scope.launch {
                                runCatching { store.downloadUserServers(source) }
                                    .onSuccess {
                                        reloadAll()
                                        dialog = "下载完成" to it.message
                                    }.onFailure {
                                        dialog = "下载失败" to (it.message ?: "无法下载服务器列表")
                                }
                            }
                        },
                        onConnectConfigServer = {
                            if (!isFullConfigServerUrl(settings.configServerUrl)) {
                                dialog = "网页控制台" to "请填写完整配置服务器 URL，例如 udp://host:22020/admin"
                            } else {
                                rootController.startConfigServer(settings)
                            }
                        },
                        onDisconnectConfigServer = { rootController.stopConfigServer() },
                        onImportCoreZip = importCoreZip,
                        onExportDiagnostics = {
                            exportDiagnostics(
                                AppDiagnostics.buildReport(
                                    settings = settings,
                                    runtime = runtime,
                                    root = rootController.state,
                                    managerLog = rootController.diagnosticLogTail()
                                )
                            )
                        }
                    )
                }
            }

            dialog?.let { (title, body) ->
                AppleToast(
                    title = title,
                    body = body,
                    palette = palette,
                    onDismiss = { dialog = null }
                )
            }
        }
    }
}

@Composable
private fun ApplySystemBars(palette: Palette, darkMode: Boolean) {
    val view = LocalView.current
    SideEffect {
        val window = (view.context as Activity).window
        @Suppress("DEPRECATION")
        // Keep explicit bar colors until the app is migrated to a full edge-to-edge layout.
        window.statusBarColor = palette.background.toArgbInt()
        @Suppress("DEPRECATION")
        window.navigationBarColor = palette.background.toArgbInt()
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = if (darkMode) 0 else (
            View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR else 0
            )
    }
}

@Composable
private fun MainTabs(
    palette: Palette,
    nodesExpanded: Boolean,
    onNodesExpandedChange: (Boolean) -> Unit,
    selectedTab: Int,
    onTab: (Int) -> Unit,
    configs: List<NetworkConfig>,
    settings: AppSettings,
    runtime: RuntimeState,
    rootController: RootTierController,
    rootAvailable: Boolean,
    rootChecking: Boolean,
    rootMode: Boolean,
    rootSelectedConfigId: String,
    onRootConfigSelected: (String) -> Unit,
    onRefreshRoot: () -> Unit,
    userServers: List<ServerEntry>,
    officialServers: List<ServerEntry>,
    onStart: (NetworkConfig) -> Unit,
    onStop: () -> Unit,
    onEdit: (NetworkConfig) -> Unit,
    onNewConfig: () -> Unit,
    onImportToml: () -> Unit,
    onDeleteConfig: (NetworkConfig) -> Unit,
    onDefaultConfig: (NetworkConfig) -> Unit,
    onExportConfig: (NetworkConfig) -> Unit,
    onSaveUserServers: (List<ServerEntry>) -> Unit,
    onAddOfficialServer: (ServerEntry) -> Unit,
    onSettings: (AppSettings) -> Unit,
    onSyncOfficial: () -> Unit,
    onDownloadUsers: (String) -> Unit,
    onConnectConfigServer: () -> Unit,
    onDisconnectConfigServer: () -> Unit,
    onImportCoreZip: () -> Unit,
    onExportDiagnostics: () -> Unit
) {
    val tabs = remember {
        listOf(
            NavItem("网络配置", Icons.Rounded.Wifi),
            NavItem("配置文件", Icons.Rounded.Description),
            NavItem("服务器", Icons.Rounded.Storage),
            NavItem("设置", Icons.Rounded.Settings)
        )
    }
    Column(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .pointerInput(selectedTab) {
                    var offsetX = 0f
                    detectHorizontalDragGestures(
                        onDragEnd = {
                            if (offsetX < -150f && selectedTab < tabs.size - 1) {
                                onTab(selectedTab + 1)
                            } else if (offsetX > 150f && selectedTab > 0) {
                                onTab(selectedTab - 1)
                            }
                            offsetX = 0f
                        },
                        onDragCancel = { offsetX = 0f },
                        onHorizontalDrag = { _, amount -> offsetX += amount }
                    )
                }
        ) {
            AnimatedContent(
                targetState = selectedTab,
                transitionSpec = {
                    val dir = if (targetState > initialState) 1 else -1
                    (fadeIn(tween(170)) + scaleIn(initialScale = 0.94f, animationSpec = tween(230)) + fadeIn(tween(230)))
                        .togetherWith(fadeOut(tween(120)) + scaleOut(targetScale = 0.94f, animationSpec = tween(190)))
                },
                label = "tab"
            ) { index ->
                when (index) {
                    0 -> NetworkPage(palette, configs, runtime, nodesExpanded, onNodesExpandedChange, onStart, onStop, onEdit, rootController, rootAvailable, rootMode, rootSelectedConfigId, onRootConfigSelected)
                    1 -> ProfilesPage(palette, configs, runtime, rootController, rootAvailable, rootMode, onStart, onStop, onEdit, onNewConfig, onImportToml, onDeleteConfig, onDefaultConfig, onExportConfig)
                    2 -> ServersPage(
                        palette = palette,
                        userServers = userServers,
                        officialServers = officialServers,
                        rootMode = rootMode,
                        rootAvailable = rootAvailable,
                        settings = settings,
                        configServer = rootController.state.configServer,
                        onSettings = onSettings,
                        onConnectConfigServer = onConnectConfigServer,
                        onDisconnectConfigServer = onDisconnectConfigServer,
                        onSaveUserServers = onSaveUserServers,
                        onAddOfficialServer = onAddOfficialServer,
                        onDownloadUsers = onDownloadUsers,
                        onSyncOfficial = onSyncOfficial
                    )
                    else -> SettingsPage(
                        palette = palette,
                        settings = settings,
                        rootController = rootController,
                        rootAvailable = rootAvailable,
                        rootChecking = rootChecking,
                        onSettings = onSettings,
                        onRefreshRoot = onRefreshRoot,
                        onImportCoreZip = onImportCoreZip,
                        onExportDiagnostics = onExportDiagnostics
                    )
                }
            }
        }
        BottomNav(palette, tabs, selectedTab, onTab)
    }
}

@Composable
private fun NetworkPage(
    palette: Palette,
    configs: List<NetworkConfig>,
    runtime: RuntimeState,
    nodesExpanded: Boolean,
    onNodesExpandedChange: (Boolean) -> Unit,
    onStart: (NetworkConfig) -> Unit,
    onStop: () -> Unit,
    onEdit: (NetworkConfig) -> Unit,
    rootController: RootTierController,
    rootAvailable: Boolean,
    rootMode: Boolean,
    rootSelectedConfigId: String,
    onRootConfigSelected: (String) -> Unit
) {
    if (rootMode) {
        RootNetworkPage(
            palette = palette,
            configs = configs,
            rootController = rootController,
            rootAvailable = rootAvailable,
            rootSelectedConfigId = rootSelectedConfigId,
            onRootConfigSelected = onRootConfigSelected,
            onStart = { rootController.start(it) },
            onEdit = onEdit
        )
        return
    }
    val current = if (runtime.running) {
        configs.firstOrNull { it.id == runtime.runningConfigId } ?: configs.firstOrNull { it.isDefault }
    } else {
        configs.firstOrNull { it.isDefault }
    } ?: NetworkConfig.defaultConfig()

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(horizontal = 16.dp),
        contentPadding = PaddingValues(top = 14.dp, bottom = 104.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Header(
                title = if (runtime.running) "当前网络" else "默认配置",
                subtitle = current.displayName,
                palette = palette
            )
        }
        item {
            AnimatedContent(
                targetState = runtime.running,
                transitionSpec = {
                    (fadeIn(tween(180)) + scaleIn(initialScale = 0.94f, animationSpec = tween(200)) + fadeIn(tween(200)))
                        .togetherWith(fadeOut(tween(140)) + scaleOut(targetScale = 0.94f, animationSpec = tween(140)))
                },
                label = "network-mode"
            ) { running ->
                if (!running) {
                    QCard(palette) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(current.displayName, color = palette.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                                Text(current.networkName.ifBlank { "尚未填写网络名称" }, color = palette.subText, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                        Spacer(Modifier.height(12.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            QButton(
                                text = if (runtime.starting) "启动中..." else "启动",
                                palette = palette,
                                modifier = Modifier.weight(1f),
                                enabled = !runtime.starting,
                                icon = Icons.Rounded.PlayArrow,
                                onClick = { onStart(current) }
                            )
                            QButton(
                                text = "编辑",
                                palette = palette,
                                modifier = Modifier.weight(1f),
                                primary = false,
                                icon = Icons.Rounded.Edit,
                                onClick = { onEdit(current) }
                            )
                        }
                    }
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            QButton(
                                text = "编辑配置",
                                palette = palette,
                                modifier = Modifier.weight(1f),
                                primary = false,
                                icon = Icons.Rounded.Edit,
                                enabled = !runtime.stopping,
                                onClick = { onEdit(current) }
                            )
                            QButton(
                                text = if (runtime.stopping) "停止中..." else "停止",
                                palette = palette,
                                modifier = Modifier.weight(1f),
                                danger = true,
                                icon = Icons.Rounded.Stop,
                                enabled = !runtime.stopping,
                                onClick = onStop
                            )
                        }
                        RunningStatus(palette, runtime, nodesExpanded, onNodesExpandedChange, current.displayName)
                    }
                }
            }
        }
    }
}

@Composable
private fun RootNetworkPage(
    palette: Palette,
    configs: List<NetworkConfig>,
    rootController: RootTierController,
    rootAvailable: Boolean,
    rootSelectedConfigId: String,
    onRootConfigSelected: (String) -> Unit,
    onStart: (NetworkConfig) -> Unit,
    onEdit: (NetworkConfig) -> Unit
) {
    val rootState = rootController.state
    val instances = rootState.instances.filter { it.running || it.starting || it.stopping }
    val defaultConfig = configs.firstOrNull { it.isDefault } ?: configs.firstOrNull() ?: NetworkConfig.defaultConfig()
    val selectedConfigId = configs.firstOrNull { it.id == rootSelectedConfigId }?.id ?: defaultConfig.id
    LaunchedEffect(configs, rootSelectedConfigId, selectedConfigId) {
        if (configs.isNotEmpty() && selectedConfigId != rootSelectedConfigId) {
            onRootConfigSelected(selectedConfigId)
        }
    }
    val current = configs.firstOrNull { it.id == selectedConfigId } ?: defaultConfig
    val selectedInstance = instances.firstOrNull { it.configId == current.id }
    val core = rootState.core

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(horizontal = 16.dp),
        contentPadding = PaddingValues(top = 14.dp, bottom = 104.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Header(
                title = "Root 模式",
                subtitle = if (rootAvailable) {
                    "可用 · 核心 ${core.installedVersion.ifBlank { "未安装" }}"
                } else {
                    "未检测到 root，请在 KernelSU 中授权"
                },
                palette = palette
            )
        }
        item {
            QCard(palette) {
                Text("当前配置", color = palette.text, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(6.dp))
                RootConfigSelect(
                    configs = configs,
                    selectedId = current.id,
                    runningIds = instances.map { it.configId }.toSet(),
                    palette = palette,
                    onSelected = onRootConfigSelected
                )
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    QButton(
                        text = when {
                            selectedInstance?.starting == true -> "启动中"
                            selectedInstance?.stopping == true -> "停止中"
                            selectedInstance?.running == true -> "停止"
                            else -> "启动"
                        },
                        palette = palette,
                        modifier = Modifier.weight(1f),
                        danger = selectedInstance != null,
                        enabled = selectedInstance?.stopping != true && rootAvailable && core.ready,
                        icon = if (selectedInstance != null) Icons.Rounded.Stop else Icons.Rounded.PlayArrow,
                        onClick = {
                            if (selectedInstance != null) rootController.stop(current.id) else onStart(current)
                        }
                    )
                    QButton(
                        text = "编辑",
                        palette = palette,
                        modifier = Modifier.weight(1f),
                        primary = false,
                        icon = Icons.Rounded.Edit,
                        onClick = { onEdit(current) }
                    )
                }
                if (!core.ready) {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "请先到设置下载 easytier-core 与 easytier-cli。",
                        color = palette.subText,
                        fontSize = 12.sp,
                        lineHeight = 18.sp
                    )
                }
            }
        }

        selectedInstance?.let { instance ->
            item(key = "selected-${instance.configId}") {
                QCard(palette) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(instance.displayName, color = palette.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                when {
                                    instance.stopping -> "正在停止"
                                    instance.starting -> "正在启动"
                                    instance.localCidr.isNotBlank() -> "已连接: ${instance.localCidr}"
                                    else -> "核心运行中"
                                },
                                color = palette.subText,
                                fontSize = 12.sp
                            )
                        }
                        QButton(
                            text = "停止",
                            palette = palette,
                            modifier = Modifier.width(86.dp),
                            compact = true,
                            danger = true,
                            enabled = !instance.stopping,
                            onClick = { rootController.stop(instance.configId) }
                        )
                    }
                    if (instance.error.isNotBlank()) {
                        Spacer(Modifier.height(8.dp))
                        Text(instance.error, color = palette.error, fontSize = 12.sp, lineHeight = 17.sp)
                    }
                    Spacer(Modifier.height(8.dp))
                    var nodesExpanded by remember(instance.configId) { mutableStateOf(true) }
                    NodeList(
                        palette = palette,
                        nodes = instance.nodes,
                        expanded = nodesExpanded,
                        onExpandedChange = { nodesExpanded = it }
                    )
                    Spacer(Modifier.height(8.dp))
                    var logsExpanded by remember(instance.configId) { mutableStateOf(false) }
                    LogSection(
                        palette = palette,
                        logs = instance.logs,
                        expanded = logsExpanded,
                        onExpandedChange = { logsExpanded = it }
                    )
                }
            }
        }

    }
}

@Composable
private fun RootConfigSelect(
    configs: List<NetworkConfig>,
    selectedId: String,
    runningIds: Set<String>,
    palette: Palette,
    onSelected: (String) -> Unit
) {
    var open by remember { mutableStateOf(false) }
    val selected = configs.firstOrNull { it.id == selectedId }
    val arrowRotation by animateFloatAsState(
        targetValue = if (open) 180f else 0f,
        animationSpec = tween(180),
        label = "config-arrow"
    )
    Column(
        modifier = Modifier
            .fillMaxWidth()
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(46.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(palette.surfaceVariant)
                .border(BorderStroke(0.7.dp, palette.border), RoundedCornerShape(8.dp))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) { open = !open }
                .padding(horizontal = 14.dp),
            contentAlignment = Alignment.CenterStart
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    selected?.displayName.orEmpty(),
                    color = palette.text,
                    fontSize = 14.sp,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (selectedId in runningIds) {
                    Text("运行中", color = palette.success, fontSize = 12.sp)
                    Spacer(Modifier.width(8.dp))
                }
                Icon(
                    Icons.Rounded.KeyboardArrowDown,
                    null,
                    tint = palette.subText,
                    modifier = Modifier
                        .size(22.dp)
                        .graphicsLayer { rotationZ = arrowRotation }
                )
            }
        }
        AnimatedVisibility(
            visible = open,
            enter = expandVertically(animationSpec = tween(200)) + fadeIn(tween(140)),
            exit = shrinkVertically(animationSpec = tween(160)) + fadeOut(tween(100))
        ) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp)
                    .heightIn(max = 320.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(palette.surface)
                    .border(BorderStroke(0.8.dp, palette.border), RoundedCornerShape(8.dp))
                    .padding(vertical = 6.dp)
                    .zIndex(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                items(configs, key = { it.id }) { config ->
                    val checked = config.id == selectedId
                    val running = config.id in runningIds
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(6.dp))
                            .background(
                                if (checked) palette.accent.copy(alpha = 0.10f)
                                else Color.Transparent
                            )
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null
                            ) {
                                onSelected(config.id)
                                open = false
                            }
                            .padding(horizontal = 12.dp, vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                config.displayName,
                                color = palette.text,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                config.networkName.ifBlank { "未填写网络名称" },
                                color = if (running) palette.success else palette.subText,
                                fontSize = 12.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        if (running) {
                            Spacer(Modifier.width(10.dp))
                            Text("运行中", color = palette.success, fontSize = 12.sp)
                        }
                        if (checked) {
                            Spacer(Modifier.width(10.dp))
                            Icon(Icons.Rounded.Check, null, tint = palette.accent)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RunningStatus(palette: Palette, runtime: RuntimeState, nodesExpanded: Boolean, onNodesExpandedChange: (Boolean) -> Unit, label: String) {
    var logsExpanded by remember { mutableStateOf(false) }
    QCard(palette, modifier = Modifier.fillMaxWidth()) {
        Text(label, color = palette.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        Text(runtime.statusText, color = palette.subText, fontSize = 13.sp, modifier = Modifier.padding(top = 3.dp))
        Spacer(Modifier.height(14.dp))
        NodeList(palette, runtime.nodes, nodesExpanded, onNodesExpandedChange)
        Spacer(Modifier.height(10.dp))
        LogSection(palette, runtime.logs, logsExpanded) { logsExpanded = it }
    }
}

@Composable
private fun NodeList(
    palette: Palette,
    nodes: List<NodeInfo>,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text("设备列表", color = palette.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Text(
                if (nodes.isEmpty()) "等待节点信息" else "${nodes.size} 个设备",
                color = palette.subText,
                fontSize = 12.sp
            )
        }
        ExpandArrow(expanded, palette) { onExpandedChange(!expanded) }
    }
    AnimatedVisibility(visible = expanded, enter = fadeIn(tween(160)), exit = fadeOut(tween(120))) {
        Column {
            if (nodes.isEmpty()) {
                Text("等待节点信息", color = palette.subText, fontSize = 13.sp, modifier = Modifier.padding(vertical = 6.dp))
            } else {
                nodes.forEach { node ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        StatusDot(if (node.local) palette.success else palette.subText)
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(node.hostname, color = palette.text, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("${node.ip} · ${node.role}", color = palette.subText, fontSize = 12.sp, maxLines = 1)
                        }
                        if (node.latencyMs >= 0) Text("${node.latencyMs} ms", color = palette.subText, fontSize = 12.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun LogSection(
    palette: Palette,
    logs: List<String>,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("运行日志", color = palette.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        ExpandArrow(expanded, palette) { onExpandedChange(!expanded) }
    }
    AnimatedVisibility(visible = expanded, enter = fadeIn(tween(160)), exit = fadeOut(tween(120))) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(palette.surfaceVariant)
                .padding(10.dp)
        ) {
            logs.takeLast(18).ifEmpty { listOf("暂无日志") }.forEach {
                Text(it, color = palette.subText, fontSize = 12.sp, lineHeight = 17.sp)
            }
        }
    }
}

@Composable
private fun ProfilesPage(
    palette: Palette,
    configs: List<NetworkConfig>,
    runtime: RuntimeState,
    rootController: RootTierController,
    rootAvailable: Boolean,
    rootMode: Boolean,
    onStart: (NetworkConfig) -> Unit,
    onStop: () -> Unit,
    onEdit: (NetworkConfig) -> Unit,
    onNew: () -> Unit,
    onImportToml: () -> Unit,
    onDelete: (NetworkConfig) -> Unit,
    onDefault: (NetworkConfig) -> Unit,
    onExport: (NetworkConfig) -> Unit
    ) {
    var pendingDeleteConfig by remember { mutableStateOf<NetworkConfig?>(null) }
    var deletingConfigId by remember { mutableStateOf<String?>(null) }
    val rootState = rootController.state
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(horizontal = 16.dp),
        contentPadding = PaddingValues(top = 14.dp, bottom = 26.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Header(
                "配置文件",
                if (rootMode) "Root 模式可同时运行多个网络" else "同一时间只能运行一个 VPN 配置",
                palette
            )
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                QButton("新建", palette, Modifier.weight(1f), icon = Icons.Rounded.Add, onClick = onNew)
                QButton("导入 TOML", palette, Modifier.weight(1f), primary = false, onClick = onImportToml)
            }
        }
        items(configs, key = { it.id }) { cfg ->
            val rootInstance = if (rootMode) rootState.instances.firstOrNull { it.configId == cfg.id } else null
            val runningHere = if (rootMode) {
                rootInstance?.running == true || rootInstance?.starting == true || rootInstance?.stopping == true
            } else {
                runtime.running && runtime.runningConfigId == cfg.id
            }
            val runnable = if (rootMode) rootAvailable else true
            AnimatedVisibility(
                visible = cfg.id != deletingConfigId,
                exit = shrinkVertically(animationSpec = tween(280)) + fadeOut(tween(200))
            ) {
            QCard(palette, modifier = Modifier.animateContentSize(tween(280))) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(cfg.displayName, color = palette.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(cfg.networkName.ifBlank { "尚未填写网络名称" }, color = palette.subText, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    DefaultPill(palette, selected = cfg.isDefault, onClick = { if (!cfg.isDefault) onDefault(cfg) })
                }
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    QButton(
                        text = when {
                            rootInstance?.starting == true -> "启动中"
                            rootInstance?.stopping == true -> "停止中"
                            runningHere -> "停止"
                            rootMode && !rootAvailable -> "无 Root"
                            else -> "运行"
                        },
                        palette = palette,
                        modifier = Modifier.weight(1f),
                        danger = runningHere,
                        enabled = runningHere || runnable,
                        icon = if (runningHere) Icons.Rounded.Stop else Icons.Rounded.PlayArrow,
                        onClick = {
                            if (runningHere) {
                                if (rootMode) rootController.stop(cfg.id) else onStop()
                            } else {
                                if (rootMode) rootController.start(cfg) else onStart(cfg)
                            }
                        }
                    )
                    QButton("编辑", palette, Modifier.weight(1f), primary = false, icon = Icons.Rounded.Edit, onClick = { onEdit(cfg) })
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    QButton("导出", palette, Modifier.weight(1f), primary = false, onClick = { onExport(cfg) })
                    QButton("删除", palette, Modifier.weight(1f), primary = false, dangerText = true, icon = Icons.Rounded.Delete, enabled = !runningHere, onClick = { pendingDeleteConfig = cfg })
                }
            }
            }
        }
    }

            pendingDeleteConfig?.let { cfg ->
        AlertDialog(
            onDismissRequest = { pendingDeleteConfig = null },
            title = { Text("确认删除", color = palette.text, fontWeight = FontWeight.SemiBold) },
            text = { Text("确定要删除配置「${cfg.displayName}」吗？此操作不可撤销。", color = palette.subText, lineHeight = 20.sp) },
            confirmButton = {
                QButton("删除", palette, compact = true, danger = true) {
                    deletingConfigId = cfg.id
                    pendingDeleteConfig = null
                }
            },
            dismissButton = {
                QButton("取消", palette, primary = false, compact = true) { pendingDeleteConfig = null }
            },
            containerColor = palette.surface,
            shape = RoundedCornerShape(22.dp)
        )
    }
    LaunchedEffect(deletingConfigId) {
        deletingConfigId?.let { id ->
            delay(300)
            val cfg = configs.firstOrNull { it.id == id }
            if (cfg != null) onDelete(cfg)
            deletingConfigId = null
        }
    }
}


@Composable
private fun ServersPage(
    palette: Palette,
    userServers: List<ServerEntry>,
    officialServers: List<ServerEntry>,
    rootMode: Boolean,
    rootAvailable: Boolean,
    settings: AppSettings,
    configServer: RootConfigServerState,
    onSettings: (AppSettings) -> Unit,
    onConnectConfigServer: () -> Unit,
    onDisconnectConfigServer: () -> Unit,
    onSaveUserServers: (List<ServerEntry>) -> Unit,
    onAddOfficialServer: (ServerEntry) -> Unit,
    onDownloadUsers: (String) -> Unit,
    onSyncOfficial: () -> Unit
) {
    var favoriteOpen by remember { mutableStateOf(false) }
    var officialOpen by remember { mutableStateOf(false) }
    var consoleOpen by remember { mutableStateOf(false) }
    var consoleLogsOpen by remember { mutableStateOf(false) }
    var source by remember { mutableStateOf("") }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(horizontal = 16.dp),
        contentPadding = PaddingValues(top = 14.dp, bottom = 26.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Header(
                "服务器",
                if (rootMode) "网页控制台、用户收藏与官方源" else "用户收藏和官方源分开管理",
                palette
            )
        }
        if (rootMode) {
            item {
                QCard(palette) {
                    val statusText = when {
                        !rootAvailable -> "Root 不可用"
                        configServer.stopping -> "正在断开"
                        configServer.starting -> "正在启动"
                        configServer.connected -> "已连接"
                        configServer.running -> "正在连接"
                        configServer.error.isNotBlank() -> "连接失败"
                        else -> "未连接"
                    }
                    val statusColor = when {
                        configServer.connected -> palette.success
                        configServer.error.isNotBlank() || !rootAvailable -> palette.error
                        else -> palette.subText
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("网页控制台", color = palette.text, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                            Text(
                                if (configServer.managedNetworks.isEmpty()) statusText else "$statusText · ${configServer.managedNetworks.size} 个下发网络",
                                color = statusColor,
                                fontSize = 12.sp
                            )
                        }
                        StatusDot(statusColor)
                        Spacer(Modifier.width(8.dp))
                        ExpandArrow(consoleOpen, palette) { consoleOpen = !consoleOpen }
                    }
                    AnimatedVisibility(
                        visible = consoleOpen,
                        enter = expandVertically(animationSpec = tween(220)) + fadeIn(tween(160)),
                        exit = shrinkVertically(animationSpec = tween(180)) + fadeOut(tween(120))
                    ) {
                        Column(Modifier.padding(top = 10.dp)) {
                            FieldBlock("完整配置服务器 URL", palette) {
                                QTextField(
                                    value = settings.configServerUrl,
                                    placeholder = "udp://host:22020/admin",
                                    palette = palette,
                                    onValueChange = { onSettings(settings.copy(configServerUrl = it)) }
                                )
                            }
                            FieldBlock("主机名", palette) {
                                QTextField(
                                    value = settings.configServerHostname,
                                    placeholder = "留空时使用设备名",
                                    palette = palette,
                                    onValueChange = { onSettings(settings.copy(configServerHostname = it)) }
                                )
                            }
                            FieldBlock("机器 ID", palette) {
                                QTextField(
                                    value = settings.configServerMachineId,
                                    placeholder = "留空时使用 Android 设备 ID",
                                    palette = palette,
                                    onValueChange = { onSettings(settings.copy(configServerMachineId = it)) }
                                )
                            }
                            SwitchRow("配置服务器加密（Secure Mode）", settings.configServerSecureMode, palette) {
                                onSettings(settings.copy(configServerSecureMode = it))
                            }
                            SwitchRow("启动时自动连接", settings.configServerAutoConnect, palette) {
                                onSettings(settings.copy(configServerAutoConnect = it))
                            }
                            if (configServer.error.isNotBlank()) {
                                Text(configServer.error, color = palette.error, fontSize = 12.sp, lineHeight = 18.sp)
                            }
                            if (configServer.managedNetworks.isNotEmpty()) {
                                Spacer(Modifier.height(8.dp))
                                configServer.managedNetworks.forEach { network ->
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 5.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        StatusDot(palette.success)
                                        Spacer(Modifier.width(10.dp))
                                        Column(Modifier.weight(1f)) {
                                            Text(
                                                network.instanceName.ifBlank { network.instanceId.ifBlank { "下发网络" } },
                                                color = palette.text,
                                                fontSize = 13.sp,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                            Text(network.localCidr.ifBlank { "等待虚拟 IP" }, color = palette.subText, fontSize = 12.sp)
                                        }
                                    }
                                }
                            }
                            Spacer(Modifier.height(10.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                QButton(
                                    text = if (configServer.starting) "启动中" else "连接",
                                    palette = palette,
                                    modifier = Modifier.weight(1f),
                                    enabled = rootAvailable && !configServer.running && !configServer.starting && !configServer.stopping && isFullConfigServerUrl(settings.configServerUrl),
                                    onClick = onConnectConfigServer
                                )
                                QButton(
                                    text = if (configServer.stopping) "断开中" else "断开",
                                    palette = palette,
                                    modifier = Modifier.weight(1f),
                                    primary = false,
                                    dangerText = true,
                                    enabled = configServer.running || configServer.starting,
                                    onClick = onDisconnectConfigServer
                                )
                            }
                            if (configServer.logs.isNotEmpty()) {
                                Spacer(Modifier.height(8.dp))
                                LogSection(
                                    palette = palette,
                                    logs = configServer.logs,
                                    expanded = consoleLogsOpen,
                                    onExpandedChange = { consoleLogsOpen = it }
                                )
                            }
                        }
                    }
                }
            }
        }
        item {
            QCard(palette) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("用户收藏", color = palette.text, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                        Text("${userServers.size} 个服务器", color = palette.subText, fontSize = 12.sp)
                    }
                    ExpandArrow(favoriteOpen, palette) { favoriteOpen = !favoriteOpen }
                }
                AnimatedVisibility(
                    visible = favoriteOpen,
                    enter = expandVertically(animationSpec = tween(220)) + fadeIn(tween(160)),
                    exit = shrinkVertically(animationSpec = tween(180)) + fadeOut(tween(120))
                ) {
                    Column(Modifier.padding(top = 12.dp)) {
                        ListEditor(
                            title = "收藏地址",
                            values = userServers.map { it.address },
                            placeholder = "tcp://server.example.com:11010",
                            palette = palette,
                            onValues = { values ->
                                onSaveUserServers(
                                    values.map { address ->
                                        ServerEntry(serverNameFromAddress(address), address)
                                    }
                                )
                            }
                        )
                    }
                }
            }
        }
        item {
            QCard(palette) {
                Text("TXT 下载到用户收藏", color = palette.text, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                Spacer(Modifier.height(10.dp))
                QTextField(source, "域名或 https:// 文本地址", palette, onValueChange = { source = it })
                Spacer(Modifier.height(10.dp))
                QButton("下载到用户收藏", palette, Modifier.fillMaxWidth(), primary = false) {
                    onDownloadUsers(source)
                }
            }
        }
    }
}

@Composable
private fun SettingsPage(
    palette: Palette,
    settings: AppSettings,
    rootController: RootTierController,
    rootAvailable: Boolean,
    rootChecking: Boolean,
    onSettings: (AppSettings) -> Unit,
    onRefreshRoot: () -> Unit,
    onImportCoreZip: () -> Unit,
    onExportDiagnostics: () -> Unit
) {
    var disclaimer by remember { mutableStateOf(false) }
    var logExpanded by remember { mutableStateOf(false) }
    var appLogRefreshing by remember { mutableStateOf(false) }
    var appLogText by remember { mutableStateOf(AppDiagnostics.recent(8_000)) }
    val scope = rememberCoroutineScope()
    val core = rootController.state.core

    LaunchedEffect(logExpanded) {
        if (logExpanded) {
            appLogText = withContext(Dispatchers.IO) { AppDiagnostics.recent(8_000) }
        }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(horizontal = 16.dp),
        contentPadding = PaddingValues(top = 14.dp, bottom = 26.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item { Header("设置", "运行模式与核心管理", palette) }
        item {
            QCard(palette) {
                Text("运行模式", color = palette.text, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ModeButton(
                        text = "VPN 模式 (FFI)",
                        selected = !settings.rootModeEnabled,
                        palette = palette,
                        modifier = Modifier.weight(1f),
                        onClick = { onSettings(settings.copy(rootModeEnabled = false)) }
                    )
                    ModeButton(
                        text = "Root 模式 (官方核心)",
                        selected = settings.rootModeEnabled,
                        palette = palette,
                        modifier = Modifier.weight(1f),
                        onClick = { onSettings(settings.copy(rootModeEnabled = true)) }
                    )
                }
                if (settings.rootModeEnabled) {
                    Spacer(Modifier.height(12.dp))
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        StatusDot(if (rootAvailable) palette.success else palette.error)
                        Text(
                            if (rootAvailable) "Root可用" else "未检测到可用 Root",
                            color = if (rootAvailable) palette.success else palette.error,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium
                        )
                        Spacer(Modifier.weight(1f))
                        QButton(
                            text = if (rootChecking) "检测中" else "重新检测",
                            palette = palette,
                            modifier = Modifier.width(104.dp),
                            compact = true,
                            primary = false,
                            icon = Icons.Rounded.Refresh,
                            iconRotation = SpinOnceRotation(rootChecking),
                            enabled = !rootChecking,
                            onClick = onRefreshRoot
                        )
                    }
                    if (!rootAvailable) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "请确认系统、厂商或 Root 管理器已授权 MoonTier，再重新检测。",
                            color = palette.subText,
                            fontSize = 12.sp,
                            lineHeight = 18.sp
                        )
                    }
                }
            }
        }
        item {
            QCard(palette) {
                SwitchRow("深色模式", settings.darkMode, palette) {
                    onSettings(settings.copy(darkMode = it))
                }
                SwitchRow("自动配置出口节点路由", settings.exitNodeAutoRoutes, palette) {
                    if (it) disclaimer = true else onSettings(settings.copy(exitNodeAutoRoutes = false))
                }
                if (settings.rootModeEnabled && rootAvailable) {
                    SwitchRow("开机自动恢复 Root 网络", settings.bootAutoStart, palette) {
                        onSettings(settings.copy(bootAutoStart = it))
                    }
                    SwitchRow("开机开启无线 ADB (5555)", settings.bootAdbEnabled, palette) {
                        onSettings(settings.copy(bootAdbEnabled = it))
                    }
                }
            }
        }
        item {
            QCard(palette) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) { logExpanded = !logExpanded },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("日志", color = palette.text, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                    Spacer(Modifier.weight(1f))
                    ExpandArrow(logExpanded, palette) { logExpanded = !logExpanded }
                }
                AnimatedVisibility(
                    visible = logExpanded,
                    enter = expandVertically(tween(220)) + fadeIn(tween(180)),
                    exit = shrinkVertically(tween(180)) + fadeOut(tween(130))
                ) {
                    Column {
                        Spacer(Modifier.height(12.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf(CoreLogLevel.OFF, CoreLogLevel.NORMAL, CoreLogLevel.DEBUG).forEach { level ->
                                ModeButton(
                                    text = CoreLogLevel.label(level),
                                    selected = CoreLogLevel.normalize(settings.coreLogLevel) == level,
                                    palette = palette,
                                    modifier = Modifier.weight(1f),
                                    onClick = { onSettings(settings.copy(coreLogLevel = level)) }
                                )
                            }
                        }
                        Spacer(Modifier.height(12.dp))
                        ActionBar(palette) {
                            QButton(
                                text = if (appLogRefreshing) "刷新中" else "刷新",
                                palette = palette,
                                modifier = Modifier.weight(1f),
                                compact = true,
                                primary = false,
                                enabled = !appLogRefreshing,
                                icon = Icons.Rounded.Refresh,
                                iconRotation = SpinOnceRotation(appLogRefreshing),
                                onClick = {
                                    scope.launch {
                                        appLogRefreshing = true
                                        try {
                                            appLogText = withContext(Dispatchers.IO) {
                                                AppDiagnostics.recent(8_000)
                                            }
                                            delay(80)
                                        } finally {
                                            appLogRefreshing = false
                                        }
                                    }
                                }
                            )
                            QButton(
                                text = "导出日志",
                                palette = palette,
                                modifier = Modifier.weight(1f),
                                compact = true,
                                icon = Icons.Rounded.Description,
                                onClick = onExportDiagnostics
                            )
                        }
                        Spacer(Modifier.height(12.dp))
                        Text(
                            if (appLogText.isBlank()) "暂无记录" else appLogText,
                            color = palette.subText,
                            fontSize = 11.sp,
                            lineHeight = 16.sp,
                            maxLines = 30,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
        if (settings.rootModeEnabled) {
            item {
                QCard(palette) {
                    Text("下载核心", color = palette.text, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                    Spacer(Modifier.height(10.dp))
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatusDot(if (core.ready) palette.success else palette.subText)
                    Text(
                        if (core.ready) "已安装 ${core.installedVersion}" else "尚未安装",
                        color = if (core.ready) palette.success else palette.subText,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium
                    )
                    }
                    if (core.latestVersion.isNotBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "最新官方版本: ${core.latestVersion}",
                        color = palette.subText,
                        fontSize = 12.sp,
                    )
                    }
                    if (core.installing) {
                    Spacer(Modifier.height(8.dp))
                    Text("正在下载 ${core.progress}%", color = palette.text, fontSize = 13.sp)
                    }
                    if (core.message.isNotBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Text(core.message, color = palette.subText, fontSize = 12.sp, lineHeight = 18.sp)
                    }
                    Spacer(Modifier.height(12.dp))
                    ActionBar(palette) {
                    QButton(
                        text = if (core.checking) "检查中" else "检查更新",
                        palette = palette,
                        modifier = Modifier.weight(1f),
                        compact = true,
                        primary = false,
                        enabled = !core.checking && !core.installing,
                        icon = Icons.Rounded.Refresh,
                        iconRotation = SpinOnceRotation(core.checking),
                        onClick = { rootController.checkCoreUpdate() }
                    )
                    QButton(
                        text = if (core.installing) "下载中" else "下载核心",
                        palette = palette,
                        modifier = Modifier.weight(1f),
                        compact = true,
                        enabled = !core.installing,
                        icon = Icons.Rounded.Download,
                        onClick = { rootController.installCore() }
                    )
                    QButton(
                        text = "导入 ZIP",
                        palette = palette,
                        modifier = Modifier.weight(1f),
                        compact = true,
                        primary = false,
                        enabled = !core.installing,
                        icon = Icons.Rounded.Description,
                        onClick = onImportCoreZip
                    )
                    }
                    Spacer(Modifier.height(8.dp))
                    SwitchRow("Root 模式自动检查更新", settings.coreAutoUpdate, palette) {
                        onSettings(settings.copy(coreAutoUpdate = it))
                    }
                    Text(
                        "Root 模式直接运行官方发布的 aarch64 easytier-core，可单独更新，不依赖 FFI。",
                        color = palette.subText,
                        fontSize = 12.sp,
                        lineHeight = 18.sp
                    )
                }
            }
        }
    }

    if (disclaimer) {
        AlertDialog(
            onDismissRequest = { disclaimer = false },
            confirmButton = {
                QButton("\u542f\u7528", palette, compact = true) {
                    onSettings(settings.copy(exitNodeAutoRoutes = true))
                    disclaimer = false
                }
            },
            dismissButton = {
                QButton("\u53d6\u6d88", palette, primary = false, compact = true) { disclaimer = false }
            },
            title = { Text("\u51fa\u53e3\u8282\u70b9\u8def\u7531", color = palette.text, fontWeight = FontWeight.SemiBold) },
            text = {
                Text(
                    "\u542f\u7528\u540e\u4f1a\u901a\u8fc7 EasyTier \u5411 Android VPN \u4e0b\u53d1\u9ed8\u8ba4 IPv4 \u8def\u7531\u3002\u8bf7\u5728\u786e\u8ba4\u51fa\u53e3\u8282\u70b9\u53ef\u4fe1\u4e14\u7b26\u5408\u9884\u671f\u540e\u518d\u5f00\u542f\u3002",
                    color = palette.subText,
                    lineHeight = 20.sp
                )
            },
            containerColor = palette.surface,
            shape = RoundedCornerShape(22.dp)
        )
    }
}

@Composable
private fun ModeButton(
    text: String,
    selected: Boolean,
    palette: Palette,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val background by animateColorAsState(
        targetValue = if (selected) palette.accent else palette.surfaceVariant,
        animationSpec = tween(220),
        label = "mode-background"
    )
    val textColor by animateColorAsState(
        targetValue = if (selected) palette.onAccent else palette.text,
        animationSpec = tween(180),
        label = "mode-text"
    )
    val borderWidth by animateDpAsState(
        targetValue = if (selected) 0.dp else 0.7.dp,
        animationSpec = tween(220),
        label = "mode-border"
    )
    val contentScale by animateFloatAsState(
        targetValue = if (selected) 1f else 0.97f,
        animationSpec = tween(220),
        label = "mode-scale"
    )
    Box(
        modifier = modifier
            .height(44.dp)
            .graphicsLayer { scaleX = contentScale; scaleY = contentScale }
            .clip(RoundedCornerShape(14.dp))
            .background(background)
            .border(
                BorderStroke(borderWidth, palette.border),
                RoundedCornerShape(14.dp)
            )
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text,
            color = textColor,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
private fun SpinOnceRotation(active: Boolean): Float {
    var turns by remember { mutableIntStateOf(0) }
    LaunchedEffect(active) {
        if (active) turns += 1
    }
    return animateFloatAsState(
        targetValue = turns * 360f,
        animationSpec = tween(600),
        label = "refresh-spin"
    ).value
}

@Composable
private fun ConfigEditorScreenV2(
    palette: Palette,
    initial: NetworkConfig,
    userServers: List<ServerEntry>,
    officialServers: List<ServerEntry>,
    onBack: () -> Unit,
    onSave: (NetworkConfig) -> Unit,
    onExport: (NetworkConfig) -> Unit
) {
    var cfg by remember(initial.id) { mutableStateOf(initial) }
    var rawTomlOpen by remember { mutableStateOf(false) }
    var rawTomlText by remember(initial.id) { mutableStateOf(TomlCodec.build(initial)) }
    var rawTomlDirty by remember(initial.id) { mutableStateOf(false) }
    var editorError by remember { mutableStateOf<Pair<String, String>?>(null) }
    var passwordVisible by remember { mutableStateOf(false) }
    var serverOpen by remember { mutableStateOf(false) }
    var protocolOpen by remember { mutableStateOf(false) }
    var p2pOpen by remember { mutableStateOf(false) }
    var perfOpen by remember { mutableStateOf(false) }
    var serviceOpen by remember { mutableStateOf(false) }
    var aclOpen by remember { mutableStateOf(false) }
    var serverPicker by remember { mutableStateOf<ServerPickerState?>(null) }

    LaunchedEffect(cfg, rawTomlDirty) {
        if (!rawTomlDirty) rawTomlText = TomlCodec.build(cfg)
    }

    fun parseRawTomlForEditor(): NetworkConfig? {
        val parsed = runCatching { TomlCodec.parse(rawTomlText) }.getOrElse {
            editorError = "\u539f\u59cb TOML \u89e3\u6790\u5931\u8d25" to (it.message ?: "\u65e0\u6cd5\u89e3\u6790\u5f53\u524d TOML\u3002")
            return null
        }
        return parsed.copy(id = cfg.id, isDefault = cfg.isDefault)
    }

    fun saveCurrentConfig() {
        val target = if (rawTomlDirty) parseRawTomlForEditor() else cfg
        if (target != null) {
            onSave(target)
            rawTomlDirty = false
        }
    }

    fun exportCurrentConfig() {
        val target = if (rawTomlDirty) parseRawTomlForEditor() else cfg
        if (target != null) {
            onExport(target)
        }
    }

    fun applyRawTomlToForm() {
        val parsed = parseRawTomlForEditor() ?: return
        cfg = parsed
        rawTomlDirty = false
    }

    BackHandler {
        onBack()
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(horizontal = 16.dp),
        contentPadding = PaddingValues(top = 14.dp, bottom = 180.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            QCard(palette) {
                Text("编辑配置", color = palette.text, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(12.dp))
                SmallLabel("配置名称", palette)
                QTextField(cfg.label, "配置名称", palette) { cfg = cfg.copy(label = it) }
                Spacer(Modifier.height(16.dp))
                ActionBar(palette) {
                    QButton("保存", palette, Modifier.weight(1f), compact = true, icon = Icons.Rounded.Check) { onSave(cfg) }
                    QButton("不保存", palette, Modifier.weight(1f), primary = false, compact = true) { onBack() }
                    QButton("导出", palette, Modifier.weight(1f), primary = false, compact = true) { onExport(cfg) }
                }
            }
        }

        item {
            QCard(palette) {
                SmallLabel("基础设置", palette)
                FieldBlock("主机名", palette) {
                    QTextField(cfg.hostname, "留空时使用系统设备名", palette) { cfg = cfg.copy(hostname = it) }
                }
                FieldBlock("网络名称", palette) {
                    QTextField(cfg.networkName, "请输入网络名称", palette) { cfg = cfg.copy(networkName = it) }
                }
                FieldBlock("密码", palette) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        QTextField(
                            cfg.networkSecret,
                            "请输入密码",
                            palette,
                            modifier = Modifier.weight(1f),
                            password = !passwordVisible
                        ) { cfg = cfg.copy(networkSecret = it) }
                        IconButton(onClick = { passwordVisible = !passwordVisible }) {
                            Icon(if (passwordVisible) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility, null, tint = palette.text)
                        }
                    }
                }
                FieldBlock("IP 设置", palette) {
                    SwitchRow("DHCP 自动分配", cfg.dhcp, palette) { cfg = cfg.copy(dhcp = it) }
                    Spacer(Modifier.height(8.dp))
                    QTextField(
                        cfg.ipv4,
                        "例如 10.126.126.2",
                        palette,
                        modifier = Modifier.graphicsLayer { alpha = if (cfg.dhcp) 0.42f else 1f },
                        onValueChange = { if (!cfg.dhcp) cfg = cfg.copy(ipv4 = it) }
                    )
                }
                SwitchRow("低延迟优先", cfg.latencyFirst, palette) { cfg = cfg.copy(latencyFirst = it) }
                SwitchRow("私有模式", cfg.enablePrivateMode, palette) { cfg = cfg.copy(enablePrivateMode = it) }
            }
        }

        item {
            ExpandableQCard("服务器", serverOpen, { serverOpen = it }, palette) {
                ListEditor(
                    title = "服务器列表",
                    values = cfg.peerUrls,
                    placeholder = "tcp://server.example.com:11010",
                    palette = palette
                ) {
                    cfg = cfg.copy(peerUrls = it)
                }
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    QButton("从收藏", palette, Modifier.weight(1f), primary = false, enabled = userServers.any { it.address.isNotBlank() }) {
                        serverPicker = ServerPickerState("用户收藏", userServers)
                    }
                }
            }
        }

        item {
            ExpandableQCard("传输协议", protocolOpen, { protocolOpen = it }, palette) {
                SwitchRow("启用 KCP 代理", cfg.enableKcpProxy, palette) { cfg = cfg.copy(enableKcpProxy = it) }
                SwitchRow("禁用 KCP 输入", cfg.disableKcpInput, palette) { cfg = cfg.copy(disableKcpInput = it) }
                SwitchRow("启用 QUIC 代理", cfg.enableQuicProxy, palette) { cfg = cfg.copy(enableQuicProxy = it) }
                SwitchRow("禁用 QUIC 输入", cfg.disableQuicInput, palette) { cfg = cfg.copy(disableQuicInput = it) }
                SwitchRow("禁止转发 KCP", cfg.disableRelayKcp, palette) { cfg = cfg.copy(disableRelayKcp = it) }
                SwitchRow("禁止转发 QUIC", cfg.disableRelayQuic, palette) { cfg = cfg.copy(disableRelayQuic = it) }
                SwitchRow("允许转发其他网络 KCP", cfg.enableRelayForeignNetworkKcp, palette) { cfg = cfg.copy(enableRelayForeignNetworkKcp = it) }
                SwitchRow("允许转发其他网络 QUIC", cfg.enableRelayForeignNetworkQuic, palette) { cfg = cfg.copy(enableRelayForeignNetworkQuic = it) }
                SwitchRow("启用加密", cfg.enableEncryption, palette) { cfg = cfg.copy(enableEncryption = it) }
                FieldBlock("默认连接协议", palette) {
                    QSelect(cfg.defaultProtocol, listOf("tcp", "udp", "ws", "wss", "wg", "quic"), palette) { cfg = cfg.copy(defaultProtocol = it) }
                }
                FieldBlock("默认加密协议", palette) {
                    QSelect(cfg.encryptionAlgorithm, listOf("aes-gcm", "chacha20-poly1305", "none"), palette) { cfg = cfg.copy(encryptionAlgorithm = it) }
                }
            }
        }

        item {
            ExpandableQCard("P2P 连接", p2pOpen, { p2pOpen = it }, palette) {
                SwitchRow("仅 P2P", cfg.p2pOnly, palette) { cfg = cfg.copy(p2pOnly = it) }
                SwitchRow("禁用 P2P", cfg.disableP2p, palette) { cfg = cfg.copy(disableP2p = it) }
                SwitchRow("需要 P2P", cfg.needP2p, palette) { cfg = cfg.copy(needP2p = it) }
                SwitchRow("按需 P2P", cfg.lazyP2p, palette) { cfg = cfg.copy(lazyP2p = it) }
                SwitchRow("禁用 UDP 打洞", cfg.disableUdpHolePunching, palette) { cfg = cfg.copy(disableUdpHolePunching = it) }
                SwitchRow("禁用 TCP 打洞", cfg.disableTcpHolePunching, palette) { cfg = cfg.copy(disableTcpHolePunching = it) }
                SwitchRow("禁用 UPnP", cfg.disableUpnp, palette) { cfg = cfg.copy(disableUpnp = it) }
                SwitchRow("禁用对称 NAT 打洞", cfg.disableSymHolePunching, palette) { cfg = cfg.copy(disableSymHolePunching = it) }
                SwitchRow("转发 RPC 包", cfg.relayAllPeerRpc, palette) { cfg = cfg.copy(relayAllPeerRpc = it) }
                SwitchRow("仅使用物理网卡", cfg.bindDevice, palette) { cfg = cfg.copy(bindDevice = it) }
            }
        }

        item {
            ExpandableQCard("性能与系统", perfOpen, { perfOpen = it }, palette) {
                SwitchRow("启用多线程", cfg.multiThread, palette) { cfg = cfg.copy(multiThread = it) }
                SwitchRow("使用用户态协议栈", cfg.useSmoltcp, palette) { cfg = cfg.copy(useSmoltcp = it) }
                SwitchRow("无 TUN 模式", cfg.noTun, palette) { cfg = cfg.copy(noTun = it) }
                SwitchRow("禁用 IPv6", cfg.disableIpv6, palette) { cfg = cfg.copy(disableIpv6 = it) }
                FieldBlock("MTU 值", palette) {
                    QTextField(if (cfg.mtu > 0) cfg.mtu.toString() else "", "最大 1380，留空使用默认", palette) {
                        val mtu = it.trim().toIntOrNull()?.coerceIn(0, 1380) ?: 0
                        cfg = cfg.copy(mtu = mtu)
                    }
                }
            }
        }

        item {
            ExpandableQCard("网络服务", serviceOpen, { serviceOpen = it }, palette) {
                SwitchRow("启用出口节点", cfg.enableExitNode, palette) { cfg = cfg.copy(enableExitNode = it) }
                SwitchRow("系统转发", cfg.proxyForwardBySystem, palette) { cfg = cfg.copy(proxyForwardBySystem = it) }
                SwitchRow("启用魔法 DNS", cfg.enableMagicDns, palette) { cfg = cfg.copy(enableMagicDns = it) }
                SwitchRow("启用网络白名单", cfg.enableRelayNetworkWhitelist, palette) { cfg = cfg.copy(enableRelayNetworkWhitelist = it) }
                AnimatedVisibility(cfg.enableRelayNetworkWhitelist, enter = fadeIn(tween(160)), exit = fadeOut(tween(120))) {
                    Column(Modifier.padding(top = 8.dp)) {
                        ListEditor("网络白名单", cfg.relayNetworkWhitelist, "网络名称", palette) {
                            cfg = cfg.copy(relayNetworkWhitelist = it)
                        }
                    }
                }
                ListEditor("监听地址", cfg.listenerUrls, "tcp://0.0.0.0:11010", palette) {
                    cfg = cfg.copy(listenerUrls = it)
                }
                ListEditor("子网代理 CIDR", cfg.proxyCidrs, "192.168.1.0/24", palette) {
                    cfg = cfg.copy(proxyCidrs = it)
                }
                Text("自定义进入 VPN 的路由，将禁用子网代理等自动传播的路由", color = palette.subText, fontSize = 12.sp)
                ListEditor("自定义路由规则", cfg.routes, "10.0.0.0/8", palette) {
                    cfg = cfg.copy(routes = it, enableManualRoutes = it.isNotEmpty())
                }
                ListEditor("出口节点列表", cfg.exitNodes, "10.126.126.1", palette) {
                    cfg = cfg.copy(exitNodes = it)
                }
            }
        }
        item {
            ExpandableQCard("ACL", aclOpen, { aclOpen = it }, palette) {
                Text(
                    "\u8fd9\u91cc\u76f4\u63a5\u7f16\u8f91 ACL \u7684\u539f\u59cb TOML\uff0c\u4fdd\u5b58\u65f6\u4f1a\u539f\u6837\u9644\u52a0\u5230\u914d\u7f6e\u540e\u9762\uff0c\u4ee5\u4fbf\u540e\u7eed\u8ddf\u8fdb\u5b98\u65b9 ACL \u5b57\u6bb5\u53d8\u66f4\u3002",
                    color = palette.subText,
                    fontSize = 12.sp,
                    lineHeight = 18.sp
                )
                QTextField(
                    value = cfg.aclToml,
                    placeholder = "[acl]\ndefault_action = \"accept\"",
                    palette = palette,
                    modifier = Modifier.heightIn(min = 160.dp),
                    singleLine = false,
                    onValueChange = { cfg = cfg.copy(aclToml = it) }
                )
            }
        }
        item {
            ExpandableQCard("\u539f\u59cb TOML", rawTomlOpen, { rawTomlOpen = it }, palette) {
                Text(
                    "\u53ef\u4ee5\u5728\u8fd9\u91cc\u76f4\u63a5\u7f16\u8f91\u6574\u4efd EasyTier TOML\u3002\u4fee\u6539\u540e\u5148\u70b9\u201c\u5e94\u7528\u5230\u8868\u5355\u201d\uff0c\u518d\u70b9\u4e0a\u65b9\u7684\u201c\u4fdd\u5b58\u201d\u3002",
                    color = palette.subText,
                    fontSize = 12.sp,
                    lineHeight = 18.sp
                )
                QTextField(
                    value = rawTomlText,
                    placeholder = "instance_id = \"...\"",
                    palette = palette,
                    modifier = Modifier.heightIn(min = 220.dp),
                    singleLine = false,
                    onValueChange = {
                        rawTomlText = it
                        rawTomlDirty = true
                    }
                )
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    QButton("\u5e94\u7528\u5230\u8868\u5355", palette, Modifier.weight(1f), primary = false, compact = true) {
                        applyRawTomlToForm()
                    }
                    QButton("\u7528\u8868\u5355\u8986\u76d6", palette, Modifier.weight(1f), primary = false, compact = true) {
                        rawTomlText = TomlCodec.build(cfg)
                        rawTomlDirty = false
                    }
                }
            }
        }
    }

    serverPicker?.let { picker ->
        ServerPickerDialog(
            palette = palette,
            state = picker,
            currentAddresses = cfg.peerUrls,
            onDismiss = { serverPicker = null },
            onConfirm = { selected ->
                cfg = cfg.copy(peerUrls = (cfg.peerUrls + selected.map { it.address }).cleanItems())
                serverPicker = null
            }
        )
    }

    editorError?.let { (title, body) ->
        AlertDialog(
            onDismissRequest = { editorError = null },
            confirmButton = {
                QButton("\u786e\u5b9a", palette, compact = true) { editorError = null }
            },
            title = { Text(title, color = palette.text, fontWeight = FontWeight.SemiBold) },
            text = { Text(body, color = palette.subText, lineHeight = 20.sp) },
            containerColor = palette.surface,
            shape = RoundedCornerShape(22.dp)
        )
    }
}

@Composable
private fun ExpandableQCard(
    title: String,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    palette: Palette,
    content: @Composable ColumnScope.() -> Unit
) {
    QCard(palette) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(32.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(title, color = palette.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            ExpandArrow(expanded, palette) { onExpandedChange(!expanded) }
        }
        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically(animationSpec = tween(240)) + fadeIn(tween(170)),
            exit = shrinkVertically(animationSpec = tween(180)) + fadeOut(tween(120))
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                content = content
            )
        }
    }
}

@Composable
private fun ExpandArrow(
    expanded: Boolean,
    palette: Palette,
    onClick: () -> Unit
) {
    val arrowRotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = tween(180),
        label = "expand-arrow"
    )
    Box(
        modifier = Modifier
            .size(32.dp)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            Icons.Rounded.KeyboardArrowDown,
            null,
            tint = palette.text,
            modifier = Modifier
                .size(22.dp)
                .graphicsLayer { rotationZ = arrowRotation }
        )
    }
}

@Composable
private fun ActionBar(palette: Palette, content: @Composable RowScope.() -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp)
            .clip(RoundedCornerShape(22.dp))
            .background(palette.surfaceVariant)
            .border(BorderStroke(0.8.dp, palette.border), RoundedCornerShape(22.dp))
            .padding(4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        content = content
    )
}

@Composable
private fun SmallLabel(text: String, palette: Palette) {
    Text(
        text,
        color = palette.text.copy(alpha = 0.58f),
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(bottom = 6.dp)
    )
}

@Composable
private fun FieldBlock(label: String, palette: Palette, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(label, color = palette.text, fontSize = 14.sp)
        content()
    }
}

@Composable
private fun QSelect(
    value: String,
    options: List<String>,
    palette: Palette,
    onValue: (String) -> Unit
) {
    var open by remember { mutableStateOf(false) }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(44.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(palette.surfaceVariant)
            .border(BorderStroke(0.7.dp, palette.border), RoundedCornerShape(14.dp))
            .clickable { open = true }
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.CenterStart
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(value.ifBlank { options.firstOrNull().orEmpty() }, color = palette.text, fontSize = 14.sp, modifier = Modifier.weight(1f))
            Icon(Icons.Rounded.KeyboardArrowDown, null, tint = palette.subText)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option) },
                    onClick = {
                        onValue(option)
                        open = false
                    }
                )
            }
        }
    }
}

@Composable
private fun BottomNav(palette: Palette, items: List<NavItem>, selected: Int, onSelect: (Int) -> Unit) {
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .height(84.dp)
            .navigationBarsPadding()
            .background(palette.background)
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(0.7.dp)
                .background(palette.border.copy(alpha = 0.7f))
                .align(Alignment.TopCenter)
        )
        val slot = maxWidth / items.size
        val pillX by animateDpAsState(
            targetValue = slot * selected + (slot - 64.dp) / 2,
            animationSpec = tween(220),
            label = "nav-pill"
        )
        Box(
            modifier = Modifier
                .padding(top = 8.dp)
                .graphicsLayer { translationX = pillX.toPx() }
                .size(width = 64.dp, height = 34.dp)
                .clip(CircleShape)
                .background(palette.accent)
        )
        Row(Modifier.fillMaxSize()) {
            items.forEachIndexed { index, item ->
                val active = selected == index
                val source = remember { MutableInteractionSource() }
                val pressed by source.collectIsPressedAsState()
                val scale by animateFloatAsState(if (pressed) 0.96f else 1f, tween(110), label = "nav-scale")
                Column(
                    modifier = Modifier
                        .width(slot)
                        .fillMaxHeight()
                        .graphicsLayer { scaleX = scale; scaleY = scale }
                        .clickable(source, indication = null) { onSelect(index) }
                        .padding(top = 14.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(item.icon, null, tint = if (active) palette.onAccent else palette.subText, modifier = Modifier.size(22.dp))
                    Spacer(Modifier.height(12.dp))
                    Text(
                        item.label,
                        color = if (active) palette.text else palette.subText,
                        fontSize = 12.sp,
                        fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                        maxLines = 1
                    )
                }
            }
        }
    }
}

@Composable
private fun Header(title: String, subtitle: String, palette: Palette, modifier: Modifier = Modifier) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Text(title, color = palette.text, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        if (subtitle.isNotBlank()) Text(subtitle, color = palette.subText, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun AppleToast(
    title: String,
    body: String,
    palette: Palette,
    onDismiss: () -> Unit
) {
    var visible by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        visible = true
        delay(2800)
        visible = false
        delay(320)
        onDismiss()
    }

    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.TopCenter
    ) {
        AnimatedVisibility(
            visible = visible,
            enter = slideInVertically(
                initialOffsetY = { -it },
                animationSpec = tween(320, delayMillis = 0)
            ) + fadeIn(tween(240)),
            exit = slideOutVertically(
                targetOffsetY = { -it },
                animationSpec = tween(260)
            ) + fadeOut(tween(200))
        ) {
            Box(
                modifier = Modifier
                    .padding(top = 56.dp)
                    .padding(horizontal = 24.dp)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(20.dp))
                    .background(palette.surface.copy(alpha = 0.97f))
                    .border(BorderStroke(0.5.dp, palette.border), RoundedCornerShape(20.dp))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = { visible = false }
                    )
                    .padding(horizontal = 20.dp, vertical = 14.dp)
            ) {
                Column {
                    Text(title, color = palette.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    if (body.isNotBlank()) {
                        Text(body, color = palette.subText, fontSize = 13.sp, lineHeight = 18.sp, modifier = Modifier.padding(top = 4.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun QCard(palette: Palette, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(palette.surface)
            .border(BorderStroke(0.7.dp, palette.border), RoundedCornerShape(14.dp))
            .padding(16.dp),
        content = content
    )
}

@Composable
private fun QButton(
    text: String,
    palette: Palette,
    modifier: Modifier = Modifier,
    primary: Boolean = true,
    danger: Boolean = false,
    dangerText: Boolean = false,
    compact: Boolean = false,
    enabled: Boolean = true,
    icon: ImageVector? = null,
    iconRotation: Float = 0f,
    onClick: () -> Unit
) {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.982f else 1f, tween(95), label = "button-scale")
    val bg = when {
        !enabled -> palette.text.copy(alpha = 0.06f)
        danger -> palette.error
        primary -> palette.accent
        else -> palette.secondaryButton
    }
    val fg = when {
        dangerText -> palette.error
        primary || danger -> palette.onAccent
        else -> palette.text
    }
    Box(
        modifier = modifier
            .height(if (compact) 38.dp else 46.dp)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(RoundedCornerShape(16.dp))
            .background(bg)
            .then(if (dangerText) Modifier.border(BorderStroke(0.8.dp, if (enabled) palette.error else palette.border.copy(alpha = 0.35f)), RoundedCornerShape(16.dp)) else Modifier)
            .clickable(source, indication = null, enabled = enabled) { onClick() },
        contentAlignment = Alignment.Center
    ) {
        val overlayAlpha by animateFloatAsState(if (pressed) 1f else 0f, tween(if (pressed) 42 else 125), label = "button-overlay")
        Box(
            Modifier
                .fillMaxSize()
                .background(if (primary || danger) Color.White.copy(alpha = 0.15f * overlayAlpha) else palette.overlay.copy(alpha = overlayAlpha))
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
            if (icon != null) {
                Icon(
                    icon,
                    null,
                    tint = if (enabled) fg else palette.subText,
                    modifier = Modifier
                        .size(18.dp)
                        .graphicsLayer { rotationZ = iconRotation }
                )
                Spacer(Modifier.width(6.dp))
            }
            Text(text, color = if (enabled) fg else palette.subText, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
        }
    }
}

@Composable
private fun QTextField(
    value: String,
    placeholder: String,
    palette: Palette,
    modifier: Modifier = Modifier,
    password: Boolean = false,
    singleLine: Boolean = true,
    onValueChange: (String) -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    Box(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = if (singleLine) 44.dp else 88.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(palette.surfaceVariant)
            .border(BorderStroke(if (focused) 1.2.dp else 0.7.dp, if (focused) palette.accent.copy(alpha = 0.82f) else palette.border), RoundedCornerShape(14.dp))
            .padding(horizontal = 16.dp, vertical = 9.dp),
        contentAlignment = Alignment.CenterStart
    ) {
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = singleLine,
            visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
            textStyle = TextStyle(color = palette.text, fontSize = 14.sp),
            modifier = Modifier
                .fillMaxWidth()
                .onFocusChanged { focused = it.isFocused }
        )
        val hintAlpha by animateFloatAsState(if (value.isEmpty() && !focused) 1f else 0f, tween(140), label = "hint")
        if (hintAlpha > 0.01f) {
            Text(
                placeholder,
                color = palette.subText.copy(alpha = 0.8f * hintAlpha),
                fontSize = 14.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun SwitchRow(text: String, checked: Boolean, palette: Palette, onChecked: (Boolean) -> Unit) {
    val thumbOffset by animateDpAsState(if (checked) 20.dp else 0.dp, tween(180), label = "switch-thumb")
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.96f else 1f, tween(130), label = "switch-scale")
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text, color = palette.text, fontSize = 14.sp, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        Box(
            modifier = Modifier
                .size(width = 48.dp, height = 28.dp)
                .graphicsLayer { scaleX = scale; scaleY = scale }
                .clip(CircleShape)
                .background(if (checked) palette.accent else palette.secondaryButton)
                .border(BorderStroke(if (checked) 0.dp else 0.7.dp, palette.border), CircleShape)
                .clickable(source, indication = null) { onChecked(!checked) },
            contentAlignment = Alignment.CenterStart
        ) {
            Box(
                modifier = Modifier
                    .padding(start = 4.dp)
                    .graphicsLayer { translationX = thumbOffset.toPx() }
                    .size(20.dp)
                    .clip(CircleShape)
                    .background(palette.onAccent)
                    .border(BorderStroke(0.7.dp, palette.border), CircleShape)
            )
        }
    }
}

@Composable
private fun ServerPickerDialog(
    palette: Palette,
    state: ServerPickerState,
    currentAddresses: List<String>,
    onDismiss: () -> Unit,
    onConfirm: (List<ServerEntry>) -> Unit
) {
    val servers = remember(state) {
        state.servers
            .filter { it.address.isNotBlank() }
            .distinctBy { it.address.lowercase() }
    }
    val existing = remember(currentAddresses) { currentAddresses.map { it.lowercase() }.toHashSet() }
    var selected by remember(state) { mutableStateOf(emptySet<String>()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(state.title, color = palette.text, fontWeight = FontWeight.Bold, fontSize = 20.sp)
        },
        text = {
            Column(
                Modifier
                    .fillMaxWidth()
                    .animateContentSize(tween(180))
            ) {
                if (servers.isEmpty()) {
                    Text("暂无可选服务器", color = palette.subText, fontSize = 14.sp)
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 360.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(servers, key = { it.address }) { server ->
                            val addressKey = server.address.lowercase()
                            val checked = addressKey in selected
                            val alreadyAdded = addressKey in existing
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(13.dp))
                                    .background(palette.surfaceVariant)
                                    .clickable(enabled = !alreadyAdded) {
                                        selected = if (checked) selected - addressKey else selected + addressKey
                                    }
                                    .padding(horizontal = 8.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Checkbox(
                                    checked = checked || alreadyAdded,
                                    enabled = !alreadyAdded,
                                    onCheckedChange = {
                                        selected = if (it) selected + addressKey else selected - addressKey
                                    }
                                )
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        serverNameFromAddress(server.address),
                                        color = if (alreadyAdded) palette.subText else palette.text,
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    if (alreadyAdded) {
                                        Text("已在当前配置中", color = palette.subText, fontSize = 11.sp)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            QButton(
                "添加选中",
                palette,
                Modifier.width(112.dp),
                compact = true,
                enabled = selected.isNotEmpty()
            ) {
                onConfirm(servers.filter { it.address.lowercase() in selected })
            }
        },
        dismissButton = {
            QButton("取消", palette, Modifier.width(82.dp), primary = false, compact = true, onClick = onDismiss)
        },
        containerColor = palette.surface,
        shape = RoundedCornerShape(22.dp)
    )
}

@Composable
private fun ListEditor(
    title: String,
    values: List<String>,
    placeholder: String,
    palette: Palette,
    quickAdd: List<String> = emptyList(),
    quickAddLabel: String? = null,
    secondaryQuickAdd: List<String> = emptyList(),
    secondaryQuickAddLabel: String? = null,
    onValues: (List<String>) -> Unit
) {
    var local by remember(values) { mutableStateOf(values.ifEmpty { emptyList() }) }
    val quickActions = buildList {
        if (quickAddLabel != null || quickAdd.isNotEmpty()) add((quickAddLabel ?: "收藏") to quickAdd)
        if (secondaryQuickAddLabel != null || secondaryQuickAdd.isNotEmpty()) add((secondaryQuickAddLabel ?: "官方") to secondaryQuickAdd)
    }
    Column(
        Modifier
            .fillMaxWidth()
            .animateContentSize(tween(220))
    ) {
        FormLabel(title, palette)
        local.forEachIndexed { index, value ->
            Row(
                modifier = Modifier.padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                QTextField(
                    value = value,
                    placeholder = placeholder,
                    palette = palette,
                    modifier = Modifier.weight(1f),
                    onValueChange = {
                        local = local.replaceAt(index, it)
                        onValues(local.cleanItems())
                    }
                )
                IconButton(onClick = {
                    local = local.filterIndexed { i, _ -> i != index }
                    onValues(local.cleanItems())
                }) {
                    Icon(Icons.Rounded.Delete, null, tint = palette.error)
                }
            }
        }
        Row(
            Modifier
                .fillMaxWidth()
                .padding(top = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            QButton("添加", palette, Modifier.weight(1f), primary = false, icon = Icons.Rounded.Add) {
                local = local + ""
                onValues(local.cleanItems())
            }
            quickActions.forEach { (label, items) ->
                QButton(label, palette, Modifier.weight(1f), primary = false, enabled = items.isNotEmpty()) {
                    local = (local + items).cleanItems()
                    onValues(local)
                }
            }
        }
    }
}

@Composable
private fun DefaultPill(palette: Palette, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .height(24.dp)
            .clip(CircleShape)
            .border(BorderStroke(0.8.dp, if (selected) palette.accent.copy(alpha = 0.26f) else palette.border), CircleShape)
            .background(if (selected) palette.accent.copy(alpha = 0.10f) else Color.Transparent)
            .clickable(enabled = !selected) { onClick() }
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        Text("默认", color = if (selected) palette.accent else palette.subText, fontSize = 11.sp)
    }
}

@Composable
private fun StatusDot(color: Color, size: Dp = 10.dp) {
    Box(
        Modifier
            .size(size)
            .clip(CircleShape)
            .background(color)
    )
}

@Composable
private fun FormLabel(text: String, palette: Palette) {
    Text(text, color = palette.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(bottom = 10.dp))
}

private fun <T> List<T>.replaceAt(index: Int, value: T): List<T> =
    mapIndexed { i, old -> if (i == index) value else old }

private fun Color.toArgbInt(): Int =
    android.graphics.Color.argb((alpha * 255).toInt(), (red * 255).toInt(), (green * 255).toInt(), (blue * 255).toInt())
