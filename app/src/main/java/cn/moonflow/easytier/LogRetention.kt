package cn.moonflow.easytier

import java.math.BigDecimal
import java.math.RoundingMode

internal object LogRetention {
    const val DEFAULT_CORE_BYTES = 10L * 1024 * 1024
    const val DEFAULT_APP_BYTES = 512L * 1024

    /** Zero means unlimited; null means invalid input. Accept fractional MiB. */
    fun parseMiB(input: String): Long? {
        val text = input.trim()
        if (text.isEmpty()) return 0
        if (text.length > 32 || !text.matches(Regex("(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)"))) return null
        return runCatching {
            BigDecimal(text).multiply(BigDecimal(1024 * 1024))
                .setScale(0, RoundingMode.DOWN).longValueExact()
        }.getOrNull()
    }
}
