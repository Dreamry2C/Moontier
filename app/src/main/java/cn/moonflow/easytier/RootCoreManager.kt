package cn.moonflow.easytier

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipFile

class RootCoreManager(private val context: Context) {
    private val rootDir = File(context.filesDir, "root")
    val coreDir = File(rootDir, "core")
    val coreFile = File(coreDir, "easytier-core")
    val cliFile = File(coreDir, "easytier-cli")
    val managerClientFile = File(coreDir, "moontier-root-manager")
    private val versionFile = File(coreDir, "version.txt")
    private val managerClientVersionFile = File(coreDir, "manager-client.version")

    fun ensureDirectories() {
        coreDir.mkdirs()
        installBundledManagerClient()
    }

    fun isReady(): Boolean = coreFile.isFile && cliFile.isFile && managerClientFile.isFile

    fun installedVersion(): String =
        runCatching { versionFile.readText().trim() }.getOrDefault("")

    fun checkLatest(useProxies: Boolean, proxies: List<String>): String? {
        val original = "https://api.github.com/repos/EasyTier/EasyTier/releases/latest"
        val errors = ArrayList<String>()
        for (url in candidateUrls(original, useProxies, proxies)) {
            try {
                val connection = URL(url).openConnection() as HttpURLConnection
                connection.connectTimeout = 8000
                connection.readTimeout = 10000
                connection.requestMethod = "GET"
                connection.instanceFollowRedirects = true
                connection.setRequestProperty("Accept", "application/vnd.github+json")
                connection.setRequestProperty("User-Agent", "MoonTier")
                val code = connection.responseCode
                if (code !in 200..299) throw IllegalStateException("HTTP $code")
                val body = connection.inputStream.bufferedReader().use { it.readText() }
                val tag = JSONObject(body).optString("tag_name").trim()
                if (tag.isBlank()) throw IllegalStateException("响应中缺少版本号")
                AppDiagnostics.info("root", "EasyTier 版本源成功: ${URL(url).host}")
                return tag
            } catch (error: Exception) {
                val detail = "${runCatching { URL(url).host }.getOrDefault(url)}: ${error.message ?: error.javaClass.simpleName}"
                errors += detail
                AppDiagnostics.warn("root", "EasyTier 版本源失败: $detail")
            }
        }
        return null
    }

    suspend fun installLatest(
        useProxies: Boolean,
        proxies: List<String>,
        onProgress: (Int, Int) -> Unit
    ): String =
        withContext(Dispatchers.IO) {
            val tag = checkLatest(useProxies, proxies) ?: throw IllegalStateException("无法获取官方最新版本")
            val zip = File(context.cacheDir, "easytier-core-$tag.zip")
            try {
                downloadRelease(tag, zip, useProxies, proxies, onProgress)
                installRelease(zip, tag)
            } finally {
                zip.delete()
            }
            tag
        }

