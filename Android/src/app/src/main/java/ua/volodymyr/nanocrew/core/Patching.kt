package ua.volodymyr.nanocrew.core

/**
 * Small, checkable edits instead of retyping a file. The Editor answers with blocks
 *
 *     <<<<<<< FIND
 *     the exact lines to change, copied from the file
 *     =======
 *     the new lines
 *     >>>>>>> REPLACE
 *
 * and only those lines change; everything else stays byte for byte. A block whose FIND text isn't
 * in the file exactly once (ignoring indentation) is rejected rather than guessed.
 */
object Patching {

    data class Block(val find: String, val replace: String)

    data class Result(
        val text: String,
        val applied: Int,
        /** Why blocks were rejected, one line each. */
        val failed: List<String>,
        /** "@@ line N" headers with "- old" / "+ new" lines, for the review. */
        val diff: String,
        /** Lines removed plus lines added (after dropping lines that stayed the same). */
        val changedLines: Int,
    )

    private val start = Regex("""^\s*<{5,}\s*(FIND|SEARCH|ORIGINAL|OLD)?\s*$""", RegexOption.IGNORE_CASE)
    private val middle = Regex("""^\s*={5,}\s*$""")
    private val end = Regex("""^\s*>{5,}\s*(REPLACE|NEW|UPDATED)?\s*$""", RegexOption.IGNORE_CASE)

    fun parse(reply: String): List<Block> {
        val blocks = mutableListOf<Block>()
        var find: MutableList<String>? = null
        var repl: MutableList<String>? = null
        for (raw in reply.replace("\r\n", "\n").split("\n")) {
            when {
                start.matches(raw) -> { find = mutableListOf(); repl = null }
                middle.matches(raw) && find != null && repl == null -> repl = mutableListOf()
                end.matches(raw) && find != null && repl != null -> {
                    blocks += Block(find.joinToString("\n"), repl.joinToString("\n"))
                    find = null; repl = null
                }
                repl != null -> repl.add(raw)
                find != null -> find.add(raw)
            }
        }
        return blocks
    }

    /**
     * Applies [blocks] to [original]. [check] can veto a block before it is applied (it returns why,
     * or null to allow it); see [EditGuard.checkBlock].
     */
    fun apply(original: String, blocks: List<Block>, maxFindLines: Int = 40, check: ((Block) -> String?)? = null): Result {
        var text = original
        var applied = 0
        var changed = 0
        val failed = mutableListOf<String>()
        val diff = StringBuilder()
        for ((i, b) in blocks.withIndex()) {
            val n = i + 1
            val findLines = b.find.split("\n").dropWhile { it.isBlank() }.dropLastWhile { it.isBlank() }
            if (findLines.isEmpty()) { failed += "change $n: nothing to find"; continue }
            if (findLines.size > maxFindLines) { failed += "change $n: too big (${findLines.size} lines)"; continue }
            if (b.find.trimEnd() == b.replace.trimEnd()) { failed += "change $n: changes nothing"; continue }
            val veto = check?.invoke(b)
            if (veto != null) { failed += "change $n: $veto"; continue }
            val lines = text.split("\n")
            val at = locate(lines, findLines)
            if (at.size != 1) {
                failed += "change $n: " + (if (at.isEmpty()) "the lines to change aren't in the file" else "those lines appear ${at.size} times")
                continue
            }
            val from = at.single()
            val oldLines = lines.subList(from, from + findLines.size)
            // Keep the file's indentation if the model shifted the whole block left or right.
            val shift = indent(oldLines.first { it.isNotBlank() }) - indent(findLines.first { it.isNotBlank() })
            val newLines = b.replace.split("\n").dropWhile { it.isBlank() }.dropLastWhile { it.isBlank() }
                .map { l -> if (l.isBlank()) l else if (shift > 0) " ".repeat(shift) + l else l.drop(minOf(-shift, indent(l)).coerceAtLeast(0)) }
            text = (lines.subList(0, from) + newLines + lines.subList(from + findLines.size, lines.size)).joinToString("\n")
            applied++
            // Only the lines that really differ go into the diff.
            var p = 0
            while (p < oldLines.size && p < newLines.size && oldLines[p] == newLines[p]) p++
            var s = 0
            while (s < oldLines.size - p && s < newLines.size - p && oldLines[oldLines.size - 1 - s] == newLines[newLines.size - 1 - s]) s++
            val removed = oldLines.subList(p, oldLines.size - s)
            val added = newLines.subList(p, newLines.size - s)
            changed += removed.size + added.size
            diff.append("@@ line ").append(from + p + 1).append('\n')
            removed.forEach { diff.append("- ").append(it).append('\n') }
            added.forEach { diff.append("+ ").append(it).append('\n') }
        }
        return Result(text, applied, failed, diff.toString().trimEnd(), changed)
    }

    /** Start lines where [find] matches: exactly, else ignoring indentation and trailing spaces. */
    private fun locate(lines: List<String>, find: List<String>): List<Int> {
        fun scan(eq: (String, String) -> Boolean): List<Int> =
            (0..lines.size - find.size).filter { i -> find.indices.all { j -> eq(lines[i + j], find[j]) } }
        val exact = scan { a, b -> a == b }
        if (exact.isNotEmpty()) return exact
        return scan { a, b -> a.trim() == b.trim() }
    }

    private fun indent(s: String) = s.length - s.trimStart().length

    /** A new file shown as all-added lines. */
    fun created(content: String): String =
        "@@ new file\n" + content.split("\n").take(80).joinToString("\n") { "+ $it" } +
            if (content.split("\n").size > 80) "\n+ … (${content.split("\n").size - 80} more lines)" else ""
}
