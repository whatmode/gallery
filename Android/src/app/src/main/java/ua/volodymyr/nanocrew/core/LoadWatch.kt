package ua.volodymyr.nanocrew.core

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/**
 * Model loading can take the whole app down (a 6 GB model that doesn't fit in memory is killed by
 * Android, with no Java error). So each load is written down before it starts; if the app is gone
 * when it should have finished, the next start says which model it was and why Android ended it.
 */
object LoadWatch {
    private val _lastCrash = MutableStateFlow<String?>(null)
    /** A message about the load that closed the app last time, until dismissed. */
    val lastCrash: StateFlow<String?> = _lastCrash.asStateFlow()

    @Volatile private var checked = false

    private fun marker(context: Context) = File(context.filesDir, "crew/loading.txt")

    fun init(context: Context) {
        DebugLog.init(File(context.filesDir, "crew/logs"))
        if (checked) return
        checked = true
        val m = marker(context)
        if (!m.exists()) return
        val what = runCatching { m.readText().trim() }.getOrDefault("a model")
        m.delete()
        val exit = runCatching {
            context.getSystemService(ActivityManager::class.java)
                .getHistoricalProcessExitReasons(context.packageName, 0, 1).firstOrNull()
        }.getOrNull()
        val reason = when (exit?.reason) {
            ApplicationExitInfo.REASON_LOW_MEMORY -> "Android ran out of memory"
            ApplicationExitInfo.REASON_CRASH_NATIVE -> "the model library crashed"
            ApplicationExitInfo.REASON_CRASH -> "the app crashed"
            ApplicationExitInfo.REASON_SIGNALED -> "Android stopped it (usually memory)"
            ApplicationExitInfo.REASON_ANR -> "it stopped responding"
            ApplicationExitInfo.REASON_USER_REQUESTED -> "it was closed"
            null -> "unknown reason"
            else -> "reason ${exit.reason}"
        }
        val mem = exit?.let { " · it was using ${it.pss / 1024} MB (rss ${it.rss / 1024} MB)" }.orEmpty()
        val msg = "The app closed while loading $what: $reason$mem. Try a smaller \\"Max tokens\\" (⚙ at the top right), " +
            "the CPU instead of the GPU, or a smaller model."
        DebugLog.dSync("model", "LAST LOAD FAILED · $what · exit ${exit?.reason} ${exit?.description.orEmpty()}$mem")
        _lastCrash.value = msg
    }

    fun dismiss() { _lastCrash.value = null }

    /** Call right before loading: written to disk at once, so it survives the app being killed. */
    fun starting(context: Context, what: String) {
        init(context)
        val am = context.getSystemService(ActivityManager::class.java)
        val mi = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        runCatching { marker(context).apply { parentFile?.mkdirs() }.writeText(what) }
        DebugLog.dSync(
            "model",
            "loading $what · phone memory free ${mi.availMem / 1_048_576} of ${mi.totalMem / 1_048_576} MB" +
                (if (mi.lowMemory) " (low!)" else ""),
        )
    }

    fun finished(context: Context, what: String, error: String) {
        marker(context).delete()
        DebugLog.d("model", if (error.isEmpty()) "loaded $what" else "couldn't load $what: $error")
    }
}
