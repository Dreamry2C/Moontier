package cn.moonflow.easytier

import java.io.InputStream
import java.io.OutputStream
import java.text.ParsePosition
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

internal object LogTime {
    private const val DATE_PATTERN = "yyyy-MM-dd'T'HH:mm:ss"
    private val zone = TimeZone.getTimeZone("GMT+08:00")
    private val timestamp = Regex(
        "^((?:--- MoonTier manager start |generated_at=)?)(\\d{4}-\\d{2}-\\d{2}[T ]\\d{2}:\\d{2}:\\d{2})" +
            "(\\.\\d{1,9})?(Z|[+-]\\d{2}:?\\d{2})?(?=[ \\t\\r\\n]|$)",
        RegexOption.MULTILINE
    )

    fun now(date: Date = Date()): String = SimpleDateFormat("$DATE_PATTERN.SSS", Locale.US).apply {
        timeZone = zone
    }.format(date) + "+08:00"

    /** Legacy App timestamps had no offset and were written in the user's UTC+8 zone. */
    fun normalize(text: String): String = timestamp.replace(text) { match ->
        if (match.groupValues[4].isEmpty() && match.groupValues[2][10] != ' ') return@replace match.value
        val offset = match.groupValues[4].ifEmpty { "+08:00" }
        val sourceZone = if (offset == "Z") TimeZone.getTimeZone("UTC") else {
            val digits = offset.drop(1).replace(":", "")
            if (digits.take(2).toInt() > 23 || digits.takeLast(2).toInt() > 59) return@replace match.value
            TimeZone.getTimeZone("GMT$offset")
        }
        val parser = SimpleDateFormat(DATE_PATTERN, Locale.US).apply {
            isLenient = false
            timeZone = sourceZone
        }
        val position = ParsePosition(0)
        val date = parser.parse(match.groupValues[2].replace(' ', 'T'), position)
        if (date == null || position.index != 19) match.value else {
            val local = SimpleDateFormat(DATE_PATTERN, Locale.US).apply { timeZone = zone }.format(date)
            match.groupValues[1] + local + match.groupValues[3] + "+08:00"
        }
    }

    /** Only buffer a line's timestamp prefix, even for multi-GiB or newline-free logs. */
    fun copyNormalized(input: InputStream, output: OutputStream) {
        val buffer = ByteArray(64 * 1024)
        val prefix = ByteArray(128)
        var prefixSize = 0
        var atPrefix = true
        fun writePrefix() {
            // Latin-1 round trips all bytes unchanged outside the ASCII timestamp.
            val text = String(prefix, 0, prefixSize, Charsets.ISO_8859_1)
            output.write(normalize(text).toByteArray(Charsets.ISO_8859_1))
            prefixSize = 0
        }
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            var index = 0
            while (index < count) {
                if (atPrefix) {
                    val byte = buffer[index++]
                    prefix[prefixSize++] = byte
                    if (byte == 10.toByte() || prefixSize == prefix.size) {
                        writePrefix()
                        atPrefix = byte == 10.toByte()
                    }
                } else {
                    val start = index
                    while (index < count && buffer[index] != 10.toByte()) index++
                    if (index < count) { index++; atPrefix = true }
                    output.write(buffer, start, index - start)
                }
            }
        }
        if (prefixSize > 0) writePrefix()
    }
}
