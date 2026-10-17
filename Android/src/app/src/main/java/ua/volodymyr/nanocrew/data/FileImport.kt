package ua.volodymyr.nanocrew.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.provider.OpenableColumns
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ua.volodymyr.nanocrew.core.ArchiveInfo
import ua.volodymyr.nanocrew.core.Attachment
import java.util.zip.ZipInputStream
import kotlin.math.max

/**
 * Turns a picked or shared file into [Attachment]s, entirely on the phone.
 * Text-like files → text; PDFs → text (or page images if scanned); .docx → text; images → bitmap.
 */
class FileImport(context: Context) {
    private val app = context.applicationContext
    private val resolver = app.contentResolver
    @Volatile private var pdfBoxReady = false

    class ImportException(message: String) : Exception(message)

    suspend fun import(uri: Uri): List<Attachment> = withContext(Dispatchers.IO) {
        val (name, size) = nameAndSize(uri)
        val mime = resolver.getType(uri).orEmpty()
        val ext = name.substringAfterLast('.', "").lowercase()
        if (size > MAX_BYTES) throw ImportException("$name is too large (max ${MAX_BYTES / 1_048_576} MB).")

        when {
            mime.startsWith("image/") || ext in IMAGE_EXT ->
                listOf(Attachment(name, Attachment.Kind.IMAGE, image = decodeImage(uri)))
            mime == "application/pdf" || ext == "pdf" -> readPdf(uri, name)
            ext == "docx" || mime == DOCX_MIME -> listOf(textAttachment(name, readDocx(uri)))
            ext == "zip" || mime in ZIP_MIMES -> readZip(uri, name)
            ext in UNSUPPORTED_ARCHIVES ->
                throw ImportException("$name: only .zip archives are supported. Re-pack it as a zip and try again.")
            else -> listOf(textAttachment(name, readText(uri, name)))
        }
    }

    private fun textAttachment(name: String, raw: String): Attachment {
        val text = raw.replace("\u0000", "").trim()
        if (text.isBlank()) throw ImportException("$name has no readable text.")
        val clipped = text.take(MAX_TEXT_CHARS)
        return Attachment(
            name, Attachment.Kind.TEXT, text = clipped,
            note = if (text.length > MAX_TEXT_CHARS) "first ${MAX_TEXT_CHARS / 1000}k characters" else "",
        )
    }

