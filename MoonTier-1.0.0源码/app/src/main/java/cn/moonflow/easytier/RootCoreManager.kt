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

    fun checkLatest(): String? {
        val connection = URL("https://api.github.com/repos/EasyTier/EasyTier/releases/latest")
            .openConnection() as HttpURLConnection
        connection.connectTimeout = 8000
        connection.readTimeout = 10000
        connection.requestMethod = "GET"
        connection.setRequestProperty("Accept", "application/vnd.github+json")
        connection.setRequestProperty("User-Agent", "MoonTier")
        return runCatching {
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            JSONObject(body).optString("tag_name").trim().ifBlank { null }
        }.getOrNull()
    }

    suspend fun installLatest(onProgress: (Int, Int) -> Unit): String =
        withContext(Dispatchers.IO) {
            val tag = checkLatest() ?: throw IllegalStateException("无法获取官方最新版本")
            val zip = File(context.cacheDir, "easytier-core-$tag.zip")
            try {
                downloadRelease(tag, zip, onProgress)
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
        onProgress: (Int, Int) -> Unit
    ) {
        val url = "https://github.com/EasyTier/EasyTier/releases/download/$tag/easytier-linux-aarch64-$tag.zip"
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 10000
        connection.readTimeout = 30000
        connection.instanceFollowRedirects = true
        val total = connection.contentLengthLong
        connection.inputStream.use { input ->
            FileOutputStream(target).use { output ->
                val buffer = ByteArray(64 * 1024)
                var read: Int
                var written = 0L
                while (input.read(buffer).also { read = it } != -1) {
                    output.write(buffer, 0, read)
                    written += read
                    if (total > 0) {
                        onProgress(
                            (written * 100 / total).toInt().coerceIn(0, 100),
                            (total / 1024).toInt()
                        )
                    }
                }
            }
        }
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
