package ua.volodymyr.nanocrew.core

/** A zip the user attached, with the text files the Editor is allowed to change. */
data class ArchiveInfo(
    /** Key of the cached copy of the original zip on the phone. */
    val id: String,
    val name: String,
    /** path → full text, for readable text files. */
    val files: Map<String, String>,
    /** Every file path in the archive (for the planner). */
    val allPaths: List<String>,
)

data class FileEdit(
    val path: String,
    val action: Action,
    val instruction: String,
    val status: Status,
    /** New full file content (empty unless DONE). */
    val content: String = "",
    /** Human-readable detail: what changed, or why it was skipped. */
    val detail: String = "",
) {
    enum class Action { EDIT, CREATE, DELETE }
    enum class Status { DONE, SKIPPED }
}

/** One slice of a file: lines [startLine, endLine) (0-based), text without trailing newline. */
data class Chunk(val index: Int, val startLine: Int, val endLine: Int, val text: String)

object Chunker {
    val TARGET_LINES get() = Budget.EDIT_CHUNK_LINES
    val MAX_CHARS get() = Budget.EDIT_CHUNK_CHARS

    /**
     * Splits a file into chunks of ~[TARGET_LINES] lines / at most [MAX_CHARS] chars, preferring
     * to cut before a top-level declaration that follows a blank line, then at any blank line.
     */
    fun split(text: String, targetLines: Int = TARGET_LINES, maxChars: Int = MAX_CHARS): List<Chunk> {
        val lines = text.split("\n")
        val chunks = mutableListOf<Chunk>()
        var start = 0
        while (start < lines.size) {
            var end = start
            var chars = 0
            while (end < lines.size && end - start < targetLines && chars + lines[end].length + 1 <= maxChars) {
                chars += lines[end].length + 1
                end++
            }
            if (end == start) end = start + 1 // one giant line
            if (end < lines.size) end = betterCut(lines, start, end)
            chunks += Chunk(chunks.size, start, end, lines.subList(start, end).joinToString("\n"))
            start = end
        }
        return chunks
    }

    private fun betterCut(lines: List<String>, start: Int, end: Int): Int {
        val minEnd = start + (end - start) / 2
        // 1) a blank line followed by an unindented, non-closing line (likely a new top-level declaration)
        for (i in end downTo minEnd + 1) {
            val prev = lines[i - 1]
            val cur = lines.getOrNull(i) ?: continue
            if (prev.isBlank() && cur.isNotBlank() && !cur[0].isWhitespace() && !cur.trimStart().startsWith("}") &&
                !cur.trimStart().startsWith(")")
            ) return i
        }
        // 2) any blank line
        for (i in end downTo minEnd + 1) if (lines[i - 1].isBlank()) return i
        return end
    }

    fun join(chunks: List<String>): String = chunks.joinToString("\n")
}

object EditParsing {
    private val planLine = Regex(
        """^[\s>*`\-•\d.)]*\**(EDIT|CHANGE|MODIFY|UPDATE|FIX|CREATE|ADD|NEW|DELETE|REMOVE)\**\s+`?([^\s`:]+)`?\s*[:\-–—]\s*(.+)$""",
        RegexOption.IGNORE_CASE,
    )

    data class PlannedEdit(val action: FileEdit.Action, val path: String, val instruction: String)

    fun parsePlan(reply: String, archive: ArchiveInfo, max: Int): List<PlannedEdit> =
        reply.lines().mapNotNull { line ->
            val m = planLine.matchEntire(line.trim()) ?: return@mapNotNull null
            val verb = m.groupValues[1].uppercase()
            val rawPath = m.groupValues[2].trim().trim('"', '\'', '`').removePrefix("./").trimStart('/')
            val instruction = m.groupValues[3].trim()
            if (rawPath.contains("..") || rawPath.isBlank()) return@mapNotNull null
            if (verb == "DELETE" || verb == "REMOVE") {
                // Any file can be removed, binaries too — but only one that is really in the zip.
                return@mapNotNull resolveAny(rawPath, archive)?.let { PlannedEdit(FileEdit.Action.DELETE, it, instruction) }
            }
            val existing = resolve(rawPath, archive)
            when {
                existing != null -> PlannedEdit(FileEdit.Action.EDIT, existing, instruction)
                verb in setOf("CREATE", "ADD", "NEW") -> PlannedEdit(FileEdit.Action.CREATE, rawPath, instruction)
                // Asked to edit a file that isn't there: create it only if it has a sensible extension.
                rawPath.contains('.') && rawPath !in archive.allPaths -> PlannedEdit(FileEdit.Action.CREATE, rawPath, instruction)
                else -> null
            }
        }.distinctBy { it.path }.take(max.coerceAtLeast(1))

