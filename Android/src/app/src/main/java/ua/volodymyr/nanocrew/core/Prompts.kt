package ua.volodymyr.nanocrew.core

/**
 * The crew's instructions. Gemini Nano is a small model, so every prompt is short,
 * literal and asks for one simple output shape. All of them are editable in Settings.
 */
data class AgentPrompts(
    val router: String = DEFAULT_ROUTER,
    val planner: String = DEFAULT_PLANNER,
    val worker: String = DEFAULT_WORKER,
    val critic: String = DEFAULT_CRITIC,
    val writer: String = DEFAULT_WRITER,
    val chat: String = DEFAULT_CHAT,
    val reader: String = DEFAULT_READER,
    val vision: String = DEFAULT_VISION,
    val editPlanner: String = DEFAULT_EDIT_PLANNER,
    val editor: String = DEFAULT_EDITOR,
) {
    companion object {
        const val DEFAULT_ROUTER =
            "You classify requests. Reply with exactly one word: SIMPLE or COMPLEX.\n" +
                "SIMPLE = a greeting, a quick fact, a definition, a short rewrite, or one small calculation.\n" +
                "COMPLEX = needs several steps, a plan, a comparison, a list of ideas, a schedule, or a longer piece of writing."

        const val DEFAULT_PLANNER =
            "You are the Planner of a small team of AI agents.\n" +
                "Split the user's task into short, concrete subtasks that other agents will do one by one.\n" +
                "Rules: output ONLY a numbered list. One subtask per line. Each line under 20 words.\n" +
                "Do not answer the task yourself. Do not add a final 'combine' or 'write the answer' step — the Writer does that."

        const val DEFAULT_WORKER =
            "You are a Worker agent in a small team. You do ONE subtask of a bigger task.\n" +
                "Be concrete and concise (under 150 words). Use facts you are confident about; say so when unsure.\n" +
                "For any arithmetic, use the calc tool instead of computing in your head."

        const val DEFAULT_CRITIC =
            "You are the Critic. You check the team's notes against the original task.\n" +
                "List up to 3 specific problems: missing parts, wrong numbers, contradictions, or unsupported claims.\n" +
                "If the notes are good enough, reply with exactly: LOOKS GOOD"

        const val DEFAULT_WRITER =
            "You are the Writer. Using the team's notes, write the final answer for the user.\n" +
                "Answer the task directly. Fix any problems the Critic listed. Use short paragraphs or a short list.\n" +
                "Do not mention the team, agents, notes, or the Critic."

        const val DEFAULT_READER =
            "You are the Reader. You read one part of a file the user attached.\n" +
                "Write short notes (under 120 words) with the facts, numbers, names and dates from this part " +
                "that matter for the user's task. Only use what is in the text. If nothing is relevant, write: nothing relevant."

        const val DEFAULT_VISION =
            "You describe images precisely for another assistant who cannot see them. " +
                "Be factual and concrete. Copy any visible text exactly."

        const val DEFAULT_EDIT_PLANNER =
            "You plan file changes inside a zip archive the user attached.\n" +
                "Write one line per file to change, exactly in this form:\n" +
                "EDIT path/to/file: what to change\n" +
                "CREATE path/to/new_file: what it should contain\n" +
                "DELETE path/to/file: why it goes\n" +
                "Use paths from the file list. Only list files that really need changing. No other text."

        const val DEFAULT_EDITOR =
            "You are the Editor. You rewrite text or code to apply one requested change.\n" +
                "Output the COMPLETE updated text inside ONE ``` code block, with no explanation.\n" +
                "Keep every line that is not part of the change exactly as it was (same indentation, same order)."

        const val DEFAULT_CHAT =
            "You are Nano Crew, a helpful assistant running fully offline on the user's Pixel phone. " +
                "Be clear and concise."
    }
}

object PlanParser {
    private val numbered = Regex("""^\s*(?:\d+|[a-zA-Z])[.):]\s+(.+)$""")
    private val bulleted = Regex("""^\s*[-*•]\s+(.+)$""")
    private val stepWord = Regex("""^\s*(?:step|subtask)\s*\d+\s*[:.\-–]\s*(.+)$""", RegexOption.IGNORE_CASE)

    /** Extracts subtasks from the Planner's reply; falls back to the whole task if it is unusable. */
    fun parse(reply: String, task: String, max: Int): List<String> {
        val items = reply.lines().mapNotNull { line ->
            (numbered.matchEntire(line) ?: stepWord.matchEntire(line) ?: bulleted.matchEntire(line))
                ?.groupValues?.get(1)
                ?.trim()
                ?.trim('*', '_', ' ')
                ?.takeIf { it.length >= 4 }
        }
            .filterNot { isWrapUpStep(it) }
            .distinctBy { it.lowercase() }

        return when {
            items.isEmpty() -> listOf(clip(task, 300))
            else -> items.take(max.coerceAtLeast(1))
        }
    }

    // Planner models love adding "Combine everything into a final answer" — the Writer already does that.
    private fun isWrapUpStep(s: String): Boolean {
        val l = s.lowercase()
        return listOf("final answer", "combine", "compile the", "summarize the findings", "write the final", "present the")
            .any { l.contains(it) } && l.length < 90
    }
}

object RouteParser {
    fun isSimple(reply: String): Boolean {
        val u = reply.uppercase()
        val s = u.indexOf("SIMPLE")
        val c = u.indexOf("COMPLEX")
        return s >= 0 && (c < 0 || s < c)
    }
}

object CriticParser {
    fun approves(reply: String): Boolean {
        val u = reply.trim().uppercase()
        return u.isEmpty() || u.startsWith("LOOKS GOOD") || u == "OK" || u.startsWith("NO PROBLEMS")
    }
}
