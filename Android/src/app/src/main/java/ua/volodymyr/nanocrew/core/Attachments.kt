package ua.volodymyr.nanocrew.core

/** A file the user attached: either extracted text or an image (Bitmap on the phone). */
data class Attachment(
    val name: String,
    val kind: Kind,
    val text: String = "",
    val image: Any? = null,
    /** Extra info for the user, e.g. "first 40 pages" or "scanned page 2". */
    val note: String = "",
    /** Set for .zip files: lets the Editor change files and rebuild the archive. */
    val archive: ArchiveInfo? = null,
) {
    /** True for pictures pulled out of a zip (named "archive.zip › path"). */
    val fromArchive: Boolean get() = " › " in name

    /** The file the user actually attached ("archive.zip" for things inside it). */
    val baseName: String get() = name.substringBefore(" › ")

    enum class Kind { TEXT, IMAGE }

    val sizeLabel: String
        get() = when (kind) {
            Kind.IMAGE -> "image"
            Kind.TEXT -> if (text.length < 1000) "${text.length} chars" else "${text.length / 1000}k chars"
        }
}

/**
 * Turns attachments into a compact text block the agents can use.
 *
 * Gemini Nano only takes ~4K tokens per call, so: images are described by a vision call,
 * short texts go in as-is, and long texts are read part by part ("map"), keeping only
 * notes relevant to the task. Each model call is reported through [run] so the Crew can
 * show it as a Reader step in the timeline.
 */
object Reading {
    val INLINE_TOTAL_CHARS get() = Budget.INLINE_TOTAL_CHARS
    val SMALL_FILE_CHARS get() = Budget.SMALL_FILE_CHARS
    val CHUNK_CHARS get() = Budget.READ_CHUNK_CHARS
    const val MAX_CHUNKS = 8

    suspend fun digest(
        attachments: List<Attachment>,
        focus: String,
        prompts: AgentPrompts,
        run: suspend (title: String, request: LlmRequest) -> String,
    ): String {
        if (attachments.isEmpty()) return ""
        val focusText = clip(focus.ifBlank { "Understand and summarise the file." }, 600)
        val parts = mutableListOf<String>()

        // Pictures found inside a zip (wallpapers, icons…) are only worth a slow vision call
        // when the task is actually about visuals.
        val images = attachments.filter { it.kind == Attachment.Kind.IMAGE }
            .filter { !it.fromArchive || VISUAL_TASK.containsMatchIn(focus) }
        images.forEach { a ->
            val desc = run(
                "Looking at ${shortName(a.name)}",
                LlmRequest(
                    system = prompts.vision,
                    prompt = "The user's task: $focusText\n\nDescribe this image for that task. " +
                        "Include any visible text, numbers, labels and important details.",
                    temperature = 0.2f, maxOutputTokens = 320, image = a.image,
                ),
            )
            parts += "[${a.name} — image]\n$desc"
        }

        val texts = attachments.filter { it.kind == Attachment.Kind.TEXT && it.text.isNotBlank() }
        val totalChars = texts.sumOf { it.text.length }
        var chunksLeft = MAX_CHUNKS
        for (a in texts) {
            val label = "[${a.name}${if (a.note.isNotBlank()) " — ${a.note}" else ""}]"
            if (totalChars <= INLINE_TOTAL_CHARS || a.text.length <= SMALL_FILE_CHARS) {
                parts += "$label\n${a.text.trim()}"
                continue
            }
            val chunks = chunk(a.text, CHUNK_CHARS)
            val readable = chunks.take(chunksLeft.coerceAtLeast(1))
            chunksLeft -= readable.size
            val notes = readable.mapIndexed { i, c ->
                val n = run(
                    (if (chunks.size > 1) "Reading part ${i + 1}/${chunks.size} · " else "Reading ") + shortName(a.name),
                    LlmRequest(
                        system = prompts.reader,
                        prompt = "The user's task: $focusText\n\nPart ${i + 1} of ${chunks.size} of \"${a.name}\":\n" +
                            "\"\"\"\n$c\n\"\"\"\n\nNotes relevant to the task:",
                        temperature = 0.1f, maxOutputTokens = 220,
                    ),
                )
                if (chunks.size > 1) "(part ${i + 1}) $n" else n
            }
            val skipped = chunks.size - readable.size
            parts += "$label notes:\n" + notes.joinToString("\n") +
                if (skipped > 0) "\n(only the first ${readable.size} of ${chunks.size} parts were read)" else ""
        }
        return clip(parts.joinToString("\n\n"), INLINE_TOTAL_CHARS + 1_500)
    }

    val VISUAL_TASK = Regex(
        "\\b(image|images|photo|picture|screenshot|logo|icon|wallpaper|design|ui|ux|look|looks|visual|colou?r|theme|layout|style)s?\\b",
        RegexOption.IGNORE_CASE,
    )

    /** "proj.zip › assets/bg.png" → "bg.png"; plain names unchanged. */
    fun shortName(name: String): String = name.substringAfterLast(" › ").substringAfterLast('/')

    /** Splits on paragraph, then sentence boundaries, into pieces of at most [size] chars. */
    fun chunk(text: String, size: Int): List<String> {
        val t = text.replace("\r\n", "\n").trim()
        if (t.length <= size) return listOf(t)
        val out = mutableListOf<String>()
        var start = 0
        while (start < t.length) {
            var end = (start + size).coerceAtMost(t.length)
            if (end < t.length) {
                val window = t.substring(start, end)
                val cut = listOf(window.lastIndexOf("\n\n"), window.lastIndexOf("\n"), window.lastIndexOf(". "))
                    .firstOrNull { it > size / 2 }
                if (cut != null) end = start + cut + 1
            }
            out += t.substring(start, end).trim()
            start = end
        }
        return out.filter { it.isNotBlank() }
    }
}
