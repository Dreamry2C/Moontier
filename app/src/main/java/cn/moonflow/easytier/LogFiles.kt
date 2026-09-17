package cn.moonflow.easytier

import java.io.File
import java.io.RandomAccessFile

internal object LogFiles {
    const val PREVIEW_BYTES = 1024 * 1024

    /** Caller serializes appends/exports. Move the retained tail using bounded memory. */
    fun trimToLimit(file: File, maxBytes: Long) {
        if (maxBytes <= 0 || !file.exists() || file.length() <= maxBytes) return
        RandomAccessFile(file, "rw").use { log ->
            val end = log.length()
            if (end <= maxBytes) return
            val keep = maxBytes / 2
            val buffer = ByteArray(64 * 1024)
            var copied = 0L
            while (copied < keep) {
                val count = minOf(buffer.size.toLong(), keep - copied).toInt()
                log.seek(end - keep + copied)
                log.readFully(buffer, 0, count)
                log.seek(copied)
                log.write(buffer, 0, count)
                copied += count
            }
            log.setLength(keep)
        }
    }

    fun tail(file: File, maxBytes: Int = PREVIEW_BYTES): String {
        if (!file.exists()) return ""
        RandomAccessFile(file, "r").use { input ->
            val end = input.length()
            val size = minOf(end, maxBytes.toLong()).toInt()
            input.seek(end - size)
            val buffer = ByteArray(size)
            var read = 0
            while (read < size) {
                val count = input.read(buffer, read, size - read)
                if (count < 0) break
                read += count
            }
            return String(buffer, 0, read, Charsets.UTF_8)
        }
    }

    /** Copy exactly the bytes present at open, even while the writer keeps appending. */
    fun snapshot(source: File?, target: File) {
        target.outputStream().use { output ->
            if (source == null || !source.exists()) return
            source.inputStream().use { input ->
                var remaining = input.channel.size()
                val buffer = ByteArray(64 * 1024)
                while (remaining > 0) {
                    val count = input.read(buffer, 0, minOf(remaining, buffer.size.toLong()).toInt())
                    check(count >= 0) { "日志在导出时被截断，请重试" }
                    output.write(buffer, 0, count)
                    remaining -= count
                }
            }
        }
    }

    /** The hourly shell trimmer uses the same directory lock and owner PID. */
    fun snapshotCore(source: File, target: File, ownerPid: Int) {
        if (!source.exists()) { snapshot(null, target); return }
        val lock = File(source.path + ".lock")
        val deadline = System.nanoTime() + 10_000_000_000L
        while (!lock.mkdir()) {
            val owner = File(lock, "pid").readTextOrEmpty().trim().toIntOrNull()
            val start = File(lock, "start").readTextOrEmpty().trim()
            if ((owner != null && (!File("/proc/$owner").exists() || (start.isNotEmpty() && processStart(owner) != start))) ||
                (owner == null && System.currentTimeMillis() - lock.lastModified() > 30_000)) {
                File(lock, "pid").delete()
                File(lock, "start").delete()
                lock.delete()
            }
            check(System.nanoTime() < deadline) { "日志正在整理，请稍后重试" }
            Thread.sleep(50)
        }
        try {
            File(lock, "pid").writeText(ownerPid.toString())
            File(lock, "start").writeText(processStart(ownerPid))
            snapshot(source, target)
        } finally {
            File(lock, "pid").delete()
            File(lock, "start").delete()
            lock.delete()
        }
    }

    private fun File.readTextOrEmpty(): String = runCatching { readText() }.getOrDefault("")

    private fun processStart(pid: Int): String = File("/proc/$pid/stat").readTextOrEmpty()
        .substringAfterLast(") ").split(' ').getOrNull(19).orEmpty()
}
