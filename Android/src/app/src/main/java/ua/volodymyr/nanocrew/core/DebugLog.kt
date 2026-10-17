package ua.volodymyr.nanocrew.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * The app's debug log: every model call (sizes, first-token time, speed, errors, retries), every
 * agent step, file imports, follow-ups, zip rebuilds, the app leaving/returning to the screen and
 * UI freezes. Kept in `debug.log` (plus `debug.1.log` once it passes [MAX_BYTES]) so it survives
 * restarts; Settings → Debug log shares it as one text file.
 *
 * Writes go through one background thread, so logging never slows the UI or the agents.
 */
object DebugLog {
    const val MAX_BYTES = 1_500_000L
    private const val KEEP_IN_MEMORY = 300

    private val writer = Executors.newSingleThreadExecutor { r -> Thread(r, "debug-log").apply { isDaemon = true } }
    private val stamp = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)
    @Volatile private var dir: File? = null

    private val _recent = MutableStateFlow<List<String>>(emptyList())
    /** The newest lines, for the Settings preview. */
    val recent: StateFlow<List<String>> = _recent.asStateFlow()

    fun init(directory: File) {
        dir = directory.also { it.mkdirs() }
    }

    val file: File? get() = dir?.let { File(it, "debug.log") }
    private val oldFile: File? get() = dir?.let { File(it, "debug.1.log") }

    fun d(tag: String, msg: String) {
        val line = synchronized(stamp) { stamp.format(Date()) } + " [" + tag + "] " + msg.replace("\n", "\n    ")
        synchronized(this) { _recent.value = (_recent.value + line).takeLast(KEEP_IN_MEMORY) }
        val f = file ?: return
        writer.execute {
            runCatching {
                if (f.length() > MAX_BYTES) {
                    oldFile?.let { old -> old.delete(); f.renameTo(old) }
                }
                f.appendText(line + "\n")
            }
        }
    }

    fun e(tag: String, msg: String, t: Throwable? = null) =
        d(tag, "ERROR $msg" + (t?.let { "\n" + it.stackTraceToString().lines().take(14).joinToString("\n") } ?: ""))

    /** Both log files, oldest first, after pending writes are done. */
    fun allText(): String {
        val done = java.util.concurrent.CountDownLatch(1)
        writer.execute { done.countDown() }
        done.await(2, java.util.concurrent.TimeUnit.SECONDS)
        return listOfNotNull(oldFile, file).filter { it.exists() }.joinToString("") { it.readText() }
    }

    val sizeBytes: Long get() = listOfNotNull(oldFile, file).filter { it.exists() }.sumOf { it.length() }

    fun clear() {
        writer.execute { oldFile?.delete(); file?.delete() }
        _recent.value = emptyList()
    }

    /**
     * Copies this app's own recent logcat lines that match [filter] into the log (native libraries
     * such as LiteRT log their real error messages there). An app may read its own logcat without
     * any permission.
     */
    fun captureLogcat(tag: String, filter: Regex, maxLines: Int = 80) {
        val lines = runCatching {
            val p = ProcessBuilder("logcat", "-d", "-t", "1500", "--pid=${android.os.Process.myPid()}")
                .redirectErrorStream(true).start()
            val out = p.inputStream.bufferedReader().readLines()
            p.waitFor()
            out
        }.getOrElse { listOf("couldn't read logcat: ${it.message}") }
        val hits = lines.filter { filter.containsMatchIn(it) && !it.contains("[$tag]") }.takeLast(maxLines)
        d(tag, "logcat (${hits.size} of ${lines.size} lines):\n" + hits.joinToString("\n") { it.take(400) })
    }

    /** Short preview of a prompt or answer for the log. */
    fun peek(text: String, max: Int = 160): String {
        val t = text.replace(Regex("\\s+"), " ").trim()
        return if (t.length <= max) t else t.take(max) + "…(${t.length} chars)"
    }
}
