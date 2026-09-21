package cn.moonflow.easytier

/** Only a completed root-side identity probe may declare the process absent. */
internal object RootProcessStatus {
    fun parse(result: ShellResult): Long {
        check(result.success) { result.output.ifBlank { "Root 进程查询失败" } }
        val line = result.output.lineSequence().lastOrNull { it.isNotBlank() }.orEmpty().trim()
        if (line == "STOPPED") return 0L
        if (line.startsWith("RUNNING ")) {
            val pid = line.removePrefix("RUNNING ").toLongOrNull()
            if (pid != null && pid > 0) return pid
        }
        error("无法确认 Core 进程状态：${result.output.takeLast(300)}")
    }
}
