package ua.volodymyr.nanocrew.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import ua.volodymyr.nanocrew.core.Attachment
import ua.volodymyr.nanocrew.data.FileImport
import ua.volodymyr.nanocrew.data.ZipExport
import ua.volodymyr.nanocrew.core.FileEdit
import ua.volodymyr.nanocrew.core.Crew
import ua.volodymyr.nanocrew.core.CrewConfig
import ua.volodymyr.nanocrew.core.Phase
import ua.volodymyr.nanocrew.core.RunState
import ua.volodymyr.nanocrew.core.ToolBox
import ua.volodymyr.nanocrew.core.Turn
import ua.volodymyr.nanocrew.core.DebugLog
import ua.volodymyr.nanocrew.data.Storage
import ua.volodymyr.nanocrew.engine.ModelStatus
import java.util.UUID

/**
 * Nano Crew inside Crew Gallery: the crew, its files, history and settings. The model is the one
 * Gallery loaded for the Crew task ([attachModel]); Gallery downloads and starts it.
 */
class CrewViewModel(app: Application) : AndroidViewModel(app) {

    init {
        DebugLog.init(java.io.File(app.filesDir, "crew/logs"))
        installCrashLog()
    }

    private val storage = Storage(app)
    private val files = FileImport(app)

    private val _config = MutableStateFlow(storage.loadConfig())

    @Volatile private var model: com.google.ai.edge.gallery.data.Model? = null
    val engine = ua.volodymyr.nanocrew.engine.GalleryLlmEngine { model }

    private val _modelStatus = MutableStateFlow<ModelStatus>(ModelStatus.Checking)
    val modelStatus: StateFlow<ModelStatus> = _modelStatus.asStateFlow()

    /** Called by the screen with Gallery's selected model and its loading state. */
    fun attachModel(m: com.google.ai.edge.gallery.data.Model?, status: com.google.ai.edge.gallery.data.Model.InitializationStatus?) {
        if (m?.name != model?.name) DebugLog.d("model", "model → ${m?.name}")
        model = m
        if (m == null) { _modelStatus.value = ModelStatus.Preparing("No model yet", "Pick one with the chip at the top."); return }
        val tokens = engine.contextTokens(m)
        ua.volodymyr.nanocrew.core.Budget.useGemma = true
        ua.volodymyr.nanocrew.core.Budget.gemmaTokens = tokens
        val name = m.displayName.ifBlank { m.name }
        val next = when (status) {
            is com.google.ai.edge.gallery.data.Model.InitializationStatus.Initialized ->
                ModelStatus.Ready("$name · ${m.currentAccelerator?.label ?: "GPU"}", tokens, true)
            is com.google.ai.edge.gallery.data.Model.InitializationStatus.Failed ->
                ModelStatus.Failed("$name couldn't be loaded: ${status.error.message}")
            is com.google.ai.edge.gallery.data.Model.InitializationStatus.Initializing ->
                ModelStatus.Preparing("Loading $name…", "Takes about 10 seconds.")
            else -> ModelStatus.Preparing("$name isn't loaded", "It loads when the Crew opens.")
        }
        if (next != _modelStatus.value) {
            DebugLog.d("model", "status → $next")
            _modelStatus.value = next
        }
    }
    val config: StateFlow<CrewConfig> = _config.asStateFlow()

    /** The run on the Crew screen (live or reopened from history). */
    private val _run = MutableStateFlow<RunState?>(null)
    val run: StateFlow<RunState?> = _run.asStateFlow()

    private val _history = MutableStateFlow<List<RunState>>(emptyList())
    val history: StateFlow<List<RunState>> = _history.asStateFlow()

    private val _noteCount = MutableStateFlow(0)
    val noteCount: StateFlow<Int> = _noteCount.asStateFlow()

    // ---- attachments (one tray per tab) ----
    enum class Tray { CREW, CHAT }

    private val _crewFiles = MutableStateFlow<List<Attachment>>(emptyList())
    val crewFiles: StateFlow<List<Attachment>> = _crewFiles.asStateFlow()
    private val _importing = MutableStateFlow(false)
    val importing: StateFlow<Boolean> = _importing.asStateFlow()
    private val _fileError = MutableStateFlow<String?>(null)
    val fileError: StateFlow<String?> = _fileError.asStateFlow()

    private var crewJob: Job? = null
    val busy: Boolean get() = crewJob?.isActive == true

