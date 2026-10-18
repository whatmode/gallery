package ua.volodymyr.nanocrew.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream
import org.apache.commons.compress.archivers.zip.ZipFile
import ua.volodymyr.nanocrew.core.FileEdit
import java.io.File

/**
 * Rebuilds a zip with the Editor's changes. Untouched entries are copied raw, byte for byte, so
 * symlinks, executable bits and timestamps stay exactly as they were; edited files keep their
 * original permissions; new files are added as ordinary 644 files.
 */
object ZipExport {

    private const val REGULAR_644 = 0x81A4 // S_IFREG | 0644

    suspend fun build(
        original: File,
        out: File,
        edits: List<FileEdit>,
        /** (entries written, entries in all) — called every few dozen entries. */
        onProgress: (Int, Int) -> Unit = { _, _ -> },
    ): File = withContext(Dispatchers.IO) {
        val done = edits.filter { it.status == FileEdit.Status.DONE }
        val deleted = done.filter { it.action == FileEdit.Action.DELETE }.map { it.path.trimStart('/') }.toSet()
        val byPath = done.filter { it.action != FileEdit.Action.DELETE }.associateBy { it.path }
        val written = mutableSetOf<String>()
        out.parentFile?.mkdirs()

        @Suppress("DEPRECATION")
        ZipFile(original).use { zf ->
            ZipArchiveOutputStream(out).use { zout ->
                val all = zf.entries.toList()
                var n = 0
                onProgress(0, all.size)
                for (e in all) {
                    if (++n % 25 == 0) onProgress(n, all.size)
                    val path = e.name.replace('\\', '/').trimStart('/')
                    if (path in deleted) continue
                    if (!written.add(path)) continue // duplicate entries would make an invalid zip
                    val edit = byPath[path]
                    if (edit == null || e.isDirectory || e.isUnixSymlink) {
                        zf.getRawInputStream(e).use { zout.addRawArchiveEntry(e, it) }
                        continue
                    }
                    val oldBytes = zf.getInputStream(e).use { it.readBytes() }
                    val entry = ZipArchiveEntry(e.name).apply {
                        if (e.unixMode != 0) unixMode = e.unixMode
                        if (e.time > 0) time = e.time
                    }
                    zout.putArchiveEntry(entry)
                    zout.write(encode(edit.content, crlf = String(oldBytes, Charsets.UTF_8).contains("\r\n")))
                    zout.closeArchiveEntry()
                }
                // New files the Editor created.
                for (edit in done) {
                    if (edit.action == FileEdit.Action.DELETE) continue
                    val path = edit.path.trimStart('/')
                    if (path in written || path.contains("..")) continue
                    zout.putArchiveEntry(ZipArchiveEntry(path).apply { unixMode = REGULAR_644 })
                    zout.write(encode(edit.content, crlf = false))
                    zout.closeArchiveEntry()
                    written += path
                }
                onProgress(all.size, all.size)
            }
        }
        out
    }

    private fun encode(text: String, crlf: Boolean): ByteArray {
        val normalized = text.replace("\r\n", "\n")
        return (if (crlf) normalized.replace("\n", "\r\n") else normalized).toByteArray(Charsets.UTF_8)
    }

    /** "project.zip" → "project-edited.zip" */
    /** "kit.zip" → "kit-edited.zip"; a follow-up edit of that → "kit-edited-2.zip", then -3… */
    fun outputName(archiveName: String): String {
        val base = archiveName.removeSuffix(".zip").removeSuffix(".ZIP")
        val m = Regex("^(.*)-edited(?:-(\\d+))?$").find(base)
        return if (m == null) "$base-edited.zip"
        else "${m.groupValues[1]}-edited-${(m.groupValues[2].toIntOrNull() ?: 1) + 1}.zip"
    }
}
