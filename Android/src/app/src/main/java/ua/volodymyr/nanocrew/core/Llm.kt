package ua.volodymyr.nanocrew.core

/** One call to the on-device model. */
data class LlmRequest(
    val system: String,
    val prompt: String,
    val temperature: Float = 0.3f,
    val topK: Int = 16,
    val maxOutputTokens: Int = 320,
    /** Optional image for multimodal calls (an android.graphics.Bitmap on the phone). */
    val image: Any? = null,
)

/**
 * Anything that can run a prompt. On the phone this is [ua.volodymyr.nanocrew.engine.NanoEngine]
 * (Gemini Nano through AICore on the Tensor G4); in tests it is a fake.
 */
interface LlmEngine {
    /** Generates a reply; [onChunk] receives streamed text as it arrives. Returns the full text. */
    suspend fun generate(request: LlmRequest, onChunk: (String) -> Unit = {}): String

    companion object {
        /** Sent through onChunk when a call is retried after it had streamed some text: start over. */
        const val RESTART = "\u0000restart"
    }
}

/**
 * Rough character budgets per prompt. Gemini Nano takes ~4K tokens per call; Gemma 4 E2B (run by
 * the app through LiteRT-LM) is set up for 16K, so with it every agent sees about four times more
 * of the files, the notes and the conversation. [large] follows the model chosen in Settings.
 */
object Budget {
    /** Context of Gemini Nano, which the small numbers below are sized for. */
    const val NANO_TOKENS = 4_096
    /** Context the large numbers below are sized for. */
    const val FULL_TOKENS = 16_384

    /** True while Gemma is the chosen model. */
    @Volatile var useGemma: Boolean = false
    /** The context Gemma actually loaded with (the model file decides how much it allows). */
    @Volatile var gemmaTokens: Int = FULL_TOKENS

    val contextTokens: Int get() = if (useGemma) gemmaTokens else NANO_TOKENS

    /** Scales between the Nano-sized and the 16K-sized numbers by the context there really is. */
    private fun pick(small: Int, big: Int): Int {
        val t = contextTokens
        return when {
            t >= FULL_TOKENS -> big
            t <= NANO_TOKENS -> (small.toLong() * t / NANO_TOKENS).toInt()
            else -> small + ((big - small).toLong() * (t - NANO_TOKENS) / (FULL_TOKENS - NANO_TOKENS)).toInt()
        }
    }

    val PROMPT_CHARS get() = pick(8_000, 36_000)
    val FINDING_CHARS get() = pick(1_400, 4_000)
    val ALL_FINDINGS_CHARS get() = pick(4_800, 14_000)
    val TASK_CHARS get() = pick(2_000, 6_000)
    val CHAT_HISTORY_CHARS get() = pick(6_000, 18_000)
    /** How much attached-file content each agent sees. */
    val DOCS_CHARS get() = pick(2_500, 14_000)
    val DOCS_SHORT_CHARS get() = pick(1_400, 8_000)

    /** Reader: files up to this size go in whole; longer ones are read part by part. */
    val INLINE_TOTAL_CHARS get() = pick(3_200, 14_000)
    val SMALL_FILE_CHARS get() = pick(1_200, 5_000)
    val READ_CHUNK_CHARS get() = pick(5_200, 20_000)
    /** Editor: how big a slice of a file one rewrite call gets. */
    val EDIT_CHUNK_LINES get() = pick(120, 360)
    val EDIT_CHUNK_CHARS get() = pick(3_800, 11_000)
    /** Earlier turns carried into a follow-up. */
    val EARLIER_CHARS get() = pick(1_300, 5_000)
}

/** Keeps the start and the end of an over-long text, dropping the middle. */
fun clip(text: String, maxChars: Int): String {
    val t = text.trim()
    if (t.length <= maxChars) return t
    if (maxChars < 40) return t.take(maxChars)
    val head = (maxChars * 0.7).toInt()
    val tail = maxChars - head - 7
    return t.take(head).trimEnd() + "\n[…]\n" + t.takeLast(tail).trimStart()
}
