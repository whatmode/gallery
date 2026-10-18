package ua.volodymyr.nanocrew.core

/**
 * Checks that stop a small model (Gemini Nano through AICore, or Gemma) from shipping a change
 * that only looks right. Everything here is plain text work — no model calls, so it costs nothing
 * against the AICore quota.
 *
 *  - [includeShortcut]: "include <X>" / "import a.b.C" is done by the app itself, as an addition
 *    after the last existing include/import. The model never gets the chance to swap one out.
 *  - [checkBlock]: each FIND/REPLACE block must do what the instruction says. It may not only
 *    delete when asked to add, drop an include/import nobody asked to drop, or delete a
 *    class/struct/function declaration.
 *  - [checkFile]: the edited file must keep its brackets balanced the way the original was.
 */
object EditGuard {

    // ---------------------------------------------------------------- language detection

    private val includeLangs = setOf("c", "cc", "cpp", "cxx", "h", "hh", "hpp", "hxx", "ino", "m", "mm")
    private val importLangs = setOf("kt", "kts", "java", "scala", "groovy", "dart", "swift", "go", "py")
    private val braceLangs = includeLangs + setOf(
        "kt", "kts", "java", "scala", "groovy", "dart", "swift", "go", "rs", "js", "jsx", "mjs", "cjs",
        "ts", "tsx", "cs", "php", "css", "scss", "less", "json", "qml",
    )

    private fun ext(path: String) = path.substringAfterLast('/').substringAfterLast('.', "").lowercase()

    // ---------------------------------------------------------------- what the request asks for

    private val removeWords = Regex("""\b(remove|delete|drop|replace|rename|swap|move|get\s+rid|unused|clean\s*up)\b""", RegexOption.IGNORE_CASE)
    private val addWords = Regex("""\b(add|include|import|insert|append|introduce|declare|create|define|implement)\b""", RegexOption.IGNORE_CASE)

    /** `<QQuickItem>` / `"shell.h"` named in an instruction, as written after #include or "include". */
    private val includeTarget = Regex("""(?:#\s*include|\binclude)\s*([<"][^>"\s]+[>"])""", RegexOption.IGNORE_CASE)
    /** `import a.b.C` named in an instruction. */
    private val importTarget = Regex("""\bimport\s+((?:static\s+)?[A-Za-z_][\w.]*(?:\.\*)?)""")

    /** The include the instruction asks for, normalised to `<X>` / `"X"`, or null. */
    fun requestedInclude(instruction: String): String? = includeTarget.find(instruction)?.groupValues?.get(1)

    /** The import the instruction asks for, or null. */
    fun requestedImport(instruction: String): String? =
        importTarget.find(instruction)?.groupValues?.get(1)?.takeIf { it.contains('.') }

    // ---------------------------------------------------------------- 1) adds done by the app

    data class Shortcut(val text: String, val diff: String, val detail: String, val changed: Boolean)

    private val includeLine = Regex("""^\s*#\s*include\s*([<"][^>"]+[>"])""")
    private val importLine = Regex("""^\s*import\s+((?:static\s+)?[A-Za-z_][\w.]*(?:\.\*)?)\s*;?\s*$""")
    private val packageLine = Regex("""^\s*package\s+[\w.]+\s*;?\s*$""")

