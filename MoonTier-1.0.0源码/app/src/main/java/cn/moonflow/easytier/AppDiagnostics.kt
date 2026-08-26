package cn.moonflow.easytier

import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object CoreLogLevel {
    const val OFF = "off"
    const val NORMAL = "normal"
    const val DEBUG = "debug"

    fun normalize(value: String): String = when (value.lowercase(Locale.US)) {
        NORMAL -> NORMAL
        DEBUG -> DEBUG
        else -> OFF
    }

    fun rustLevel(value: String): String = when (normalize(value)) {
        DEBUG -> "debug"
        NORMAL -> "warn"
        else -> "off"
    }

    fun rustFilter(value: String): String = when (normalize(value)) {
        DEBUG -> "CORE=debug,easytier=debug"
        NORMAL -> "CORE=warn,easytier=warn"
        else -> "off"
    }

    fun label(value: String): String = when (normalize(value)) {
        NORMAL -> "\u666e\u901a"
        DEBUG -> "Debug"
        else -> "\u5173\u95ed"
    }
}

/** Writes launcher diagnostics separately from the FFI and root-manager core logs. */
object AppDiagnostics {
    private const val TAG = "MoonTierDiag"
    private const val MAX_FILE_BYTES = 256 * 1024

    private var logFile: File? = null

    @Volatile
    private var coreLogLevel = CoreLogLevel.OFF

    fun initialize(context: Context, level: String = CoreLogLevel.OFF) {
        synchronized(this) {
            if (logFile == null) logFile = File(context.filesDir, "diagnostics.log")
            coreLogLevel = CoreLogLevel.normalize(level)
        }
    }

    fun configure(level: String) {
        coreLogLevel = CoreLogLevel.normalize(level)
    }

    fun info(source: String, message: String) {
        Log.i(TAG, "$source: $message")
        if (coreLogLevel == CoreLogLevel.DEBUG) append("INFO", source, message)
    }

    fun event(source: String, message: String) {
        Log.i(TAG, "$source: $message")
        if (coreLogLevel != CoreLogLevel.OFF) append("EVENT", source, message)
    }

    fun debug(source: String, message: String) {
        Log.d(TAG, "$source: $message")
        if (coreLogLevel == CoreLogLevel.DEBUG) append("DEBUG", source, message)
    }

    fun warn(source: String, message: String, error: Throwable? = null) {
        Log.w(TAG, "$source: $message", error)
        if (coreLogLevel != CoreLogLevel.OFF) append("WARN", source, formatMessage(message, error))
    }

    fun error(source: String, message: String, error: Throwable? = null) {
        Log.e(TAG, "$source: $message", error)
        if (coreLogLevel != CoreLogLevel.OFF) append("ERROR", source, formatMessage(message, error))
    }

    fun recent(maxChars: Int = 12_000): String = synchronized(this) {
        val text = runCatching { logFile?.readText().orEmpty() }.getOrDefault("")
        if (text.length <= maxChars) text else text.takeLast(maxChars)
    }

    fun clear() = synchronized(this) {
        runCatching { logFile?.writeText("") }
    }

    fun buildReport(
        settings: AppSettings,
        runtime: RuntimeState,
        root: RootTierState,
        managerLog: List<String>
    ): String = buildString {
        appendLine("MoonTier diagnostics")
        appendLine("generated_at=${timestamp()}")
        appendLine("android=${Build.VERSION.RELEASE} sdk=${Build.VERSION.SDK_INT} device=${Build.MANUFACTURER} ${Build.MODEL}")
        appendLine("mode=${if (settings.rootModeEnabled) "root" else "vpn"} core_log=${CoreLogLevel.normalize(settings.coreLogLevel)}")
        appendLine()
        appendLine("[launcher]")
        append(recent())
        appendLine()
        appendLine("[ffi_state]")
        appendLine("running=${runtime.running} starting=${runtime.starting} stopping=${runtime.stopping} cidr=${runtime.localCidr}")
        runtime.logs.takeLast(80).forEach(::appendLine)
        appendLine()
        appendLine("[root_state]")
        appendLine("core_ready=${root.core.ready} version=${root.core.installedVersion} instances=${root.instances.size}")
        root.instances.forEach { instance ->
            appendLine("instance=${instance.configId} running=${instance.running} cidr=${instance.localCidr} error=${instance.error}")
            instance.logs.takeLast(30).forEach(::appendLine)
        }
        appendLine("config_server=${root.configServer.running} connected=${root.configServer.connected} error=${root.configServer.error}")
        root.configServer.logs.takeLast(50).forEach(::appendLine)
        if (managerLog.isNotEmpty()) {
            appendLine()
            appendLine("[root_manager]")
            managerLog.takeLast(120).forEach(::appendLine)
        }
    }

    private fun append(kind: String, source: String, message: String) {
        synchronized(this) {
            val file = logFile ?: return
            runCatching {
                file.parentFile?.mkdirs()
                file.appendText("${timestamp()} $kind/$source $message\n")
                if (file.length() > MAX_FILE_BYTES) {
                    file.writeText(file.readText().takeLast(MAX_FILE_BYTES / 2))
                }
            }
        }
    }

    private fun formatMessage(message: String, error: Throwable?): String =
        if (error == null) message else "$message\n${Log.getStackTraceString(error)}"

    private fun timestamp(): String = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
}
