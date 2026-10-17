package ua.volodymyr.nanocrew.core

data class ChatMessage(
    val fromUser: Boolean,
    val text: String,
    val at: Long,
    /** Names of files attached to this message. */
    val attachments: List<String> = emptyList(),
    /** What Nano read from those files (hidden; replayed so follow-up questions still work). */
    val fileContext: String = "",
)

/**
 * Gemini Nano through AICore is stateless, so a chat is replayed as a transcript each turn,
 * keeping only as many recent turns as fit the input budget.
 */
object ChatPrompt {
    fun build(
        history: List<ChatMessage>,
        userText: String,
        budget: Int = Budget.CHAT_HISTORY_CHARS,
        newFiles: String = "",
    ): String {
        // Files: this message's, or else the most recent ones attached earlier in the chat.
        val files = newFiles.ifBlank { history.lastOrNull { it.fileContext.isNotBlank() }?.fileContext.orEmpty() }
        val filesBlock = if (files.isBlank()) "" else clip(files, Budget.DOCS_CHARS)
        val historyBudget = (budget - filesBlock.length).coerceAtLeast(1_500)
        val turns = ArrayDeque<String>()
        var used = 0
        for (m in history.asReversed()) {
            val line = (if (m.fromUser) "User: " else "Assistant: ") + clip(m.text, 1_200)
            if (used + line.length > historyBudget) break
            turns.addFirst(line)
            used += line.length + 1
        }
        return buildString {
            if (filesBlock.isNotBlank()) {
                appendLine("Files the user attached:")
                appendLine(filesBlock)
                appendLine()
            }
            if (turns.isNotEmpty()) {
                appendLine("Conversation so far:")
                turns.forEach { appendLine(it) }
                appendLine()
            }
            appendLine("User: ${clip(userText, Budget.TASK_CHARS)}")
            append("Assistant:")
        }
    }

    /** Small models sometimes keep going and write the next "User:" turn themselves. */
    fun cleanReply(raw: String): String {
        val cut = Regex("""\n\s*User\s*:""").find(raw)?.range?.first ?: raw.length
        return raw.substring(0, cut).removePrefix("Assistant:").trim()
    }
}
