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
        /** Blocks whose change is already in the file (an earlier part made it): neither applied nor failed. */
        val alreadyDone: Int = 0,
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
        var done = 0
        val failed = mutableListOf<String>()
        val diff = StringBuilder()
        for ((i, b) in blocks.withIndex()) {
            val n = i + 1
            val findLines = b.find.split("\n").dropWhile { it.isBlank() }.dropLastWhile { it.isBlank() }
            if (findLines.isEmpty()) { failed += "change $n: nothing to find"; continue }
            if (findLines.size > maxFindLines) { failed += "change $n: too big (${findLines.size} lines)"; continue }
            if (b.find.trimEnd() == b.replace.trimEnd()) { failed += "change $n: REPLACE is the same as FIND, so the new lines are missing"; continue }
            val veto = check?.invoke(b)
            if (veto != null) { failed += "change $n: $veto"; continue }
            val lines = text.split("\n")
            val at = locate(lines, findLines)
            if (at.isEmpty()) {
                // Already made (by an earlier part, or the model repeating itself): not a failure.
                val replLines = b.replace.split("\n").dropWhile { it.isBlank() }.dropLastWhile { it.isBlank() }
                if (replLines.isNotEmpty() && locate(lines, replLines).size == 1) { done++; continue }
            }
            if (at.size != 1) {
                failed += "change $n: " + (if (at.isEmpty()) "the lines to change aren't in the file" else "those lines appear ${at.size} times")
                continue
            }
            val from = at.single()
            val oldLines = lines.subList(from, from + findLines.size)
            // Keep the file's indentation if the model shifted the whole block left or right.
            val shift = indent(oldLines.first { it.isNotBlank() }) - indent(findLines.first { it.isNotBlank() })
            val newLines = repair(b.replace.split("\n").dropWhile { it.isBlank() }.dropLastWhile { it.isBlank() }, findLines, oldLines)
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
        return Result(text, applied, failed, diff.toString().trimEnd(), changed, done)
    }

    /** Start lines where [find] matches: exactly, else ignoring indentation and trailing spaces. */
    private fun locate(lines: List<String>, find: List<String>): List<Int> {
        fun scan(eq: (String, String) -> Boolean): List<Int> =
            (0..lines.size - find.size).filter { i -> find.indices.all { j -> eq(lines[i + j], find[j]) } }
        val exact = scan { a, b -> a == b }
        if (exact.isNotEmpty()) return exact
        val trimmed = scan { a, b -> a.trim() == b.trim() }
        if (trimmed.isNotEmpty()) return trimmed
        return fuzzy(lines, find)
    }

    /**
     * Small models miscopy long FIND blocks (`windowsdo` for `windows_do`, a shortened path). Accept
     * a place where every line is close to the FIND line (and the whole block very close), but only
     * if exactly one place in the file fits. Needs at least 3 non-blank lines, so a short FIND can't
     * land somewhere it wasn't meant to.
     */
    private fun fuzzy(lines: List<String>, find: List<String>): List<Int> {
        if (find.count { it.isNotBlank() } < 3 || find.size > lines.size) return emptyList()
        val first = find.indexOfFirst { it.isNotBlank() }
        val hits = mutableListOf<Int>()
        for (i in 0..lines.size - find.size) {
            if (similarity(lines[i + first].trim(), find[first].trim()) < 0.75) continue
            var total = 0.0
            var ok = true
            for (j in find.indices) {
                val sim = similarity(lines[i + j].trim(), find[j].trim())
                if (sim < 0.7) { ok = false; break }
                total += sim
            }
            if (ok && total / find.size >= 0.88) hits += i
            if (hits.size > 1) return emptyList()
        }
        return hits
    }

    /** 1.0 for equal strings, falling with the edit distance relative to the longer one. */
    internal fun similarity(a: String, b: String): Double {
        if (a == b) return 1.0
        if (a.isEmpty() || b.isEmpty()) return 0.0
        if (kotlin.math.abs(a.length - b.length) > maxOf(a.length, b.length) / 3) return 0.0
        var prev = IntArray(b.length + 1) { it }
        var cur = IntArray(b.length + 1)
        for (x in 1..a.length) {
            cur[0] = x
            for (y in 1..b.length) {
                cur[y] = minOf(prev[y] + 1, cur[y - 1] + 1, prev[y - 1] + if (a[x - 1] == b[y - 1]) 0 else 1)
            }
            val t = prev; prev = cur; cur = t
        }
        return 1.0 - prev[b.length].toDouble() / maxOf(a.length, b.length)
    }

    private val word = Regex("""[A-Za-z0-9_./-]+""")

    /**
     * After a fuzzy match, the model's REPLACE carries the same typos as its FIND. Lines it left
     * unchanged go back to the file's own text, and in changed lines the miscopied words are
     * swapped for the file's (`windowsdo` → `windows_do`).
     */
    private fun repair(replace: List<String>, find: List<String>, file: List<String>): List<String> {
        if (find == file) return replace
        val sameLine = find.indices.associate { find[it].trim() to file[it] }
        val words = mutableMapOf<String, String>()
        for (j in find.indices) {
            val f = word.findAll(find[j]).map { it.value }.toList()
            val o = word.findAll(file[j]).map { it.value }.toList()
            if (f.size == o.size) f.zip(o).forEach { (a, b) -> if (a != b && similarity(a, b) >= 0.6) words[a] = b }
        }
        return replace.map { r ->
            sameLine[r.trim()]?.let { orig -> return@map " ".repeat(indent(r)) + orig.trimStart() }
            if (words.isEmpty()) r else word.replace(r) { m -> words[m.value] ?: m.value }
        }
    }

    private fun indent(s: String) = s.length - s.trimStart().length

    /** Rejected blocks as "- find / + replace" with the reason, for the answer and later follow-ups. */
    fun describeRejected(blocks: List<Block>, failed: List<String>, maxLines: Int = 8): String = buildString {
        for ((i, b) in blocks.withIndex()) {
            val why = failed.firstOrNull { it.startsWith("change ${i + 1}:") }?.substringAfter(": ") ?: continue
            append("@@ rejected: ").append(why).append('\n')
            b.find.split("\n").filter { it.isNotBlank() }.take(maxLines).forEach { append("- ").append(it).append('\n') }
            b.replace.split("\n").filter { it.isNotBlank() }.take(maxLines).forEach { append("+ ").append(it).append('\n') }
        }
    }

    /** A new file shown as all-added lines. */
    fun created(content: String): String =
        "@@ new file\n" + content.split("\n").take(80).joinToString("\n") { "+ $it" } +
            if (content.split("\n").size > 80) "\n+ … (${content.split("\n").size - 80} more lines)" else ""
}
