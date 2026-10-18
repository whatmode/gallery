package ua.volodymyr.nanocrew.core

/**
 * "Look it up in another file", without a model call. A small model sees one part of one file;
 * when that part calls `kwm.minimize(wid)` or runs `kwm.py act min`, the code that actually
 * decides what happens lives elsewhere. This finds those definitions in the zip so the agent sees
 * them next to the part it is working on.
 */
object CrossRef {

    data class Def(val path: String, val line: Int, val name: String, val text: String)

    /** Too common to be worth looking up. */
    private val COMMON = setOf(
        "print", "len", "str", "int", "float", "bool", "dict", "list", "set", "tuple", "range", "open", "self", "super",
        "isinstance", "getattr", "setattr", "hasattr", "get", "append", "extend", "join", "split", "strip", "format",
        "items", "keys", "values", "update", "pop", "exists", "path", "loads", "dumps", "encode", "decode", "write",
        "read", "replace", "lower", "upper", "startswith", "endswith", "sorted", "enumerate", "zip", "map", "filter",
        "min", "max", "sum", "any", "all", "abs", "round", "type", "repr", "next", "iter", "sleep", "time", "run",
        "if", "for", "while", "switch", "return", "catch", "function", "console", "log", "push", "then", "parse",
        "stringify", "querySelector", "addEventListener", "setTimeout", "toString", "length", "main", "init", "Popen",
        "check_output", "communicate", "insert", "remove", "copy", "flush", "wait", "send", "recv", "connect",
    )

    private val callName = Regex("""\b([A-Za-z_][A-Za-z0-9_]{2,})\s*\(""")
    private val attrName = Regex("""\b[A-Za-z_][A-Za-z0-9_]*\.([A-Za-z_][A-Za-z0-9_]{2,})\b""")
    private val scriptRef = Regex("""(?<![\w.])([\w-]+\.(?:py|sh|js|mjs|qml|lua|pl|rb))\b""")

    /** Names [part] uses that might be defined somewhere else: module.name references first (in order), then plain calls. */
    fun usedNames(part: String): List<String> =
        (attrName.findAll(part).map { it.groupValues[1] } + callName.findAll(part).map { it.groupValues[1] })
            .filter { it !in COMMON && !it.all { c -> c.isUpperCase() || c == '_' } }
            .distinct().toList()

    private fun defPatterns(name: String): List<Regex> {
        val n = Regex.escape(name)
        return listOf(
            Regex("""^\s*(?:async\s+)?def\s+$n\s*\("""),                                  // Python
            Regex("""^\s*(?:export\s+)?(?:async\s+)?function\s+$n\s*\("""),               // JS
            Regex("""^\s*(?:(?:export|const|let|var)\s+)+$n\s*=\s*(?:async\s*)?(?:function\b|\([^)]*\)\s*=>|\w+\s*=>)"""),
            Regex("""^\s*(?:(?:private|public|internal|override|suspend|open|inline)\s+)*fun\s+(?:<[^>]+>\s*)?(?:\w+\.)?$n\s*\("""),
            Regex("""^\s*(?:function\s+)?$n\s*\(\)\s*\{"""),                                // shell
            Regex("""^\s*(?:[\w:<>]+[\s*&]+)+(?:\w+::)?$n\s*\([^;]*\)\s*(?:const\s*)?\{?\s*$"""), // C/C++: a type, then the name
            Regex("""^\s*$n\s*:\s*(?:async\s*)?(?:function\b|\([^)]*\)\s*=>)"""),           // JS object member
        )
    }

    /** The definition of [name] in [files] (skipping [exceptPath] lines inside [exceptRange]). */
    fun find(name: String, files: Map<String, String>, maxPerName: Int = 2): List<Def> {
        val pats = defPatterns(name)
        val out = mutableListOf<Def>()
        for ((p, text) in files) {
            val lines = text.split("\n")
            for ((i, l) in lines.withIndex()) {
                if (l.length > 300 || pats.none { it.containsMatchIn(l) }) continue
                // C/C++ pattern also matches calls like "  return foo(x)" — require a type-ish start.
                if (l.trimStart().startsWith("return ") || l.trimStart().startsWith("if ") || l.trimStart().startsWith("else")) continue
                out += Def(p, i + 1, name, body(lines, i))
                if (out.size >= maxPerName) return out
            }
        }
        return out
    }