    init {
        viewModelScope.launch {
            _history.value = storage.loadRuns()
            _noteCount.value = storage.notes.all().size
        }
    }

    /** Gallery loads the model itself; this just notes the state in the debug log. */
    fun checkModel() { DebugLog.d("model", "re-check: ${modelStatus.value}") }

    // ---- files --------------------------------------------------------------------------------

    private fun tray(@Suppress("UNUSED_PARAMETER") t: Tray) = _crewFiles

    fun addFiles(uris: List<Uri>, target: Tray) {
        if (uris.isEmpty()) return
        _fileError.value = null
        viewModelScope.launch {
            _importing.value = true
            try {
                for (uri in uris) {
                    if (tray(target).value.size >= MAX_FILES) {
                        _fileError.value = "Up to $MAX_FILES files per message."
                        break
                    }
                    val t0 = System.currentTimeMillis()
                    try {
                        val got = files.import(uri)
                        tray(target).update { (it + got).take(MAX_FILES) }
                        DebugLog.d("files", "imported ${got.firstOrNull()?.baseName} in ${System.currentTimeMillis() - t0} ms · " +
                            got.joinToString { "${it.kind} ${it.text.length} chars${if (it.archive != null) " zip ${it.archive.allPaths.size} files" else ""}" })
                    } catch (e: FileImport.ImportException) {
                        _fileError.value = e.message
                        DebugLog.d("files", "import refused after ${System.currentTimeMillis() - t0} ms: ${e.message}")
                    } catch (e: Exception) {
                        _fileError.value = "Couldn't open that file: ${e.message ?: e::class.java.simpleName}"
                        DebugLog.e("files", "import failed for $uri", e)
                    }
                }
            } finally {
                _importing.value = false
            }
        }
    }

    fun removeFile(index: Int, target: Tray) {
        tray(target).update { list -> list.filterIndexed { i, _ -> i != index } }
        _fileError.value = null
    }

    fun dismissFileError() { _fileError.value = null }

    // ---- crew ---------------------------------------------------------------------------------

    /** Files the current conversation works with (kept so a follow-up can use them again). */
    private var threadFiles: List<Attachment> = emptyList()

    /** A finished run on the Crew screen: the next message continues it instead of starting over. */
    val canFollowUp: Boolean
        get() = _run.value?.let { it.phase != Phase.RUNNING && it.id.isNotEmpty() } == true