    private fun nameAndSize(uri: Uri): Pair<String, Long> {
        var name = uri.lastPathSegment?.substringAfterLast('/') ?: "file"
        var size = -1L
        runCatching {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val n = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val s = c.getColumnIndex(OpenableColumns.SIZE)
                    if (n >= 0 && !c.isNull(n)) name = c.getString(n)
                    if (s >= 0 && !c.isNull(s)) size = c.getLong(s)
                }
            }
        }
        return name to size
    }

    private fun decodeImage(uri: Uri): Bitmap = decodeImage(ImageDecoder.createSource(resolver, uri))

    private fun decodeImage(source: ImageDecoder.Source): Bitmap {
        return ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            val w = info.size.width
            val h = info.size.height
            val scale = MAX_IMAGE_SIDE.toFloat() / max(w, h)
            if (scale < 1f) decoder.setTargetSize((w * scale).toInt().coerceAtLeast(1), (h * scale).toInt().coerceAtLeast(1))
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE // ML needs readable pixels
        }
    }

    private fun readText(uri: Uri, name: String): String {
        // (InputStream.readNBytes needs API 33; read manually up to the cap.)
        val bytes = resolver.openInputStream(uri)?.use { input ->
            val out = java.io.ByteArrayOutputStream()
            val buf = ByteArray(64 * 1024)
            val cap = MAX_TEXT_CHARS * 2
            while (out.size() < cap) {
                val n = input.read(buf, 0, minOf(buf.size, cap - out.size()))
                if (n < 0) break
                out.write(buf, 0, n)
            }
            out.toByteArray()
        } ?: throw ImportException("Couldn't open $name.")
        val head = bytes.take(4096)
        val zeros = head.count { it == 0.toByte() }
        if (zeros > 4) throw ImportException("$name isn't a text file. Supported: text, code, CSV, JSON, PDF, Word (.docx), images and .zip.")
        return String(bytes, Charsets.UTF_8)
    }

    private fun readDocx(uri: Uri): String =
        resolver.openInputStream(uri)?.use { docxText(it) } ?: throw ImportException("Couldn't read that Word file.")

    private fun docxText(input: java.io.InputStream): String {
        run {
            ZipInputStream(input).let { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    if (entry.name == "word/document.xml") {
                        val xml = zip.readBytes().toString(Charsets.UTF_8)
                        return xml
                            .replace(Regex("</w:p>"), "\n")
                            .replace(Regex("<w:tab/>"), "\t")
                            .replace(Regex("<w:br/>"), "\n")
                            .replace(Regex("<[^>]+>"), "")
                            .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
                            .replace("&quot;", "\"").replace("&apos;", "'")
                            .replace(Regex("\n{3,}"), "\n\n")
                    }
                }
            }
        }
        throw ImportException("Couldn't read that Word file.")
    }

    // ---- zip archives -----------------------------------------------------------------------

    private class ZipFile(val path: String, val bytes: ByteArray, val truncated: Boolean)

    /**
     * Reads a .zip in memory (nothing is extracted to storage). Produces one text attachment
     * with the file tree plus the most useful files' contents (README and configs first),
     * and up to [MAX_ZIP_IMAGES] image attachments for pictures inside the archive.
     */
    private fun readZip(uri: Uri, name: String): List<Attachment> {
        val files = mutableListOf<ZipFile>()
        val tree = mutableListOf<String>()
        var entries = 0
        var totalBytes = 0L
        var skippedJunk = 0
        var skippedBinary = 0
        var stoppedEarly = false

        // Keep a private copy of the original so the Editor can rebuild it with changes later.
        val archiveId = java.util.UUID.randomUUID().toString()
        val copy = archiveFile(archiveId)
        try {
            copy.parentFile?.mkdirs()
            resolver.openInputStream(uri)?.use { input -> copy.outputStream().use { input.copyTo(it) } }
                ?: throw ImportException("Couldn't open $name.")
            pruneArchives()
        } catch (e: ImportException) {
            throw e
        } catch (e: Exception) {
            copy.delete()
            throw ImportException("Couldn't copy $name: ${e.message ?: e::class.java.simpleName}")
        }

        // Symlinks (e.g. systemd "wants" links) look like tiny text files; never read or edit them.
        val links = try {
            @Suppress("DEPRECATION")
            org.apache.commons.compress.archivers.zip.ZipFile(copy).use { zf ->
                zf.entries.toList().filter { it.isUnixSymlink }.map { it.name.replace('\\', '/').trimStart('/') }.toSet()
            }
        } catch (e: Exception) { emptySet() }

        try {
            copy.inputStream().use { raw ->
                ZipInputStream(java.io.BufferedInputStream(raw)).use { zip ->
                    while (true) {
                        val e = zip.nextEntry ?: break
                        if (++entries > MAX_ZIP_ENTRIES || totalBytes > MAX_ZIP_TOTAL_BYTES) { stoppedEarly = true; break }
                        if (e.isDirectory) continue
                        val path = e.name.replace('\\', '/').trimStart('/')
                        if (isJunk(path)) { skippedJunk++; continue }
                        if (path in links) { tree += "$path (symlink)"; continue }
                        tree += path
                        val ext = path.substringAfterLast('/').substringAfterLast('.', "").lowercase()
                        val wanted = ext in TEXT_EXT || ext in IMAGE_EXT || ext == "pdf" || ext == "docx" ||
                            path.substringAfterLast('/').lowercase() in TEXT_NAMES || ext.isEmpty()
                        if (!wanted) { skippedBinary++; continue }
                        val cap = if (ext in IMAGE_EXT || ext == "pdf") MAX_ZIP_ENTRY_BINARY else MAX_ZIP_ENTRY_TEXT
                        val (bytes, truncated) = readCapped(zip, cap)
                        totalBytes += bytes.size
                        files += ZipFile(path, bytes, truncated)
                    }
                }
            }
        } catch (e: ImportException) {
            throw e
        } catch (e: Exception) {
            throw ImportException("$name doesn't look like a valid zip (${e.message ?: e::class.java.simpleName}).")
        }
        if (tree.isEmpty()) throw ImportException("$name is empty.")

        // Text-like contents, most informative first.
        val sections = mutableListOf<Pair<String, String>>()
        val editable = linkedMapOf<String, String>() // plain text files the Editor may rewrite
        var editableChars = 0
        val images = mutableListOf<Attachment>()
        for (f in files.sortedWith(compareBy<ZipFile>({ priority(it.path) }, { it.path.count { c -> c == '/' } }, { it.bytes.size }))) {
            val fileName = f.path.substringAfterLast('/')
            val ext = fileName.substringAfterLast('.', "").lowercase()
            when {
                ext in IMAGE_EXT -> if (images.size < MAX_ZIP_IMAGES && !f.truncated) {
                    runCatching { decodeImage(ImageDecoder.createSource(java.nio.ByteBuffer.wrap(f.bytes))) }.getOrNull()
                        ?.let { images += Attachment("$name › ${f.path}", Attachment.Kind.IMAGE, image = it, note = "from zip") }
                }
                ext == "pdf" -> runCatching {
                    if (!pdfBoxReady) { PDFBoxResourceLoader.init(app); pdfBoxReady = true }
                    PDDocument.load(f.bytes).use { doc ->
                        PDFTextStripper().apply { startPage = 1; endPage = 20 }.getText(doc)
                    }
                }.getOrNull()?.takeIf { it.isNotBlank() }?.let { sections += f.path to it }
                ext == "docx" -> runCatching { docxText(f.bytes.inputStream()) }.getOrNull()
                    ?.takeIf { it.isNotBlank() }?.let { sections += f.path to it }
                else -> {
                    val head = f.bytes.take(4096)
                    if (head.count { it == 0.toByte() } > 4) { skippedBinary++; continue }
                    val text = String(f.bytes, Charsets.UTF_8)
                    sections += f.path to (if (f.truncated) "$text\n[… file cut off]" else text)
                    if (!f.truncated && editableChars + text.length <= MAX_EDITABLE_CHARS && !text.contains('\uFFFD')) {
                        editable[f.path] = text
                        editableChars += text.length
                    }
                }
            }
        }

        val body = buildString {
            append("Zip archive \"$name\": ${tree.size} files")
            if (skippedJunk > 0) append(", $skippedJunk build/system files skipped")
            if (stoppedEarly) append(", archive too big — only the first part was read")
            append("\n\nFile tree:\n")
            tree.sorted().take(MAX_ZIP_TREE_LINES).forEach { append("  ").append(it).append('\n') }
            if (tree.size > MAX_ZIP_TREE_LINES) append("  … and ${tree.size - MAX_ZIP_TREE_LINES} more\n")
            for ((path, text) in sections) {
                if (length > MAX_TEXT_CHARS) break
                append("\n===== ").append(path).append(" =====\n")
                append(text.replace("\u0000", "").trim().take(MAX_TEXT_CHARS - length.coerceAtMost(MAX_TEXT_CHARS)))
                append('\n')
            }
            val omitted = sections.size - sections.count { (p, _) -> contains("===== $p =====") }
            if (omitted > 0) append("\n[$omitted more files not included — archive text limit reached]\n")
        }
        val textPart = Attachment(
            name, Attachment.Kind.TEXT, text = body.take(MAX_TEXT_CHARS),
            note = "zip · ${tree.size} files" + if (images.isNotEmpty()) " · ${images.size} images" else "",
            archive = ArchiveInfo(archiveId, name, editable, tree.sorted()),
        )
        return listOf(textPart) + images
    }

    /** Reads a zip the app already has (an Editor result, or a kept copy) as a new attachment. */
    suspend fun importZipFile(file: java.io.File, name: String): List<Attachment> = withContext(Dispatchers.IO) {
        if (!file.exists()) throw ImportException("$name is no longer on this phone.")
        readZip(Uri.fromFile(file), name)
    }

    /** Private copy of an attached zip, used to rebuild it with the Editor's changes. */
    fun archiveFile(id: String): java.io.File = java.io.File(app.filesDir, "crew/archives/$id.zip")

    /** Keep only the most recent archive copies. */
    private fun pruneArchives() = runCatching {
        java.io.File(app.filesDir, "crew/archives").listFiles().orEmpty()
            .sortedByDescending { it.lastModified() }
            .drop(MAX_KEPT_ARCHIVES)
            .forEach { it.delete() }
    }

    private fun readCapped(input: java.io.InputStream, cap: Int): Pair<ByteArray, Boolean> {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(32 * 1024)
        while (out.size() < cap) {
            val n = input.read(buf, 0, minOf(buf.size, cap - out.size()))
            if (n < 0) return out.toByteArray() to false
            out.write(buf, 0, n)
        }
        // Hit the cap: is there more?
        val more = input.read() >= 0
        return out.toByteArray() to more
    }

    private fun isJunk(path: String): Boolean {
        val parts = path.lowercase().split('/')
        val file = parts.last()
        return parts.dropLast(1).any { it in JUNK_DIRS } || file == ".ds_store" || file == "thumbs.db" || file.startsWith("._")
    }

    /** Lower = read first: READMEs, then docs and project configs, then everything else. */
    private fun priority(path: String): Int {
        val file = path.substringAfterLast('/').lowercase()
        return when {
            file.startsWith("readme") -> 0
            file in PROJECT_FILES -> 1
            file.endsWith(".md") || file.endsWith(".txt") || file.endsWith(".pdf") || file.endsWith(".docx") -> 2
            else -> 3
        }
    }

    private fun readPdf(uri: Uri, name: String): List<Attachment> {
        val text = runCatching {
            if (!pdfBoxReady) { PDFBoxResourceLoader.init(app); pdfBoxReady = true }
            resolver.openInputStream(uri)?.use { input ->
                PDDocument.load(input).use { doc ->
                    PDFTextStripper().apply { startPage = 1; endPage = MAX_PDF_PAGES }.getText(doc)
                }
            }.orEmpty()
        }.getOrDefault("")

        if (text.trim().length >= 80) {
            return listOf(textAttachment(name, text).let {
                if (it.note.isBlank()) it.copy(note = "PDF") else it
            })
        }
        // Little or no text → probably a scan. Render the first pages for Gemini Nano to look at.
        val pages = renderPdfPages(uri)
        if (pages.isEmpty()) throw ImportException("Couldn't read $name.")
        return pages.mapIndexed { i, bmp ->
            Attachment("$name · page ${i + 1}", Attachment.Kind.IMAGE, image = bmp, note = "scanned page")
        }
    }

    private fun renderPdfPages(uri: Uri): List<Bitmap> = runCatching {
        resolver.openFileDescriptor(uri, "r")?.use { fd ->
            PdfRenderer(fd).use { renderer ->
                (0 until minOf(renderer.pageCount, MAX_SCANNED_PAGES)).map { i ->
                    renderer.openPage(i).use { page ->
                        val scale = MAX_IMAGE_SIDE.toFloat() / max(page.width, page.height)
                        val bmp = Bitmap.createBitmap(
                            (page.width * scale).toInt().coerceAtLeast(1),
                            (page.height * scale).toInt().coerceAtLeast(1),
                            Bitmap.Config.ARGB_8888,
                        )
                        Canvas(bmp).drawColor(Color.WHITE)
                        page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        bmp
                    }
                }
            }
        }.orEmpty()
    }.getOrDefault(emptyList())

    private companion object {
        const val MAX_BYTES = 30L * 1024 * 1024
        const val MAX_TEXT_CHARS = 200_000
        const val MAX_PDF_PAGES = 60
        const val MAX_SCANNED_PAGES = 3
        const val MAX_IMAGE_SIDE = 1024
        const val DOCX_MIME = "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        val IMAGE_EXT = setOf("jpg", "jpeg", "png", "webp", "heic", "heif", "gif", "bmp")

        val ZIP_MIMES = setOf("application/zip", "application/x-zip-compressed", "application/x-zip")
        val UNSUPPORTED_ARCHIVES = setOf("rar", "7z", "tar", "gz", "tgz", "bz2", "xz")
        const val MAX_ZIP_ENTRIES = 5_000
        const val MAX_ZIP_TOTAL_BYTES = 60L * 1024 * 1024
        const val MAX_ZIP_ENTRY_TEXT = 512 * 1024
        const val MAX_ZIP_ENTRY_BINARY = 12 * 1024 * 1024
        const val MAX_ZIP_IMAGES = 3
        const val MAX_EDITABLE_CHARS = 3_000_000
        const val MAX_KEPT_ARCHIVES = 12
        const val MAX_ZIP_TREE_LINES = 250
        val JUNK_DIRS = setOf(
            "__macosx", ".git", "node_modules", "build", "dist", "out", "target", ".gradle", ".idea", ".vscode",
            "__pycache__", "venv", ".venv", "env", "pods", ".next", ".nuxt", "coverage", ".cache", "vendor", "bin", "obj",
        )
        val TEXT_EXT = setOf(
            "txt", "md", "markdown", "rst", "csv", "tsv", "json", "jsonl", "xml", "yaml", "yml", "toml", "ini", "cfg",
            "conf", "properties", "gradle", "kts", "kt", "java", "py", "js", "mjs", "cjs", "ts", "tsx", "jsx", "html", "htm",
            "css", "scss", "sass", "less", "c", "h", "cpp", "hpp", "cc", "cs", "go", "rs", "rb", "php", "swift", "m", "mm",
            "sh", "bash", "zsh", "ps1", "bat", "sql", "r", "lua", "dart", "scala", "clj", "ex", "exs", "erl", "hs", "vue",
            "svelte", "svg", "tex", "log", "env", "gitignore", "dockerignore", "srt", "vtt", "ipynb", "proto", "graphql", "tf",
        )
        val TEXT_NAMES = setOf("dockerfile", "makefile", "license", "readme", "procfile", "gemfile", "podfile")
        val PROJECT_FILES = setOf(
            "package.json", "build.gradle", "build.gradle.kts", "settings.gradle.kts", "pyproject.toml", "requirements.txt",
            "cargo.toml", "go.mod", "pom.xml", "dockerfile", "docker-compose.yml", "androidmanifest.xml", "manifest.json",
            "composer.json", "gemfile", "pubspec.yaml", "tsconfig.json", "vite.config.ts", "makefile",
        )
    }
}
