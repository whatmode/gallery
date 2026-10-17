package ua.volodymyr.nanocrew.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ua.volodymyr.nanocrew.core.FileEdit
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Rebuilds a zip with the Editor's changes: every untouched entry is copied as-is (including
 * binaries and the folders the Reader skipped), edited files are replaced, new files are added.
 */
object ZipExport {

    suspend fun build(original: File, out: File, edits: List<FileEdit>): File = withContext(Dispatchers.IO) {
        val done = edits.filter { it.status == FileEdit.Status.DONE }
        val deleted = done.filter { it.action == FileEdit.Action.DELETE }.map { it.path.trimStart('/') }.toSet()
        val byPath = done.filter { it.action != FileEdit.Action.DELETE }.associateBy { it.path }
        val written = mutableSetOf<String>()
        out.parentFile?.mkdirs()

        ZipInputStream(original.inputStream().buffered()).use { zin ->
            ZipOutputStream(out.outputStream().buffered()).use { zout ->
                while (true) {
                    val e = zin.nextEntry ?: break
                    val path = e.name.replace('\\', '/').trimStart('/')
                    if (path in deleted) continue
                    if (!written.add(path)) continue // duplicate entries would make an invalid zip
                    val entry = ZipEntry(e.name).apply { if (e.time > 0) time = e.time }
                    zout.putNextEntry(entry)
                    val edit = byPath[path]
                    if (!e.isDirectory) {
                        if (edit != null) {
                            val oldBytes = zin.readBytes()
                            zout.write(encode(edit.content, crlf = String(oldBytes, Charsets.UTF_8).contains("\r\n")))
                        } else {
                            zin.copyTo(zout)
                        }
                    }
                    zout.closeEntry()
                }
                // New files the Editor created.
                for (edit in done) {
                    if (edit.action == FileEdit.Action.DELETE) continue
                    val path = edit.path.trimStart('/')
                    if (path in written || path.contains("..")) continue
                    zout.putNextEntry(ZipEntry(path))
                    zout.write(encode(edit.content, crlf = false))
                    zout.closeEntry()
                    written += path
                }
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
