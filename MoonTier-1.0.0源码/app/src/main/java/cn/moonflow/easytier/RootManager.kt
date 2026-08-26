package cn.moonflow.easytier

import java.util.concurrent.TimeUnit

data class ShellResult(
    val exitCode: Int,
    val output: String,
    val timedOut: Boolean = false
) {
    val success: Boolean get() = exitCode == 0
}

data class RootProbe(
    val available: Boolean,
    val suPath: String,
    val version: String = ""
)

object RootManager {
    private val suCandidates = listOf(
        "su",
        "/system/bin/su",
        "/system/xbin/su",
        "/sbin/su",
        "/debug_ramdisk/su",
        "/data/adb/ksu/bin/su",
        "/data/adb/ap/bin/su",
        "/data/adb/ap/su",
        "/sbin/.magisk/busybox",
        "/data/adb/magisk/busybox"
    )

    @Volatile
    private var cachedProbe: RootProbe? = null

    fun probe(refresh: Boolean = false): RootProbe {
        val cached = cachedProbe
        if (!refresh && cached != null) return cached
        val detected = runCatching { detect() }.getOrDefault(RootProbe(false, ""))
        cachedProbe = detected
        return detected
    }

    fun refresh(): RootProbe = probe(refresh = true)

    fun isAvailable(): Boolean = probe().available

    fun su(command: String, timeoutMs: Long = 8000): ShellResult {
        val suPath = probe().suPath
        if (suPath.isBlank()) return ShellResult(1, "未检测到 Root 管理器授权")
        val cmd = if (suPath.endsWith("busybox")) {
            listOf(suPath, "su", "-c", command)
        } else {
            listOf(suPath, "-c", command)
        }
        return runProcess(cmd, timeoutMs)
    }

    private fun detect(): RootProbe {
        for (candidate in suCandidates) {
            val base = if (candidate.endsWith("busybox")) listOf(candidate, "su") else listOf(candidate)
            val idResult = runProcess(base + listOf("-c", "id"), 5000)
            if (idResult.success && idResult.output.contains("uid=0")) {
                val version = runProcess(base + listOf("--version"), 2500).output.trim()
                return RootProbe(true, candidate, version)
            }
        }
        return RootProbe(false, "")
    }

    private fun runProcess(cmd: List<String>, timeoutMs: Long): ShellResult {
        return try {
            val process = ProcessBuilder(cmd).redirectErrorStream(true).start()
            val output = StringBuilder()
            val reader = Thread({
                process.inputStream.bufferedReader().useLines { lines ->
                    lines.forEach { line ->
                        synchronized(output) {
                            if (output.isNotEmpty()) output.append('\n')
                            output.append(line)
                        }
                    }
                }
            }, "MoonTier-root-output").apply {
                isDaemon = true
                start()
            }
            val finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
            if (!finished) {
                process.destroyForcibly()
                return ShellResult(-1, "命令执行超时", timedOut = true)
            }
            reader.join(1000)
            ShellResult(process.exitValue(), synchronized(output) { output.toString().trim() })
        } catch (e: Exception) {
            ShellResult(-1, e.message ?: "无法执行 root 命令")
        }
    }
}
