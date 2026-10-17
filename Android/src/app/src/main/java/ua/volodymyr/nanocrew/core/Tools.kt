package ua.volodymyr.nanocrew.core

import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/** A tool an agent can call by writing `CALL name: argument` on its own line. */
class Tool(
    val name: String,
    val usage: String,
    val description: String,
    val run: suspend (String) -> String,
)

data class ToolCall(val name: String, val argument: String)

/** Where agents keep notes between runs. The app stores them in a file; tests use [InMemoryNotes]. */
interface NoteStore {
    suspend fun add(text: String)
    suspend fun all(): List<String>
}

class InMemoryNotes : NoteStore {
    private val notes = mutableListOf<String>()
    override suspend fun add(text: String) { notes += text }
    override suspend fun all(): List<String> = notes.toList()
}

class ToolBox(private val tools: List<Tool>) {

    val names: List<String> get() = tools.map { it.name }

    /** The block of text that teaches the model which tools exist and how to call them. */
    fun manual(): String = buildString {
        appendLine("Tools you can use (they run on this phone):")
        tools.forEach { appendLine("- ${it.usage} — ${it.description}") }
        appendLine("To use a tool, reply with ONLY the CALL line(s), one per line, nothing else.")
        append("You will then get the results and write your answer. Do not invent tool results.")
    }

    suspend fun execute(call: ToolCall): String {
        val tool = tools.firstOrNull { it.name.equals(call.name, ignoreCase = true) }
            ?: return "error: unknown tool '${call.name}'. Available: ${names.joinToString()}"
        return try {
            tool.run(call.argument).ifBlank { "(no result)" }
        } catch (e: Exception) {
            "error: ${e.message ?: e::class.simpleName}"
        }
    }

    companion object {
        fun default(notes: NoteStore, now: () -> ZonedDateTime = { ZonedDateTime.now() }) = ToolBox(
            listOf(
                Tool("calc", "CALL calc: <math expression>", "exact arithmetic, %, ^, sqrt(), sin() in degrees") { arg ->
                    "${arg.trim()} = ${Calculator.format(Calculator.evaluate(arg))}"
                },
                Tool("time", "CALL time", "current local date, weekday and time") { _ ->
                    now().format(DateTimeFormatter.ofPattern("EEEE, d MMMM yyyy, HH:mm (zzz)", Locale.ENGLISH))
                },
                Tool("save_note", "CALL save_note: <text>", "remember a fact for later runs") { arg ->
                    require(arg.isNotBlank()) { "nothing to save" }
                    notes.add(arg.trim().take(500))
                    "saved"
                },
                Tool("search_notes", "CALL search_notes: <words>", "look up previously saved notes") { arg ->
                    val words = arg.lowercase().split(Regex("\\W+")).filter { it.length > 2 }
                    val all = notes.all()
                    val hits = if (words.isEmpty()) all.takeLast(5) else all
                        .map { n -> n to words.count { n.lowercase().contains(it) } }
                        .filter { it.second > 0 }
                        .sortedByDescending { it.second }
                        .map { it.first }
                        .take(5)
                    if (hits.isEmpty()) "no matching notes" else hits.joinToString("\n") { "• $it" }
                },
            ),
        )
    }
}

object ToolParser {
    // Tolerates what small models actually produce: "CALL calc: 2+2", "call calc(2+2)",
    // "`CALL calc: 2+2`", "- CALL time", "CALL: calc 2+2".
    private val callLine = Regex(
        """^[\s>*`\-•]*CALL\s*:?\s*([A-Za-z_]+)\s*(?:[:(\s]\s*(.*?)\)?)?\s*`*\s*$""",
        RegexOption.IGNORE_CASE,
    )

    fun parse(text: String): List<ToolCall> = text.lines().mapNotNull { line ->
        callLine.matchEntire(line.trim())?.let { m ->
            ToolCall(m.groupValues[1].lowercase(), m.groupValues[2].trim().trim('`', '"'))
        }
    }.distinct().take(4)

    /** The reply with any CALL lines removed. */
    fun stripCalls(text: String): String =
        text.lines().filterNot { callLine.matches(it.trim()) }.joinToString("\n").trim()
}