    suspend fun installFromZip(uri: Uri, onProgress: (Int, Int) -> Unit): String =
        withContext(Dispatchers.IO) {
            val zip = File.createTempFile("easytier-import-", ".zip", context.cacheDir)
            try {
                onProgress(0, 0)
                context.contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(zip).use { output -> input.copyTo(output) }
                } ?: throw IllegalStateException("无法读取所选 ZIP 文件")
                if (zip.length() == 0L) throw IllegalStateException("所选 ZIP 文件为空")
                installRelease(zip, "本地 ZIP")
                onProgress(100, (zip.length() / 1024).toInt())
                "本地 ZIP"
            } finally {
                zip.delete()
            }
        }

    private fun installRelease(zip: File, version: String) {
        extractRelease(zip)
        versionFile.writeText(version)
        coreFile.setExecutable(true, false)
        cliFile.setExecutable(true, false)
    }

    private fun downloadRelease(
        tag: String,
        target: File,
        useProxies: Boolean,
        proxies: List<String>,
        onProgress: (Int, Int) -> Unit
    ) {
        val original = "https://github.com/EasyTier/EasyTier/releases/download/$tag/easytier-linux-aarch64-$tag.zip"
        val errors = ArrayList<String>()
        for (url in candidateUrls(original, useProxies, proxies)) {
            val part = File(target.parentFile, target.name + ".part")
            part.delete()
            try {
                val connection = URL(url).openConnection() as HttpURLConnection
                connection.connectTimeout = 10000
                connection.readTimeout = 30000
                connection.instanceFollowRedirects = true
                connection.requestMethod = "GET"
                val code = connection.responseCode
                if (code !in 200..299) throw IllegalStateException("HTTP $code")
                val total = connection.contentLengthLong
                connection.inputStream.use { input ->
                    FileOutputStream(part).use { output ->
                        val buffer = ByteArray(64 * 1024)
                        var read: Int
                        var written = 0L
                        while (input.read(buffer).also { read = it } != -1) {
                            output.write(buffer, 0, read)
                            written += read
                            if (total > 0) onProgress((written * 100 / total).toInt().coerceIn(0, 100), (total / 1024).toInt())
                        }
                    }
                }
                if (part.length() == 0L) throw IllegalStateException("empty response")
                ZipFile(part).use { archive ->
                    val names = buildList {
                        val entries = archive.entries()
                        while (entries.hasMoreElements()) add(entries.nextElement().name.replace('\\', '/'))
                    }
                    if (names.none { it == "easytier-core" || it.endsWith("/easytier-core") } || names.none { it == "easytier-cli" || it.endsWith("/easytier-cli") }) {
                        throw IllegalStateException("response is not an EasyTier release ZIP")
                    }
                }
                if (!part.renameTo(target)) throw IllegalStateException("cannot finalize downloaded file")
                AppDiagnostics.info("root", "EasyTier 下载源成功: ${URL(url).host}")
                return
            } catch (error: Exception) {
                errors += "${runCatching { URL(url).host }.getOrDefault(url)}: ${error.message ?: error.javaClass.simpleName}"
                part.delete()
                AppDiagnostics.warn("root", "EasyTier 下载源失败: ${errors.last()}")
            }
        }
        throw IllegalStateException("所有下载源均失败：${errors.joinToString("；")}")
    }

    private fun candidateUrls(original: String, useProxies: Boolean, proxies: List<String>): List<String> {
        if (!useProxies) return listOf(original)
        val normalized = proxies.cleanItems().mapNotNull { value ->
            val candidate = if (value.contains("://")) value else "https://$value"
            runCatching {
                val parsed = URL(candidate)
                require(parsed.protocol == "https" && parsed.host.isNotBlank())
                candidate.trimEnd('/') + "/"
            }.onFailure {
                AppDiagnostics.warn("root", "忽略无效的 GitHub 下载代理: $value")
            }.getOrNull()
        }.distinctBy { it.lowercase() }
        return normalized.map { it + original } + original
    }

    private fun extractRelease(zip: File) {
        coreDir.mkdirs()
        val tempCore = File(coreDir, "${coreFile.name}.tmp")
        val tempCli = File(coreDir, "${cliFile.name}.tmp")
        tempCore.delete()
        tempCli.delete()
        try {
            ZipFile(zip).use { archive ->
                val entries = archive.entries()
                var coreExtracted = false
                var cliExtracted = false
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    if (entry.isDirectory) continue
                    when (entry.name.replace('\\', '/')) {
                        "easytier-core" -> {
                            archive.getInputStream(entry).use { input ->
                                tempCore.outputStream().use { output -> input.copyTo(output) }
                            }
                            coreExtracted = tempCore.length() > 0L
                        }
                        "easytier-cli" -> {
                            archive.getInputStream(entry).use { input ->
                                tempCli.outputStream().use { output -> input.copyTo(output) }
                            }
                            cliExtracted = tempCli.length() > 0L
                        }
                        else -> {
                            val name = entry.name.replace('\\', '/')
                            if (name.endsWith("/easytier-core")) {
                                archive.getInputStream(entry).use { input ->
                                    tempCore.outputStream().use { output -> input.copyTo(output) }
                                }
                                coreExtracted = tempCore.length() > 0L
                            } else if (name.endsWith("/easytier-cli")) {
                                archive.getInputStream(entry).use { input ->
                                    tempCli.outputStream().use { output -> input.copyTo(output) }
                                }
                                cliExtracted = tempCli.length() > 0L
                            }
                        }
                    }
                }
                if (!coreExtracted || !cliExtracted) {
                    throw IllegalStateException("官方压缩包中缺少 easytier-core 或 easytier-cli")
                }
            }
            replaceBinaries(tempCore, tempCli)
        } finally {
            tempCore.delete()
            tempCli.delete()
        }
    }

    private fun replaceBinaries(tempCore: File, tempCli: File) {
        val oldCore = File(coreDir, "${coreFile.name}.previous")
        val oldCli = File(coreDir, "${cliFile.name}.previous")
        oldCore.delete()
        oldCli.delete()
        val hadCore = coreFile.exists()
        val hadCli = cliFile.exists()
        try {
            if (hadCore && !coreFile.renameTo(oldCore)) throw IllegalStateException("无法替换现有 easytier-core")
            if (hadCli && !cliFile.renameTo(oldCli)) throw IllegalStateException("无法替换现有 easytier-cli")
            if (!tempCore.renameTo(coreFile)) throw IllegalStateException("无法安装 easytier-core")
            if (!tempCli.renameTo(cliFile)) throw IllegalStateException("无法安装 easytier-cli")
            oldCore.delete()
            oldCli.delete()
        } catch (error: Exception) {
            coreFile.delete()
            cliFile.delete()
            if (hadCore) oldCore.renameTo(coreFile)
            if (hadCli) oldCli.renameTo(cliFile)
            throw error
        }
    }

    private fun installBundledManagerClient() {
        val currentVersion = runCatching { managerClientVersionFile.readText().trim() }.getOrDefault("")
        if (managerClientFile.isFile && currentVersion == MANAGER_CLIENT_VERSION) {
            managerClientFile.setExecutable(true, false)
            return
        }

        val temp = File(coreDir, "${managerClientFile.name}.tmp")
        context.assets.open("root/moontier-root-manager").use { input ->
            temp.outputStream().use { output -> input.copyTo(output) }
        }
        if (managerClientFile.exists() && !managerClientFile.delete()) {
            temp.delete()
            throw IllegalStateException("无法更新 Root 管理客户端")
        }
        if (!temp.renameTo(managerClientFile)) {
            temp.delete()
            throw IllegalStateException("无法安装 Root 管理客户端")
        }
        managerClientFile.setExecutable(true, false)
        managerClientVersionFile.writeText(MANAGER_CLIENT_VERSION)
    }

    companion object {
        private const val MANAGER_CLIENT_VERSION = "1"
    }
}