    /** The definition line and its body: indented lines (Python) or up to the closing brace, at most 18 lines. */
    private fun body(lines: List<String>, at: Int, maxLines: Int = 18): String {
        val first = lines[at]
        val indent = first.length - first.trimStart().length
        val out = mutableListOf(first)
        var depth = first.count { it == '{' } - first.count { it == '}' }
        val braces = first.contains('{') || lines.getOrNull(at + 1)?.trim() == "{"
        var i = at + 1
        while (i < lines.size && out.size < maxLines) {
            val l = lines[i]
            if (braces) {
                out += l
                depth += l.count { it == '{' } - l.count { it == '}' }
                if (depth <= 0 && l.contains('}')) break
            } else {
                if (l.isNotBlank() && l.length - l.trimStart().length <= indent) break
                out += l
            }
            i++
        }
        if (out.size >= maxLines) out += "    …"
        return out.joinToString("\n").trimEnd()
    }

    /**
     * A part runs another script of the zip with arguments (`kwm.py act min`): the code in that
     * script that handles those arguments — the functions whose body checks for them.
     */
    fun scriptHandlers(part: String, files: Map<String, String>, path: String): List<Def> {
        val out = mutableListOf<Def>()
        for (line in part.split("\n")) {
            for (m in scriptRef.findAll(line)) {
                val name = m.groupValues[1]
                val target = files.keys.firstOrNull { it.substringAfterLast('/') == name && it != path } ?: continue
                val args = line.substring(m.range.last + 1).trim().split(Regex("\\s+"))
                    .map { it.trim('"', '\'', ';', ')') }.filter { Regex("""^[A-Za-z][\w-]*$""").matches(it) }.take(4)
                val lines = files.getValue(target).split("\n")
                for (a in args) {
                    val hit = lines.indexOfFirst { Regex("""["']${Regex.escape(a)}["']""").containsMatchIn(it) }
                    if (hit < 0) continue
                    // The function around that line: the nearest definition above it that is less
                    // indented. Top-level code (an argv dispatch) has none — show the lines around it.
                    val ind = { l: String -> l.length - l.trimStart().length }
                    val defLine = Regex("""^\s*(?:async\s+)?def\s+\w+|^\s*(?:function\s+)?\w+\s*\(\)\s*\{|^\s*(?:export\s+)?function\s+\w+""")
                    // The block the line sits in: the nearest less-indented line above. Only a def counts.
                    val parent = (hit - 1 downTo 0).firstOrNull { k -> lines[k].isNotBlank() && ind(lines[k]) < ind(lines[hit]) }
                    val start = parent?.takeIf { k -> defLine.containsMatchIn(lines[k]) }
                        ?: parent?.let { k -> (k - 1 downTo 0).firstOrNull { j -> defLine.containsMatchIn(lines[j]) && ind(lines[j]) < ind(lines[k]) } }
                    val d = if (start != null && hit - start < 60) Def(target, start + 1, a, body(lines, start, maxLines = 24))
                        else Def(target, maxOf(0, hit - 3) + 1, a, lines.subList(maxOf(0, hit - 3), minOf(lines.size, hit + 4)).joinToString("\n"))
                    if (out.none { it.path == d.path && it.line == d.line }) out += d
                }
            }
        }
        return out
    }

    /** Text block for a prompt: definitions from elsewhere that [part] (in [path]) depends on. */
    fun context(part: String, path: String, files: Map<String, String>, maxChars: Int, maxDefs: Int = 8): String {
        if (maxChars < 200) return ""
        val defs = mutableListOf<Def>()
        defs += scriptHandlers(part, files, path)
        val localLines = part.split("\n").map { it.trim() }.toSet()
        for (n in usedNames(part)) {
            if (defs.size >= maxDefs) break
            // Defined in this very part: nothing to look up.
            if (defPatterns(n).any { p -> part.split("\n").any { p.containsMatchIn(it) } }) continue
            find(n, files).filter { d -> d.text.lineSequence().first().trim() !in localLines }
                .forEach { d -> if (defs.none { it.path == d.path && it.line == d.line }) defs += d }
        }
        if (defs.isEmpty()) return ""
        val sb = StringBuilder("Code this part depends on, from elsewhere in the project:\n")
        for (d in defs.take(maxDefs)) {
            val block = "--- ${d.path.substringAfterLast('/')} line ${d.line} (${d.name}) ---\n${d.text}\n"
            if (sb.length + block.length > maxChars) break
            sb.append(block)
        }
        return sb.toString().trimEnd()
    }

    /** For an Editor's "LOOKUP: name": that name's definitions, or a note that it wasn't found. */
    fun lookup(name: String, files: Map<String, String>, maxChars: Int): String {
        val clean = name.trim().trim('`', '"', '\'', '(', ')').substringAfterLast('.')
        val defs = find(clean, files, maxPerName = 3)
        if (defs.isEmpty()) return "LOOKUP $clean: not defined anywhere in the project."
        return clip(defs.joinToString("\n") { "--- ${it.path.substringAfterLast('/')} line ${it.line} ---\n${it.text}" }, maxChars)
    }
}
