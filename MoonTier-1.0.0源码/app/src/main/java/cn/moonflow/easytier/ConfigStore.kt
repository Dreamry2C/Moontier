package cn.moonflow.easytier

import android.content.Context
import android.util.AtomicFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URLEncoder
import java.net.URL
import java.util.UUID
import java.util.regex.Pattern

class ConfigStore(private val context: Context) {
    private val configsFile = File(context.filesDir, "network_configs.json")
    private val userServersFile = File(context.filesDir, "servers.json")
    private val officialServersFile = File(context.filesDir, "official_servers.json")
    private val settingsFile = File(context.filesDir, "settings.json")

    init {
        // Remove the legacy built-in official server cache on every startup.
        officialServersFile.delete()
    }

    @Synchronized
    fun loadConfigs(): List<NetworkConfig> {
        val raw = readJsonArray(configsFile)
        val configs = buildList {
            if (raw != null) {
                for (i in 0 until raw.length()) {
                    raw.optJSONObject(i)?.let { add(NetworkConfig.fromJson(it)) }
                }
            }
        }
        val normalized = normalizeConfigs(configs)
        if (normalized != configs) saveConfigs(normalized)
        return normalized
    }

    @Synchronized
    fun saveConfigs(configs: List<NetworkConfig>) {
        val normalized = normalizeConfigs(configs)
        val array = JSONArray()
        normalized.forEach { array.put(it.toJson()) }
        writeTextAtomic(configsFile, array.toString(2))
    }

    @Synchronized
    fun saveConfig(config: NetworkConfig) {
        val configId = config.id.ifBlank { UUID.randomUUID().toString() }
        val current = loadConfigs().toMutableList()
        val index = current.indexOfFirst { it.id == configId }
        val merged = if (index >= 0) {
            val existing = current[index]
            config.copy(id = configId, isRunning = config.isRunning || existing.isRunning)
        } else {
            config.copy(id = configId)
        }

        if (index >= 0) {
            current[index] = merged
        } else {
            current += merged
        }
        saveConfigs(current)
    }

    @Synchronized
    fun deleteConfig(id: String) {
        val filtered = loadConfigs().filterNot { it.id == id }
        saveConfigs(filtered)
    }

    @Synchronized
    fun setDefaultConfig(id: String) {
        saveConfigs(loadConfigs().map { it.copy(isDefault = it.id == id) })
    }

    @Synchronized
    fun updateConfigRunningState(id: String, running: Boolean) {
        if (id.isBlank()) return
        saveConfigs(
            loadConfigs().map {
                when {
                    running -> it.copy(isRunning = it.id == id)
                    it.id == id -> it.copy(isRunning = false)
                    else -> it
                }
            }
        )
    }

    fun loadDefaultConfig(): NetworkConfig? =
        loadConfigs().firstOrNull { it.isDefault } ?: loadConfigs().firstOrNull()

    @Synchronized
    fun loadSettings(): AppSettings {
        val obj = readJsonObject(settingsFile) ?: return AppSettings()
        return AppSettings.fromJson(obj)
    }

    @Synchronized
    fun saveSettings(settings: AppSettings) {
        val merged = loadSettings().copy(
            autoSyncOfficialServers = settings.autoSyncOfficialServers,
            exitNodeAutoRoutes = settings.exitNodeAutoRoutes,
            darkMode = settings.darkMode,
            rootModeEnabled = settings.rootModeEnabled,
            coreAutoUpdate = settings.coreAutoUpdate,
            coreDownloadProxyEnabled = settings.coreDownloadProxyEnabled,
            coreDownloadProxies = settings.coreDownloadProxies,
            coreLogLevel = settings.coreLogLevel,
            configServerUrl = settings.configServerUrl,
            configServerHostname = settings.configServerHostname,
            configServerMachineId = settings.configServerMachineId,
            configServerSecureMode = settings.configServerSecureMode,
            configServerAutoConnect = settings.configServerAutoConnect,
            bootAutoStart = settings.bootAutoStart,
            bootAdbEnabled = settings.bootAdbEnabled,
            keepAliveNotification = settings.keepAliveNotification
        )
        writeTextAtomic(settingsFile, merged.toJson().toString(2))
    }

    @Synchronized
    fun loadUserServers(): List<ServerEntry> = loadServerFile(userServersFile)

    @Synchronized
    fun saveUserServers(servers: List<ServerEntry>) = saveServerFile(userServersFile, servers)

    @Synchronized
    fun loadOfficialServers(): List<ServerEntry> {
        officialServersFile.delete()
        return emptyList()
    }