    /** Like [resolve], over every file in the zip (for deletions). */
    fun resolveAny(path: String, archive: ArchiveInfo): String? {
        val all = archive.allPaths
        if (path in all) return path
        all.firstOrNull { it.equals(path, ignoreCase = true) }?.let { return it }
        all.filter { it.substringAfterLast('/').equals(path.substringAfterLast('/'), ignoreCase = true) }.singleOrNull()?.let { return it }
        return all.filter { it.endsWith("/$path", ignoreCase = true) }.singleOrNull()
    }

    /** Exact path, then case-insensitive, then a unique file-name match. Only text files are editable. */
    fun resolve(path: String, archive: ArchiveInfo): String? {
        if (path in archive.files) return path
        archive.files.keys.firstOrNull { it.equals(path, ignoreCase = true) }?.let { return it }
        val byName = archive.files.keys.filter { it.substringAfterLast('/').equals(path.substringAfterLast('/'), ignoreCase = true) }
        if (byName.size == 1) return byName.first()
        val bySuffix = archive.files.keys.filter { it.endsWith("/$path", ignoreCase = true) }
        return bySuffix.singleOrNull()
    }

    private val fence = Regex("""```[^\n`]*\n(.*?)\n?```""", RegexOption.DOT_MATCHES_ALL)

    /** The model's rewritten text: content of the last complete code block, or of an unclosed one, or the whole reply. */
    fun extractCode(reply: String): String {
        fence.findAll(reply).lastOrNull()?.let { return it.groupValues[1] }
        val open = reply.indexOf("```")
        if (open >= 0) {
            val afterLang = reply.indexOf('\n', open)
            if (afterLang > 0) return reply.substring(afterLang + 1).trimEnd()
        }
        return reply.trim()
    }

    /** Signature-like lines (functions, classes, headings) so each chunk knows the rest of the file. */
    fun outline(text: String, maxChars: Int = 1_200): String {
        val sig = Regex(
            """^\s{0,4}(?:(?:export|public|private|protected|internal|static|async|abstract|open|override|suspend|data|sealed|final|const)\s+)*""" +
                """(?:fun|def|class|interface|object|function|struct|enum|impl|trait|type|module|func|fn|val|var|let|const)\s+\w+.*|^#{1,3}\s+.+""",
        )
        val lines = text.split("\n").mapIndexedNotNull { i, l ->
            if (sig.matches(l)) "${i + 1}: ${l.trim().take(100)}" else null
        }
        return clip(lines.joinToString("\n"), maxChars)
    }

    private val stop = setOf(
        "the", "and", "for", "with", "that", "this", "from", "into", "file", "files", "code", "make", "add", "change",
        "fix", "update", "edit", "remove", "should", "would", "please", "them", "then", "each", "every", "also", "all",
        "function", "method", "class", "line", "lines", "comment", "comments", "text", "new", "use", "using",
    )

    /** Words in the instruction that could locate the right chunk without a model call. */
    fun keywords(instruction: String): List<String> {
        val quoted = Regex("""[`"']([^`"']{3,40})[`"']""").findAll(instruction).map { it.groupValues[1] }.toList()
        val words = Regex("""[A-Za-z_][A-Za-z0-9_]{3,}""").findAll(instruction).map { it.value }
            .filter { it.lowercase() !in stop }
        val numbers = Regex("""\b\d{2,}\b""").findAll(instruction).map { it.value }
        return (quoted + words + numbers).distinct()
    }
}