    /**
     * When the instruction only asks to add one include (C/C++) or import (Kotlin/Java/…), does it
     * deterministically: inserted after the last existing include/import, nothing removed.
     * Returns null when the instruction asks for more than that, so the Editor handles it.
     */
    fun includeShortcut(path: String, text: String, instruction: String): Shortcut? {
        if (removeWords.containsMatchIn(instruction)) return null
        // Only when adding the include is the whole job ("add #include <X>", "include <X> in foo.cpp").
        val rest = instruction
            .replace(includeTarget, " ")
            .replace(importTarget, " ")
            .replace(Regex("""\b(line\s+\d+|at\s+the\s+top|top\s+of\s+the\s+file|header|file|to|in|into|the|a|an|missing|needed|for|add|include|import|so\s+it\s+compiles|change|update|fix)\b""", RegexOption.IGNORE_CASE), " ")
            .replace(Regex("""[`'".,:;()\-—–]|\S+\.(?:cpp|cc|c|h|hpp|kt|java|py|go|swift|dart)\b"""), " ")
            .trim()
        if (rest.split(Regex("\\s+")).count { it.length > 2 } > 2) return null

        val e = ext(path)
        val lines = text.split("\n")
        val (wanted, newLine, matcher) = when {
            e in includeLangs -> {
                val inc = requestedInclude(instruction) ?: return null
                Triple(inc, "#include $inc", includeLine)
            }
            e in importLangs -> {
                val imp = requestedImport(instruction) ?: return null
                val semi = if (e == "java" || e == "groovy") ";" else ""
                val line = if (e == "py") {
                    // "import a.b.C" in Python means "from a.b import C" more often than not; leave it to the Editor.
                    return null
                } else "import $imp$semi"
                Triple(imp, line, importLine)
            }
            else -> return null
        }
        val existing = lines.withIndex().filter { matcher.find(it.value) != null }
        if (existing.any { matcher.find(it.value)!!.groupValues[1].trim() == wanted.trim() }) {
            return Shortcut(text, "", "already has $newLine", changed = false)
        }
        val at = when {
            existing.isNotEmpty() -> existing.last().index + 1
            e in includeLangs -> afterHeaderGuard(lines)
            else -> lines.indexOfFirst { packageLine.matches(it) }.let { if (it >= 0) it + 1 else 0 }
        }
        // The first import in a file is set off by blank lines from `package` and from the code.
        val insert = if (existing.isNotEmpty() || e in includeLangs) listOf(newLine) else buildList {
            if (at > 0) add("")
            add(newLine)
            if (lines.getOrNull(at)?.isNotBlank() == true) add("")
        }
        val out = lines.subList(0, at) + insert + lines.subList(at, lines.size)
        val firstAdded = at + insert.indexOf(newLine) + 1
        return Shortcut(
            text = out.joinToString("\n"),
            diff = "@@ line $firstAdded\n+ $newLine",
            detail = "added $newLine (done by the app, nothing else touched)",
            changed = true,
        )
    }

    /** First line after `#pragma once` / the `#ifndef X / #define X` guard / leading comments. */
    private fun afterHeaderGuard(lines: List<String>): Int {
        var i = 0
        var inBlock = false
        while (i < lines.size) {
            val t = lines[i].trim()
            when {
                inBlock -> { if (t.contains("*/")) inBlock = false }
                t.startsWith("/*") -> { if (!t.contains("*/")) inBlock = true }
                t.isEmpty() || t.startsWith("//") || t.startsWith("#pragma once") -> {}
                t.startsWith("#ifndef") && lines.getOrNull(i + 1)?.trim()?.startsWith("#define") == true -> i++
                else -> return i
            }
            i++
        }
        return lines.size
    }

    // ---------------------------------------------------------------- 2) each change block

    private val declaration = Regex(
        """^\s*(?:template\s*<.*>\s*)?(?:(?:export|public|private|protected|internal|static|abstract|open|sealed|data|final|inline|virtual|override|suspend|async|enum)\s+)*""" +
            """(?:class|struct|interface|object|enum|union|namespace|fun|def|func|fn|function|trait|impl|module)\b\s*\w+""",
    )

