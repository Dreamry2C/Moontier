package cn.moonflow.easytier

import java.io.File

/** Read the native Android loader, not uname/translated app ABI on emulators. */
enum class RootArchitecture(val releaseName: String, val machine: Int) {
    ARM64("aarch64", 183), X86_64("x86_64", 62);

    companion object {
        fun fromElf(file: File): RootArchitecture? = runCatching {
            file.inputStream().use { input ->
                val header = ByteArray(20)
                var read = 0
                while (read < header.size) {
                    val count = input.read(header, read, header.size - read)
                    if (count < 0) return null
                    read += count
                }
                fromHeader(header)
            }
        }.getOrNull()

        fun fromHeader(header: ByteArray): RootArchitecture? {
            if (header.size < 20 || header[0] != 0x7f.toByte() ||
                header[1] != 69.toByte() || header[2] != 76.toByte() || header[3] != 70.toByte() ||
                header[4] != 2.toByte() || header[5] != 1.toByte()) return null
            val machine = (header[18].toInt() and 255) or ((header[19].toInt() and 255) shl 8)
            return entries.firstOrNull { it.machine == machine }
        }

        fun device(): RootArchitecture = fromElf(File("/system/bin/linker64"))
            ?: fromElf(File("/system/bin/sh"))
            ?: error("当前设备不支持 64 位 Root 核心")
    }
}
