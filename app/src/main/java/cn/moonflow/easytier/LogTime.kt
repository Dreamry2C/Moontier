package cn.moonflow.easytier

import java.io.InputStream
import java.io.OutputStream
import java.text.ParsePosition
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.regex.Pattern

internal object LogTime {
    private const val DATE_PATTERN = "yyyy-MM-dd'T'HH:mm:ss"
    private val zone = TimeZone.getTimeZone("GMT+08:00")
    private val timestamp = Pattern.compile(
        "^((?:--- MoonTier manager start |generated_at=)?)(\\d{4}-\\d{2}-\\d{2}[T ]\\d{2}:\\d{2}:\\d{2})" +
            "(\\.\\d{1,9})?(Z|[+-]\\d{2}:?\\d{2})?(?=[ \\t\\r\\n]|$)",
        Pattern.MULTILINE
    )

    fun now(date: Date = Date()): String = SimpleDateFormat("$DATE_PATTERN.SSS", Locale.US).apply {
        timeZone = zone
    }.format(date) + "+08:00"

    /** Legacy App timestamps had no offset and were written in the user's UTC+8 zone. */
    fun normalize(text: String): String {
        // Kotlin MatchResult.next() creates a new Matcher for every record. Android ICU
        // copies its input, making a whole-log replace transform disproportionately costly.
        val matcher = timestamp.matcher(text)
        var output: StringBuilder? = null
        var copied = 0
        var parser: SimpleDateFormat? = null
        var formatter: SimpleDateFormat? = null
        var lastOffset = ""
        while (matcher.find()) {
            val stamp = matcher.group(2)!!
            val originalOffset = matcher.group(4).orEmpty()
            if (stamp[10] == 'T' && (originalOffset.isEmpty() || originalOffset == "+08:00")) continue
            val offset = originalOffset.ifEmpty { "+08:00" }
            if (offset != "Z") {
                val digits = offset.drop(1).replace(":", "")
                if (digits.take(2).toInt() > 23 || digits.takeLast(2).toInt() > 59) continue
            }
            val currentParser = parser ?: SimpleDateFormat(DATE_PATTERN, Locale.US).apply {
                isLenient = false
            }.also { parser = it }
            if (lastOffset != offset) {
                currentParser.timeZone = TimeZone.getTimeZone(if (offset == "Z") "UTC" else "GMT$offset")
                lastOffset = offset
            }
            val position = ParsePosition(0)
            val date = currentParser.parse(stamp.replace(' ', 'T'), position)
            if (date == null || position.index != 19) continue
            val currentFormatter = formatter ?: SimpleDateFormat(DATE_PATTERN, Locale.US).apply {
                timeZone = zone
            }.also { formatter = it }
            val target = output ?: StringBuilder(text.length).also { output = it }
            target.append(text, copied, matcher.start())
                .append(matcher.group(1)).append(currentFormatter.format(date))
                .append(matcher.group(3).orEmpty()).append("+08:00")
            copied = matcher.end()
        }
        return output?.append(text, copied, text.length)?.toString() ?: text
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