    /** Why [find] → [replace] doesn't fit [instruction] for [path], or null if it does. */
    fun checkBlock(path: String, find: String, replace: String, instruction: String): String? {
        val old = find.split("\n").map { it.trimEnd() }
        val new = replace.split("\n").map { it.trimEnd() }
        val oldReal = old.filter { it.isNotBlank() }
        val newReal = new.filter { it.isNotBlank() }
        val removed = oldReal.filter { o -> newReal.none { it.trim() == o.trim() } }
        val added = newReal.filter { n -> oldReal.none { it.trim() == n.trim() } }
        val asksRemove = removeWords.containsMatchIn(instruction)
        val asksAdd = addWords.containsMatchIn(instruction)

        // Asked to add something, got only deletions.
        if (asksAdd && !asksRemove && added.isEmpty() && removed.isNotEmpty()) {
            return "it only deletes lines, but the request is to add something"
        }
        // Asked for a specific include/import that the block doesn't write.
        requestedInclude(instruction)?.let { inc ->
            if (ext(path) in includeLangs && !asksRemove && added.none { a -> includeLine.find(a)?.groupValues?.get(1) == inc } &&
                removed.any { includeLine.containsMatchIn(it) }
            ) return "it touches the includes but doesn't add #include $inc"
        }
        // Includes/imports are only ever added unless the request says to remove or replace one.
        if (!asksRemove) {
            removed.firstOrNull { includeLine.containsMatchIn(it) || importLine.matches(it) }?.let {
                return "it removes `${it.trim()}`, which the request didn't ask for"
            }
        }
        // A declaration disappearing (not renamed in place) breaks everything that uses it.
        removed.filter { declaration.containsMatchIn(it) }.forEach { d ->
            val name = declaredName(d)
            val stillThere = name != null && added.any { declaredName(it) == name }
            if (!stillThere && !asksRemove) return "it deletes the declaration `${d.trim().take(60)}`"
        }
        return null
    }

    private fun declaredName(line: String): String? =
        Regex("""\b(?:class|struct|interface|object|enum|union|namespace|fun|def|func|fn|function|trait|impl|module)\s+(\w+)""")
            .find(line)?.groupValues?.get(1)

    // ---------------------------------------------------------------- 3) the whole file

    /** Why the edited file is broken in a way the original wasn't, or null. */
    fun checkFile(path: String, original: String, edited: String): String? {
        if (ext(path) !in braceLangs) return null
        val before = balance(original, ext(path))
        val after = balance(edited, ext(path))
        val pairs = listOf("{}" to 0, "()" to 1, "[]" to 2)
        for ((name, i) in pairs) {
            if (before[i] != after[i]) {
                val d = after[i] - before[i]
                return "it leaves ${kotlin.math.abs(d)} ${if (d > 0) "unclosed '${name[0]}'" else "extra '${name[1]}'"} in the file"
            }
        }
        return null
    }

    /** Open-minus-close counts for {} () [], skipping strings, chars and comments. */
    internal fun balance(text: String, ext: String = "c"): IntArray {
        val out = IntArray(3)
        val hashComments = ext == "py"
        var i = 0
        val n = text.length
        while (i < n) {
            val c = text[i]
            val next = if (i + 1 < n) text[i + 1] else '\u0000'
            when {
                c == '/' && next == '/' -> { while (i < n && text[i] != '\n') i++; continue }
                c == '/' && next == '*' -> { i += 2; while (i + 1 < n && !(text[i] == '*' && text[i + 1] == '/')) i++; i += 2; continue }
                hashComments && c == '#' -> { while (i < n && text[i] != '\n') i++; continue }
                c == '"' || c == '\'' || c == '`' -> {
                    // A lone apostrophe in prose/comments-free code is rare; strings end at the line for ' and ".
                    val q = c
                    i++
                    while (i < n && text[i] != q) {
                        if (text[i] == '\\') i++
                        else if (text[i] == '\n' && q != '`') break
                        i++
                    }
                    i++
                    continue
                }
                c == '{' -> out[0]++
                c == '}' -> out[0]--
                c == '(' -> out[1]++
                c == ')' -> out[1]--
                c == '[' -> out[2]++
                c == ']' -> out[2]--
            }
            i++
        }
        return out
    }
}
