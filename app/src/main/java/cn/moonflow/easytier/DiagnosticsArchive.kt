package cn.moonflow.easytier

import java.io.File
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

internal object DiagnosticsArchive {
    fun write(
        output: OutputStream,
        cacheDir: File,
        report: String,
        coreLog: File,
        ownerPid: Int,
        snapshotApp: (File) -> Unit
    ) {
        val app = File.createTempFile("diagnostics-app-", ".log", cacheDir)
        val core = File.createTempFile("diagnostics-core-", ".log", cacheDir)
        try {
            snapshotApp(app)
            LogFiles.snapshotCore(coreLog, core, ownerPid)
            ZipOutputStream(output.buffered()).use { zip ->
                for ((name, file) in listOf("moontier-app.log" to app, "moontier-root-core.log" to core)) {
                    zip.putNextEntry(ZipEntry(name))
                    file.inputStream().use { LogTime.copyNormalized(it, zip) }
                    zip.closeEntry()
                }
                zip.putNextEntry(ZipEntry("moontier-diagnostics.txt"))
                zip.write(LogTime.normalize(report).toByteArray(Charsets.UTF_8))
                zip.write("\n\n[application log - complete snapshot]\n".toByteArray())
                app.inputStream().use { LogTime.copyNormalized(it, zip) }
                zip.write("\n\n[root core log - complete snapshot]\n".toByteArray())
                core.inputStream().use { LogTime.copyNormalized(it, zip) }
                zip.closeEntry()
            }
        } finally {
            app.delete()
            core.delete()
        }
    }
}
