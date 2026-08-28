package cn.moonflow.easytier

object RootSystemManager {
    fun ensureAdbTcp(port: Int = DEFAULT_ADB_PORT): ShellResult {
        require(port in 1..65535) { "无效的 ADB 端口: $port" }
        val command = """
            port=$port
            is_listening() {
                (ss -lnt 2>/dev/null || netstat -tln 2>/dev/null) |
                    grep -qE "[:.]${'$'}port([[:space:]]|${'$'})"
            }
            if is_listening; then
                echo "ADB 已监听 ${'$'}port"
                exit 0
            fi
            setprop persist.adbd.enable 1
            setprop persist.adb.tcp.port "${'$'}port"
            setprop service.adb.tcp.port "${'$'}port"
            if [ "${'$'}(getprop init.svc.vdbd)" = "running" ]; then
                stop vdbd 2>/dev/null
                start vdbd 2>/dev/null
            else
                start vdbd 2>/dev/null
            fi
            if ! is_listening; then
                stop adbd 2>/dev/null
                start adbd 2>/dev/null
            fi
            tries=0
            while [ "${'$'}tries" -lt 10 ]; do
                if is_listening; then
                    echo "ADB 已开启 ${'$'}port"
                    exit 0
                fi
                tries=${'$'}((tries + 1))
                sleep 1
            done
            echo "ADB 未能监听 ${'$'}port"
            exit 1
        """.trimIndent()
        return RootManager.su(command, timeoutMs = 20_000)
    }

    private const val DEFAULT_ADB_PORT = 5555
}
