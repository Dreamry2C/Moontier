package cn.moonflow.easytier

internal object PollingPolicy {
    const val VPN_BACKGROUND_MS = 300_000L

    fun vpn(intervalMs: Long, missingIp: Boolean, elapsedMs: Long, noTun: Boolean): Long {
        if (intervalMs >= VPN_BACKGROUND_MS) return intervalMs
        if (!missingIp || noTun) return intervalMs
        return when {
            elapsedMs < 15_000 -> 500L
            elapsedMs < 30_000 -> 2_000L
            elapsedMs < 60_000 -> 10_000L
            else -> 30_000L
        }
    }

    fun root(foreground: Boolean, visible: Boolean, active: Boolean, waiting: Boolean,
             elapsedMs: Long, failures: Int): Long {
        if (!active) return 60_000L
        val normal = if (!foreground || !visible) 30_000L else if (waiting && elapsedMs < 15_000) 1_000L else 3_000L
        if (failures == 0) return normal
        return maxOf(normal, minOf(60_000L, 3_000L shl failures.coerceIn(0, 5)))
    }
}