    fun startCrew(rawTask: String) {
        val newFiles = _crewFiles.value
        if ((rawTask.isBlank() && newFiles.isEmpty()) || busy) {
            DebugLog.d("crew", "start ignored (blank=${rawTask.isBlank()}, files=${newFiles.size}, busy=$busy)")
            return
        }
        val previous = _run.value?.takeIf { canFollowUp }
        val task = rawTask.ifBlank {
            if (newFiles.size == 1) "Summarize the attached file and pull out the key points."
            else "Summarize the attached files and pull out the key points."
        }
        if (previous != null && newFiles.isEmpty() && task.length <= 80 && UNDO.containsMatchIn(task)) {
            undo(previous, task)
            return
        }
        _crewFiles.value = emptyList()
        val crew = Crew(engine, ToolBox.default(storage.notes), _config.value)
        crewJob = viewModelScope.launch {
            // A follow-up keeps the conversation and its files: the edited zip when the crew made
            // one (so the next change builds on it), otherwise the files from before.
            val earlier = previous?.let { it.earlier + Turn(it.task, it.finalAnswer, it.attachments) }.orEmpty()
            val tCarry = System.currentTimeMillis()
            val carried = if (previous == null) emptyList() else {
                _importing.value = true
                try { carriedFiles(previous) } finally { _importing.value = false }
            }
            if (previous != null) DebugLog.d("crew", "follow-up of ${previous.id}: carried ${carried.joinToString { it.baseName }.ifBlank { "no files" }} " +
                "in ${System.currentTimeMillis() - tCarry} ms (previous output=${previous.outputPath?.substringAfterLast('/')}, archive=${previous.archiveName})")
            val attachments = (carried.filter { c -> newFiles.none { it.baseName == c.baseName } } + newFiles)
            threadFiles = attachments
            // What the Reader found before still holds for the carried files; the Editor's changes
            // since then are added to it, so nothing has to be read again.
            val knownDocs = previous?.let { prevDocs(it) }.orEmpty()
            val alreadyRead = if (knownDocs.isBlank()) emptySet() else carried.map { it.baseName }.toSet()
            val id = previous?.id ?: UUID.randomUUID().toString()
            val collector = launch { crew.state.collect { if (it.id.isNotEmpty()) _run.value = it } }
            val result = try {
                crew.run(id, task, attachments, earlier, knownDocs, alreadyRead)
            } finally {
                collector.cancel()
                var last = crew.state.value
                if (last.id.isNotEmpty()) {
                    // Edit mode: rebuild the zip with the Editor's changes.
                    if (last.archiveId != null && last.archiveName != null &&
                        last.edits.any { it.status == FileEdit.Status.DONE }
                    ) {
                        val name = ZipExport.outputName(last.archiveName!!)
                        val changed = last.edits.count { it.status == FileEdit.Status.DONE }
                        val tZip = System.currentTimeMillis()
                        // Show the packing as a step of its own, and keep the run "working" until the zip is ready.
                        val stepId = (last.steps.maxOfOrNull { it.id } ?: -1) + 1
                        fun packing(text: String, status: ua.volodymyr.nanocrew.core.StepStatus, ended: Long? = null) {
                            val step = ua.volodymyr.nanocrew.core.Step(
                                id = stepId, role = ua.volodymyr.nanocrew.core.Role.TOOL,
                                title = "Packing $name", text = text, status = status, startedAt = tZip, endedAt = ended,
                            )
                            _run.value = last.copy(
                                phase = if (status == ua.volodymyr.nanocrew.core.StepStatus.RUNNING) Phase.RUNNING else last.phase,
                                steps = last.steps.filter { it.id != stepId } + step,
                            )
                        }
                        packing("Copying the zip with $changed changed file${if (changed == 1) "" else "s"}…", ua.volodymyr.nanocrew.core.StepStatus.RUNNING)
                        last = withContext(NonCancellable) {
                            try {
                                var lastShown = 0L
                                val out = ZipExport.build(
                                    files.archiveFile(last.archiveId!!),
                                    storage.outputFile(last.id, name),
                                    last.edits,
                                ) { done, total ->
                                    val now = System.currentTimeMillis()
                                    if (now - lastShown > 150 || done == total) {
                                        lastShown = now
                                        packing("$done of $total files · $changed changed", ua.volodymyr.nanocrew.core.StepStatus.RUNNING)
                                    }
                                }
                                val ms = System.currentTimeMillis() - tZip
                                DebugLog.d("files", "rebuilt ${out.name} in $ms ms · ${out.length()} bytes")
                                val doneStep = ua.volodymyr.nanocrew.core.Step(
                                    id = stepId, role = ua.volodymyr.nanocrew.core.Role.TOOL, title = "Packed $name",
                                    text = "%.1f MB · $changed changed file${if (changed == 1) "" else "s"}, everything else copied as it was".format(out.length() / 1_048_576.0),
                                    status = ua.volodymyr.nanocrew.core.StepStatus.DONE, startedAt = tZip, endedAt = System.currentTimeMillis(),
                                )
                                last.copy(outputPath = out.absolutePath, steps = last.steps + doneStep)
                            } catch (e: Exception) {
                                DebugLog.e("files", "couldn't rebuild the zip", e)
                                val errStep = ua.volodymyr.nanocrew.core.Step(
                                    id = stepId, role = ua.volodymyr.nanocrew.core.Role.TOOL, title = "Packing $name failed",
                                    text = e.message ?: e.toString(), status = ua.volodymyr.nanocrew.core.StepStatus.ERROR,
                                    startedAt = tZip, endedAt = System.currentTimeMillis(),
                                )
                                last.copy(
                                    steps = last.steps + errStep,
                                    finalAnswer = last.finalAnswer + "\n\n⚠️ Couldn't build the new zip: ${e.message}",
                                )
                            }
                        }
                    }
                    _run.value = last
                    // Save even when the run was stopped (the coroutine is cancelled at this point).
                    withContext(NonCancellable) {
                        storage.saveRun(last)
                        _history.value = storage.loadRuns()
                        _noteCount.value = storage.notes.all().size
                    }
                }
            }
            // The finally block above already showed the final state (with the packed zip); only
            // fall back to the crew's own result if it never got there.
            if (_run.value?.id != result.id) _run.value = result
        }
    }

    fun stopCrew() { DebugLog.d("crew", "Stop pressed"); crewJob?.cancel() }

    // ---- debug log ----------------------------------------------------------------------------

    val debugLines = DebugLog.recent

