package cn.moonflow.easytier

import java.io.File
import java.util.concurrent.atomic.AtomicLong

/** Writes invalidate without waiting for a reader's parsing work. */
internal class LogPreviewCache(
    private val render: (String) -> String = { LogPreview.chronological(LogTime.normalize(it)) }
) {
    private data class Stamp(val path: String?, val length: Long, val modified: Long, val revision: Long)
    private val revision = AtomicLong()
    private var previous: Stamp? = null
    private var cached = ""

    fun invalidate() { revision.incrementAndGet() }

    private fun stamp(file: File?) = Stamp(file?.path, file?.length() ?: 0, file?.lastModified() ?: 0, revision.get())

    @Synchronized
    fun preview(file: File?): String {
        val before = stamp(file)
        if (before == previous) return cached
        val text = file?.let { LogFiles.tail(it) }.orEmpty()
        val result = render(text)
        if (stamp(file) == before) {
            cached = result
            previous = before
        }
        return result
    }
}
