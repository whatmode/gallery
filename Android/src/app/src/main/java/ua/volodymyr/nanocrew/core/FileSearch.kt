package ua.volodymyr.nanocrew.core

import kotlin.math.ln

/**
 * Finds the files in a zip that a request is about, before any model call: the words of the
 * request are looked up in every file's path and text, rarer words count more, and the best files
 * come back with the lines that matched. A small model can't read 150 files, but it can pick well
 * from the few that actually mention "button" and "close".
 */
object FileSearch {

    data class Hit(val path: String, val score: Double, val matches: Int, val words: List<String>, val lines: List<String>)

    private val STOP = setOf(
        "the", "and", "for", "with", "that", "this", "these", "those", "from", "into", "onto", "then", "than",
        "you", "your", "can", "could", "would", "should", "will", "please", "make", "made", "fix", "fixed",
        "change", "changes", "add", "added", "remove", "update", "edit", "file", "files", "zip", "code",
        "bug", "bugs", "issue", "issues", "problem", "work", "works", "working", "doesn", "don", "not",
        "when", "what", "why", "how", "where", "which", "there", "here", "also", "just", "only", "some",
        "need", "want", "let", "get", "got", "use", "used", "using", "all", "any", "new", "now", "still",
        "it's", "its", "are", "was", "were", "been", "has", "have", "had", "but", "out", "one", "two",
        "version", "bump", "test", "give", "send", "back", "same", "like", "via", "per", "our", "his", "her",
        "conversation", "follow", "user", "crew", "answer", "earlier", "above", "too", "very", "much",
    )

    /** A few everyday words and what code usually calls them. */
    private val ALIASES = mapOf(
        "button" to listOf("btn", "key", "keycode"),
        "buttons" to listOf("btn", "key", "keycode"),
        "close" to listOf("quit", "kill", "exit"),
        "window" to listOf("win", "toplevel", "surface"),
        "windows" to listOf("win", "toplevel"),
        "keyboard" to listOf("osk", "kbd", "wvkbd"),
        "volume" to listOf("wpctl", "osd", "audio"),
        "wifi" to listOf("wlan", "nmcli", "networkmanager"),
        "controller" to listOf("gamepad", "joystick", "evdev"),
        "stick" to listOf("axis", "joystick"),
        "sticks" to listOf("axis", "joystick"),
        "screen" to listOf("display", "output"),
        "fan" to listOf("fancontrol", "hwmon"),
        "sound" to listOf("audio", "wpctl", "pipewire"),
    )

    fun keywords(request: String): List<String> {
        val base = Regex("""[A-Za-z][A-Za-z0-9_.\-]{2,}""").findAll(request).map { it.value.lowercase().trim('.', '-') }
            .filter { it.length >= 3 && it !in STOP }
            .toList()
        val extra = base.flatMap { ALIASES[it].orEmpty() }
        // Simple stemming so "closes"/"closing" find "close".
        val stems = base.mapNotNull { w ->
            when {
                w.endsWith("ing") && w.length > 6 -> w.dropLast(3)
                w.endsWith("es") && w.length > 5 -> w.dropLast(2)
                w.endsWith("s") && w.length > 4 -> w.dropLast(1)
                w.endsWith("ed") && w.length > 5 -> w.dropLast(2)
                else -> null
            }
        }
        return (base + stems + extra).distinct().take(24)
    }

    fun search(request: String, files: Map<String, String>, max: Int = 6): List<Hit> {
        val words = keywords(request)
        if (words.isEmpty() || files.isEmpty()) return emptyList()
        val lower = files.mapValues { it.value.lowercase() }
        // How many files mention each word: words found everywhere say little.
        val df = words.associateWith { w -> lower.count { (p, t) -> w in t || w in p.lowercase() } }
        val n = files.size.toDouble()
        val hits = files.keys.mapNotNull { path ->
            val text = lower.getValue(path)
            val p = path.lowercase()
            var score = 0.0
            var matches = 0
            val found = mutableListOf<String>()
            for (w in words) {
                val inPath = w in p
                val count = if (w.length < 4) Regex("""\b${Regex.escape(w)}\b""").findAll(text).count() else countOf(text, w)
                if (!inPath && count == 0) continue
                val idf = ln((n + 1) / (df.getValue(w) + 0.5))
                score += idf * ((if (inPath) 3.0 else 0.0) + ln(1.0 + count.coerceAtMost(30)))
                matches += count
                found += w
            }
            // Several different words in one file beat one word repeated.
            if (found.size > 1) score *= 1.0 + 0.35 * (found.size - 1)
            // Headers and docs only declare or describe; prefer the code that does the work.
            val ext = p.substringAfterLast('.', "")
            if (ext in setOf("h", "hpp", "md", "txt")) score *= 0.75
            if (score <= 0) null else Hit(path, score, matches, found, matchingLines(files.getValue(path), found))
        }
        return hits.sortedByDescending { it.score }.take(max)
    }

    private fun countOf(text: String, w: String): Int {
        var i = text.indexOf(w)
        var c = 0
        while (i >= 0 && c < 200) { c++; i = text.indexOf(w, i + w.length) }
        return c
    }

    /** Up to three lines that mention the most words, with line numbers. */
    private fun matchingLines(text: String, words: List<String>): List<String> =
        text.lines().withIndex()
            .map { (i, l) -> Triple(i, l, words.count { it in l.lowercase() }) }
            .filter { it.third > 0 && it.second.isNotBlank() }
            .sortedWith(compareByDescending<Triple<Int, String, Int>> { it.third }.thenBy { it.first })
            .take(3)
            .sortedBy { it.first }
            .map { (i, l, _) -> "${i + 1}: ${l.trim().take(110)}" }

    /** For the step in the timeline and the planner's prompt. */
    fun describe(hits: List<Hit>, words: List<String>): String =
        if (hits.isEmpty()) "No file mentions ${words.take(8).joinToString(", ")}."
        else hits.joinToString("\n") { h ->
            "${h.path} — ${h.matches} match${if (h.matches == 1) "" else "es"} (${h.words.take(5).joinToString(", ")})" +
                h.lines.joinToString("") { "\n    $it" }
        }
}