    /** Writes the whole log to one text file and opens the share sheet (send it to Claude to fix things). */
    fun shareDebugLog(context: android.content.Context) = viewModelScope.launch {
      try {
        val file = withContext(kotlinx.coroutines.Dispatchers.IO) {
            val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US).format(java.util.Date())
            val out = storage.outputFile("debug", "crewgallery-debug-$stamp.txt")
            out.parentFile?.listFiles()?.forEach { it.delete() }
            out.parentFile?.mkdirs()
            val status = "${model?.name}: ${modelStatus.value}"
            out.writeText(
                "Crew Gallery debug log · exported $stamp\n" +
                    "model: $status\n" +
                    "running: $busy · current run: ${_run.value?.let { "${it.id} ${it.phase} ${it.steps.size} steps" } ?: "none"}\n" +
                    "settings: subtasks ${_config.value.maxSubtasks}, critic ${_config.value.useCritic}, tools ${_config.value.useTools}, autoRoute ${_config.value.autoRoute}, temp ${_config.value.temperature}\n" +
                    "----\n" + DebugLog.allText(),
            )
            out
        }
        val uri = androidx.core.content.FileProvider.getUriForFile(context, context.packageName + ".files", file)
        val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(android.content.Intent.EXTRA_STREAM, uri)
            putExtra(android.content.Intent.EXTRA_SUBJECT, file.name)
            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startChooser(context, android.content.Intent.createChooser(send, "Share debug log"))
      } catch (e: Exception) {
        DebugLog.e("share", "couldn't share the debug log", e)
        android.widget.Toast.makeText(context, "Couldn't share the log: ${e.message}", android.widget.Toast.LENGTH_LONG).show()
      }
    }

    fun clearDebugLog() = DebugLog.clear()

    val debugLogSize: Long get() = DebugLog.sizeBytes

    /**
     * "Undo": gives back the zip as it was before the crew's last change, exactly (no model call).
     * The restored zip becomes the conversation's zip, so the next request starts from it.
     */
    private fun undo(previous: RunState, task: String) {
        DebugLog.d("crew", "undo requested for ${previous.id} (archive=${previous.archiveName}, edits=${previous.edits.size})")
        crewJob = viewModelScope.launch {
            val now = System.currentTimeMillis()
            val before = previous.archiveId?.let { files.archiveFile(it) }?.takeIf { it.exists() }
            val changed = previous.edits.filter { it.status == FileEdit.Status.DONE }
            val earlier = previous.earlier + Turn(previous.task, previous.finalAnswer, previous.attachments)
            var run = RunState(
                id = previous.id, task = task, startedAt = now, endedAt = now, phase = Phase.DONE,
                attachments = previous.attachments, earlier = earlier, digest = previous.digest,
            )
            run = if (before == null || changed.isEmpty() || previous.archiveName == null) {
                run.copy(finalAnswer = "There's nothing to undo — the last answer didn't change any files.")
            } else {
                try {
                    val out = storage.outputFile(previous.id, previous.archiveName)
                    withContext(kotlinx.coroutines.Dispatchers.IO) { out.parentFile?.mkdirs(); before.copyTo(out, overwrite = true) }
                    threadFiles = emptyList()
                    run.copy(
                        outputPath = out.absolutePath,
                        archiveName = previous.archiveName,
                        steps = listOf(
                            ua.volodymyr.nanocrew.core.Step(
                                id = 0, role = ua.volodymyr.nanocrew.core.Role.EDITOR, title = "Undo · back to ${previous.archiveName}",
                                text = changed.joinToString("\n") { "undid ${it.action.name} ${it.path}" },
                                status = ua.volodymyr.nanocrew.core.StepStatus.DONE, startedAt = now, endedAt = now,
                            ),
                        ),
                        finalAnswer = "Undid the last change — **${previous.archiveName}** is back to how it was before:\n\n" +
                            changed.joinToString("\n") { "- ↩️ ${it.action.name.lowercase()} `${it.path}`" } +
                            "\n\nSave or share it below, or tell the crew what to do instead.",
                    )
                } catch (e: Exception) {
                    run.copy(finalAnswer = "Couldn't undo: ${e.message}")
                }
            }
            _run.value = run
            withContext(NonCancellable) {
                storage.saveRun(run)
                _history.value = storage.loadRuns()
            }
        }
    }

    /** The previous run's Reader notes, plus what its Editor changed (the zip changed since it was read). */
    private fun prevDocs(previous: RunState): String {
        if (previous.digest.isBlank()) return ""
        val done = previous.edits.filter { it.status == FileEdit.Status.DONE }
        if (done.isEmpty()) return previous.digest
        val changes = buildString {
            appendLine("Changes the crew already made to ${previous.archiveName ?: "the zip"} (this is the zip now):")
            done.forEach { e ->
                append("- ${e.action.name} ${e.path}: ${e.instruction}")
                if (e.action != FileEdit.Action.DELETE && e.content.isNotBlank()) {
                    append("\n  now starts with: ").append(e.content.take(300).replace("\n", "\n  "))
                }
                appendLine()
            }
        }
        return ua.volodymyr.nanocrew.core.clip(changes, 1_200) + "\n\n" + previous.digest
    }

    private suspend fun carriedFiles(previous: RunState): List<Attachment> {
        val out = previous.outputPath?.let { java.io.File(it) }?.takeIf { it.exists() }
        val inMemory = threadFiles.takeIf { files -> files.isNotEmpty() && files.map { it.baseName }.distinct().sorted() == previous.attachments.sorted() }
        return try {
            when {
                out != null -> {
                    val keep = (inMemory ?: emptyList()).filter { it.baseName != previous.archiveName }
                    keep + files.importZipFile(out, out.name)
                }
                inMemory != null -> inMemory
                previous.archiveId != null && previous.archiveName != null ->
                    files.importZipFile(files.archiveFile(previous.archiveId), previous.archiveName)
                else -> emptyList()
            }
        } catch (e: Exception) {
            inMemory ?: emptyList()
        }
    }

    private val _outputMessage = MutableStateFlow<String?>(null)
    val outputMessage: StateFlow<String?> = _outputMessage.asStateFlow()

    /** Copies the edited zip to where the user chose in the system "Save as" screen. */
    fun saveOutput(run: RunState, target: Uri) = viewModelScope.launch {
        val path = run.outputPath ?: return@launch
        _outputMessage.value = try {
            withContext(kotlinx.coroutines.Dispatchers.IO) {
                getApplication<Application>().contentResolver.openOutputStream(target)?.use { out ->
                    java.io.File(path).inputStream().use { it.copyTo(out) }
                } ?: error("couldn't open the destination")
            }
            "Saved ${java.io.File(path).name}"
        } catch (e: Exception) {
            "Couldn't save: ${e.message}"
        }
    }

    fun dismissOutputMessage() { _outputMessage.value = null }

    fun newCrewTask() { if (crewJob?.isActive != true) { _run.value = null; threadFiles = emptyList() } }

    fun openRun(run: RunState) { if (crewJob?.isActive != true) { _run.value = run; threadFiles = emptyList() } }

    fun deleteRun(id: String) = viewModelScope.launch {
        storage.deleteRun(id)
        _history.value = storage.loadRuns()
        if (_run.value?.id == id && _run.value?.phase != Phase.RUNNING) _run.value = null
    }

    fun clearHistory() = viewModelScope.launch {
        storage.clearRuns()
        _history.value = emptyList()
    }

    // ---- settings -----------------------------------------------------------------------------

    fun updateConfig(transform: (CrewConfig) -> CrewConfig) {
        val next = transform(_config.value)
        _config.value = next
        storage.saveConfig(next)
    }

    fun resetPrompts() = updateConfig { it.copy(prompts = ua.volodymyr.nanocrew.core.AgentPrompts()) }

    fun clearNotes() = viewModelScope.launch {
        storage.clearNotes()
        _noteCount.value = 0
    }

    private companion object {
        @Volatile private var crashLogInstalled = false

        /** Writes any crash to the crew's debug log before the app closes (then Android's handler runs). */
        fun installCrashLog() {
            if (crashLogInstalled) return
            crashLogInstalled = true
            val previous = Thread.getDefaultUncaughtExceptionHandler()
            Thread.setDefaultUncaughtExceptionHandler { t, e ->
                runCatching {
                    DebugLog.e("crash", "uncaught on ${t.name}", e)
                    DebugLog.file?.appendText("CRASH ${e.stackTraceToString().take(4000)}\n")
                }
                previous?.uncaughtException(t, e)
            }
        }

        const val MAX_FILES = 5
        /** A follow-up that only asks to take the last change back. */
        val UNDO = Regex("""^\s*(please\s+)?(undo|revert|roll\s*back|take\s+(it|that)\s+back|cancel\s+(that|the\s+last|those)\s*(change|changes|edit|edits)?)\b""", RegexOption.IGNORE_CASE)
    }

}
