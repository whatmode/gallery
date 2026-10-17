package ua.volodymyr.nanocrew.core

/** One earlier exchange in a Crew conversation: what the user asked and what the crew answered. */
data class Turn(
    val task: String,
    val answer: String,
    val attachments: List<String> = emptyList(),
)

/**
 * Follow-ups: a message sent after an answer continues the same task. Gemini Nano has no memory
 * between calls, so the earlier turns travel inside the task text every agent sees, newest last.
 */
object FollowUp {
    /** How much of the task budget the earlier turns may take. */
    val EARLIER_CHARS get() = Budget.EARLIER_CHARS
    const val MAX_TURNS = 3

    fun compose(earlier: List<Turn>, ask: String): String {
        val now = ask.trim()
        if (earlier.isEmpty()) return now
        val recent = earlier.takeLast(MAX_TURNS)
        val each = (EARLIER_CHARS / recent.size).coerceAtLeast(160)
        val convo = recent.joinToString("\n") { t ->
            val q = clip(t.task, each / 3)
            val a = clip(t.answer.ifBlank { "(no answer)" }, each - each / 3)
            "User: $q\nCrew: $a"
        }
        return clip(
            "Conversation so far (oldest first):\n$convo\n\n" +
                "Follow-up from the user — answer this, using the conversation above:\n$now",
            Budget.TASK_CHARS,
        )
    }
}
