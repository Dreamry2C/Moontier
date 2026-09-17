package cn.moonflow.easytier

internal object LogPreview {
    private val recordStart = Regex(
        "^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(?:\\.\\d+)?\\+08:00 (?:INFO|EVENT|DEBUG|WARN|ERROR)/",
        RegexOption.MULTILINE
    )

    /** Keep the newest complete records in append order; stack traces stay with their header. */
    fun chronological(text: String, maxChars: Int = 8_000): String {
        if (text.isBlank() || maxChars <= 0) return ""
        val starts = recordStart.findAll(text).map { it.range.first }.toList()
        if (starts.isEmpty()) return "当前记录超出预览范围，请导出完整日志。"
        val records = ArrayList<String>()
        var length = 0
        for (index in starts.indices.reversed()) {
            val end = starts.getOrNull(index + 1) ?: text.length
            val record = text.substring(starts[index], end).trimEnd('\r', '\n')
            val gap = records.lastOrNull()?.let { separator(record, it) }.orEmpty()
            if (length + gap.length + record.length > maxChars) {
                if (records.isEmpty()) {
                    val notice = "\n…该记录较长，完整内容请导出日志。".take(maxChars)
                    return record.take(maxChars - notice.length) + notice
                }
                break
            }
            records += record
            length += gap.length + record.length
        }
        val result = StringBuilder()
        val ordered = records.asReversed()
        ordered.forEachIndexed { index, record ->
            if (index > 0) result.append(separator(ordered[index - 1], record))
            result.append(record)
        }
        return result.toString()
    }

    private fun separator(older: String, newer: String): String =
        if (singleLineEvent(older) && singleLineEvent(newer)) "\n" else "\n\n"

    private fun singleLineEvent(record: String): Boolean = !record.contains('\n') && record.substringAfter(' ').startsWith("EVENT/")
}