    @Synchronized
    fun saveOfficialServers(servers: List<ServerEntry>) {
        officialServersFile.delete()
    }

    suspend fun syncOfficialServers(source: String = ""): SyncResult =
        withContext(Dispatchers.IO) {
            officialServersFile.delete()
            SyncResult(false, 0, "官方服务器已移除，请使用用户收藏或自定义 TXT 源")
        }

    suspend fun downloadUserServers(source: String): SyncResult =
        withContext(Dispatchers.IO) {
            val allAddresses = LinkedHashSet<String>()
            val visited = HashSet<String>()
            fetchServerSourcesRecursive(source, allAddresses, visited, depth = 0)
            val current = loadUserServers()
            val existing = current.map { it.address.lowercase() }.toHashSet()
            val usedNames = current.map { it.name }.toMutableList()
            val added = allAddresses
                .filter { existing.add(it.lowercase()) }
                .map {
                    val name = nextServerNameFromNames(usedNames, serverNameFromAddress(it))
                    usedNames += name
                    ServerEntry(name, it)
                }
            saveUserServers(current + added)
            SyncResult(
                true,
                added.size,
                if (added.isEmpty()) "没有发现新的用户收藏服务器" else "已添加 ${added.size} 个服务器"
            )
        }

    private fun fetchServerSourcesRecursive(
        source: String,
        out: LinkedHashSet<String>,
        visited: HashSet<String>,
        depth: Int
    ) {
        if (depth >= 5) return
        val key = source.trim().lowercase()
        if (!visited.add(key)) return

        val text = runCatching { fetchServerText(source) }.getOrNull() ?: return
        out += parseServerAddresses(text)

        for (nextSource in extractRecursiveSources(text)) {
            fetchServerSourcesRecursive(nextSource, out, visited, depth + 1)
        }
    }

    private fun extractRecursiveSources(text: String): List<String> {
        val out = ArrayList<String>()
        val domainPattern = Pattern.compile(
            "(?i)(?:^|\\s)([a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\\.)+[a-z]{2,}(?:\\.[a-z]{2,})?(?=\\s|$|,|;)",
            Pattern.MULTILINE
        )
        val matcher = domainPattern.matcher(text)
        while (matcher.find()) {
            val domain = matcher.group()?.trim().orEmpty()
            if (domain.isNotBlank() && !domain.startsWith("http") && !domain.contains("://")) {
                out += domain
            }
        }
        return out
    }

    private fun normalizeConfigs(configs: List<NetworkConfig>): List<NetworkConfig> {
        val source = if (configs.isEmpty()) listOf(NetworkConfig.defaultConfig()) else configs
        val firstDefaultId = source.firstOrNull { it.isDefault }?.id ?: source.first().id
        val firstRunningId = source.firstOrNull { it.isRunning }?.id
        val usedInstances = HashSet<String>()

        return source.mapIndexed { index, config ->
            val label = config.label.trim().ifBlank {
                config.networkName.trim().ifBlank { "配置 ${index + 1}" }
            }
            val baseInstance = config.instanceName.trim().ifBlank {
                "MoonTier-${config.id.take(8)}"
            }
            var instance = baseInstance
            var suffix = 1
            while (!usedInstances.add(instance.lowercase())) {
                instance = "$baseInstance-$suffix"
                suffix += 1
            }
            config.copy(
                label = label,
                instanceName = instance,
                isDefault = config.id == firstDefaultId,
                isRunning = firstRunningId != null && config.id == firstRunningId
            )
        }
    }

    private fun loadServerFile(file: File): List<ServerEntry> {
        val array = readJsonArray(file) ?: return emptyList()
        val out = ArrayList<ServerEntry>()
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            val server = ServerEntry.fromJson(item)
            if (server.address.isNotBlank()) out += server
        }
        return out.distinctBy { it.address.lowercase() }
    }

    private fun saveServerFile(file: File, servers: List<ServerEntry>) {
        val array = JSONArray()
        servers
            .filter { it.address.isNotBlank() }
            .distinctBy { it.address.lowercase() }
            .forEach { array.put(it.toJson()) }
        writeTextAtomic(file, array.toString(2))
    }

    private fun readJsonArray(file: File): JSONArray? =
        runCatching { JSONArray(readTextAtomic(file)) }.getOrNull()

    private fun readJsonObject(file: File): JSONObject? =
        runCatching { JSONObject(readTextAtomic(file)) }.getOrNull()

    private fun readTextAtomic(file: File): String {
        if (!file.exists()) return ""
        return String(AtomicFile(file).readFully(), Charsets.UTF_8)
    }

    private fun writeTextAtomic(file: File, text: String) {
        file.parentFile?.mkdirs()
        val atomicFile = AtomicFile(file)
        val stream = atomicFile.startWrite()
        try {
            stream.write(text.toByteArray(Charsets.UTF_8))
            atomicFile.finishWrite(stream)
        } catch (throwable: Throwable) {
            atomicFile.failWrite(stream)
            throw throwable
        }
    }

    private fun parseServerAddresses(text: String): List<String> {
        val pattern = Pattern.compile("(?i)((?:tcp|udp|ws|wss|wg|quic)://[^\\s,'\"<>]+)")
        val matcher = pattern.matcher(text)
        val out = ArrayList<String>()
        while (matcher.find()) {
            val cleaned = matcher.group(1)
                ?.trim()
                ?.trimEnd('.', ',', ';', ')', ']', '}')
                .orEmpty()
            if (cleaned.isNotBlank()) out += cleaned
        }
        return out.cleanItems()
    }

    private fun fetchServerText(source: String): String {
        val trimmed = source.trim()
        require(trimmed.isNotEmpty()) { "请输入 TXT 记录域名或 URL" }

        if (trimmed.startsWith("http://")) {
            throw IllegalArgumentException("服务器列表 URL 必须使用 HTTPS")
        }

        if (trimmed.startsWith("https://")) {
            val body = httpGet(trimmed, accept = "text/plain,*/*")
            if (parseServerAddresses(body).isNotEmpty()) return body
        } else {
            val dnsBody = fetchTxtByDnsJsonProviders(trimmed)
            if (parseServerAddresses(dnsBody).isNotEmpty()) return dnsBody
        }

        throw IllegalStateException("没有从 $source 读取到服务器地址")
    }

    private fun fetchTxtByDnsJsonProviders(domain: String): String {
        val encoded = URLEncoder.encode(domain.trim().trimEnd('.'), "UTF-8")
        val providers = listOf(
            "https://dns.alidns.com/resolve?name=$encoded&type=TXT",
            "https://doh.pub/dns-query?name=$encoded&type=TXT",
            "https://cloudflare-dns.com/dns-query?name=$encoded&type=TXT"
        )
        val errors = ArrayList<String>()
        for (provider in providers) {
            val response = runCatching { httpGet(provider, accept = "application/dns-json") }
                .onFailure { errors += "${URL(provider).host}: ${it.message ?: it.javaClass.simpleName}" }
                .getOrNull()
                ?: continue
            val text = parseDnsJsonTxtResponse(response)
            if (parseServerAddresses(text).isNotEmpty()) return text
            errors += "${URL(provider).host}: TXT 记录为空"
        }
        throw IllegalStateException("TXT 解析失败：${errors.joinToString("；")}")
    }

    private fun parseDnsJsonTxtResponse(response: String): String {
        val answer = JSONObject(response).optJSONArray("Answer") ?: JSONArray()
        val parts = ArrayList<String>()
        for (i in 0 until answer.length()) {
            val data = answer.optJSONObject(i)?.optString("data").orEmpty()
            if (data.isNotBlank()) parts += decodeDnsTxtData(data)
        }
        return parts.joinToString("\n")
    }

    private fun decodeDnsTxtData(raw: String): String {
        val text = raw.trim().trim('"')
        val out = StringBuilder()
        var index = 0
        while (index < text.length) {
            if (text[index] == '\\' && index + 3 < text.length) {
                val code = text.substring(index + 1, index + 4).toIntOrNull()
                if (code != null) {
                    out.append(code.toChar())
                    index += 4
                    continue
                }
            }
            if (text[index] == '"' && index + 2 < text.length && text[index + 1] == ' ' && text[index + 2] == '"') {
                out.append('\n')
                index += 3
                continue
            }
            out.append(text[index])
            index += 1
        }
        return out.toString()
    }

    private fun httpGet(target: String, accept: String): String {
        val connection = URL(target).openConnection() as HttpURLConnection
        connection.connectTimeout = 6000
        connection.readTimeout = 8000
        connection.requestMethod = "GET"
        connection.setRequestProperty("Accept", accept)
        connection.instanceFollowRedirects = true
        return connection.inputStream.bufferedReader().use { it.readText() }
    }

    fun resolveHostIps(host: String): List<String> = runCatching {
        InetAddress.getAllByName(host)
            .mapNotNull { it.hostAddress }
            .distinct()
    }.getOrDefault(emptyList())
}

data class SyncResult(
    val success: Boolean,
    val count: Int,
    val message: String
)
