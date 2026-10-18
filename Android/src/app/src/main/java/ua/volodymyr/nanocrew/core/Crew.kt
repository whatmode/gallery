package ua.volodymyr.nanocrew.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

enum class Role(val label: String) {
    READER("Reader"),
    ROUTER("Router"),
    PLANNER("Planner"),
    WORKER("Worker"),
    TOOL("Tool"),
    CRITIC("Critic"),
    WRITER("Writer"),
    EDITOR("Editor"),
}

enum class StepStatus { RUNNING, DONE, ERROR }

data class Step(
    val id: Int,
    val role: Role,
    val title: String,
    val text: String = "",
    val status: StepStatus = StepStatus.RUNNING,
    val startedAt: Long,
    val endedAt: Long? = null,
) {
    val durationMs: Long? get() = endedAt?.let { it - startedAt }

    /** Approximate output speed (≈4 characters per token), shown in the UI to show off the Tensor G4. */
    val tokensPerSecond: Double?
        get() {
            val d = durationMs ?: return null
            if (d < 200 || role == Role.TOOL || status != StepStatus.DONE || text.length < 8) return null
            return (text.length / 4.0) / (d / 1000.0)
        }
}

enum class Phase { RUNNING, DONE, FAILED, CANCELLED }

data class RunState(
    val id: String,
    val task: String,
    val steps: List<Step> = emptyList(),
    val finalAnswer: String = "",
    val phase: Phase = Phase.RUNNING,
    val error: String? = null,
    val startedAt: Long,
    val endedAt: Long? = null,
    val modelCalls: Int = 0,
    val attachments: List<String> = emptyList(),
    /** File changes made in edit mode (zip archives). */
    val edits: List<FileEdit> = emptyList(),
    val archiveId: String? = null,
    val archiveName: String? = null,
    /** Where the rebuilt zip was saved on the phone, once built. */
    val outputPath: String? = null,
    /** Earlier exchanges when this run is a follow-up (oldest first). */
    val earlier: List<Turn> = emptyList(),
    /** What the Reader found in the attached files (reused by follow-ups instead of reading again). */
    val digest: String = "",
    /** Edit mode: the changes wait for the user to apply or discard them before the zip is packed. */
    val reviewPending: Boolean = false,
)

data class CrewConfig(
    val maxSubtasks: Int = 3,
    val useCritic: Boolean = true,
    val useTools: Boolean = true,
    val autoRoute: Boolean = true,
    val temperature: Float = 0.3f,
    val prompts: AgentPrompts = AgentPrompts(),
    /** Which on-device model the agents run on. */
    val model: ModelChoice = ModelChoice.NANO,
)

enum class ModelChoice(val label: String) {
    NANO("Gemini Nano (Android AICore)"),
    GEMMA("Gemma 4 E2B (in the app, LiteRT-LM)"),
}

/**
 * The multi-agent loop, all on one on-device model:
 *
 *   [Reader ─► attached files → notes]
 *   Router ─► (simple) Worker+tools ─► answer
 *        └─► (complex) Planner ─► Worker₁…Workerₙ (+tools) ─► Critic ─► Writer ─► answer
 *
 * Every agent is the same Gemini Nano with a different role prompt. Calls run one at a time,
 * because AICore serves one inference at a time per app and enforces a quota.
 */
class Crew(
    private val engine: LlmEngine,
    private val tools: ToolBox,
    private val config: CrewConfig,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    private val _state = MutableStateFlow(RunState(id = "", task = "", startedAt = 0))
    val state: StateFlow<RunState> = _state.asStateFlow()

    private var nextStepId = 0

    /** Notes / contents of the attached files, shared with every agent. */
    private var docs = ""

    suspend fun run(
        id: String,
        rawTask: String,
        attachments: List<Attachment> = emptyList(),
        earlier: List<Turn> = emptyList(),
        /** Reader notes from earlier in the conversation, covering the files named in [alreadyRead]. */
        knownDocs: String = "",
        alreadyRead: Set<String> = emptySet(),
    ): RunState {
        // The new message alone decides how to handle it (simple/complex, read/edit); every agent
        // that does the work also sees the conversation so far.
        val ask = clip(rawTask, Budget.TASK_CHARS)
        val task = FollowUp.compose(earlier, rawTask)
        DebugLog.d(
            "crew",
            "run $id start · ${if (earlier.isEmpty()) "new task" else "follow-up #${earlier.size}"} · ask ${rawTask.length} chars · " +
                "files: ${attachments.joinToString { "${it.name} (${it.kind}, ${it.text.length} chars${if (it.archive != null) ", zip ${it.archive.allPaths.size} files / ${it.archive.files.size} editable" else ""})" }.ifBlank { "none" }} · " +
                "known notes ${knownDocs.length} chars for ${alreadyRead.joinToString().ifBlank { "-" }} · " +
                "subtasks ${config.maxSubtasks}, critic ${config.useCritic}, tools ${config.useTools}, autoRoute ${config.autoRoute}, temp ${config.temperature}" +
                "\nask: " + DebugLog.peek(rawTask, 300),
        )
        nextStepId = 0
        docs = ""
        searchHits = emptyList()
        _state.value = RunState(
            id = id, task = rawTask.trim(), startedAt = clock(),
            attachments = attachments.map { it.baseName }.distinct(),
            earlier = earlier,
        )

        try {
            // Look through every file of the zip for the request's words (no model call), so the
            // planner and workers start from the files that actually mention them.
            attachments.firstNotNullOfOrNull { it.archive }?.let { searchHits = searchArchive(rawTask, earlier, it) }
            val archive = attachments.firstNotNullOfOrNull { it.archive }
            // An edit request whose words the search found: the Editor works from those files, so
            // skip reading the whole zip part by part (minutes of "nothing relevant" on a big kit).
            val edit = archive != null && wantsEdit(ask, archive)
            val skipReading = edit && searchHits.isNotEmpty()
            val toRead = if (skipReading) attachments.filter { it.archive == null && it.baseName !in alreadyRead }
                else attachments.filter { it.baseName !in alreadyRead }
            if (knownDocs.isNotBlank()) {
                val names = attachments.map { it.baseName }.filter { it in alreadyRead }.distinct()
                val step = startStep(Role.READER, "Already read · ${names.joinToString(", ").ifBlank { "earlier files" }}")
                endStep(step, "Using what the Reader found earlier in this conversation — no need to read it again.", StepStatus.DONE)
            }
            if (skipReading && archive != null && archive.name !in alreadyRead) {
                val step = startStep(Role.READER, "Reading · only the matching files")
                endStep(step, "The search found where this is handled, so the Editor reads those files in full " +
                    "instead of skimming all ${archive.allPaths.size}.", StepStatus.DONE)
            }
            val fresh = if (toRead.isEmpty()) "" else Reading.digest(toRead, task, config.prompts) { title, req ->
                call(Role.READER, title, req)
            }
            docs = listOf(knownDocs, fresh).filter { it.isNotBlank() }.joinToString("\n\n")
            if (!skipReading) _state.update { it.copy(digest = docs) }
            if (searchHits.isNotEmpty() && archive != null) {
                docs = clip(
                    "Files in ${archive.name} that mention the request (best first):\n" + FileSearch.describe(searchHits, emptyList()),
                    Budget.DOCS_SHORT_CHARS / 2,
                ) + "\n\n" + docs
            }
            if (edit && archive != null) {
                editArchive(task, ask, archive)
                return _state.value
            }
            val simple = config.autoRoute && route(ask)
            if (simple) {
                val answer = work(task, subtask = null, index = 0, total = 1, notes = emptyList(), asFinal = true)
                finish(answer)
            } else {
                val subtasks = plan(task)
                val notes = mutableListOf<Pair<String, String>>()
                subtasks.forEachIndexed { i, sub ->
                    notes += sub to work(task, sub, i, subtasks.size, notes, asFinal = false)
                }
                val critique = if (config.useCritic) critique(task, notes) else ""
                finish(write(task, notes, critique))
            }
        } catch (e: CancellationException) {
            markRunningSteps(StepStatus.ERROR, "Stopped.")
            _state.update { it.copy(phase = Phase.CANCELLED, endedAt = clock()) }
            DebugLog.d("crew", "run $id stopped after ${clock() - _state.value.startedAt} ms")
            throw e
        } catch (e: Exception) {
            markRunningSteps(StepStatus.ERROR, e.message ?: "Error")
            _state.update { it.copy(phase = Phase.FAILED, error = e.message ?: e.toString(), endedAt = clock()) }
            DebugLog.e("crew", "run $id failed", e)
        }
        val s = _state.value
        DebugLog.d(
            "crew",
            "run $id ${s.phase} in ${(s.endedAt ?: clock()) - s.startedAt} ms · ${s.modelCalls} calls · ${s.steps.size} steps" +
                (if (s.edits.isNotEmpty()) " · edits: " + s.edits.joinToString { "${it.action} ${it.path} ${it.status} ${it.detail}" } else "") +
                "\nanswer: " + DebugLog.peek(s.finalAnswer, 300),
        )
        return s
    }

    // ---- agents -------------------------------------------------------------------------------

    private suspend fun route(task: String): Boolean {
        val reply = call(Role.ROUTER, "Choosing a strategy", LlmRequest(
            system = config.prompts.router,
            prompt = "Request: $task" +
                (if (docs.isNotBlank()) "\n(The user attached files; their content is available.)" else "") +
                "\n\nOne word (SIMPLE or COMPLEX):",
            temperature = 0f, topK = 1, maxOutputTokens = 4,
        ), stream = false)
        val simple = RouteParser.isSimple(reply)
        retitleLast(if (simple) "Simple request → one agent" else "Complex request → full crew")
        return simple
    }

    // ---- edit mode (zip archives) ------------------------------------------------------------

    private suspend fun wantsEdit(task: String, archive: ArchiveInfo): Boolean {
        val reply = call(Role.ROUTER, "Read or edit?", LlmRequest(
            system = "You classify requests about a zip archive. Reply with exactly one word: EDIT or READ.\n" +
                "EDIT = the user wants files changed, fixed, translated, added, created or removed.\n" +
                "READ = the user only wants information, a summary, an explanation or a review.",
            prompt = "Request: $task\nArchive: ${archive.name} (${archive.allPaths.size} files)\n\nOne word (EDIT or READ):",
            temperature = 0f, topK = 1, maxOutputTokens = 4,
        ), stream = false)
        val u = reply.uppercase()
        val edit = when {
            "EDIT" in u && "READ" !in u -> true
            "READ" in u && "EDIT" !in u -> false
            else -> EDIT_WORDS.containsMatchIn(task)
        }
        retitleLast(if (edit) "Edit request → Editor will change files" else "Question about the archive → full crew")
        return edit
    }

    /** Files of the zip the request is about, best first (see [FileSearch]). */
    private var searchHits: List<FileSearch.Hit> = emptyList()

    private fun searchArchive(rawTask: String, earlier: List<Turn>, archive: ArchiveInfo): List<FileSearch.Hit> {
        // "Can you fix it?" has no words to search for: use what was asked before as well.
        var text = rawTask
        if (FileSearch.keywords(text).size < 2) text += "\n" + earlier.takeLast(2).joinToString("\n") { it.task }
        val words = FileSearch.keywords(text)
        if (words.isEmpty()) return emptyList()
        val step = startStep(Role.TOOL, "Searching ${archive.files.size} files")
        val t0 = clock()
        val hits = FileSearch.search(text, archive.files)
        endStep(step, "Looked for: ${words.take(10).joinToString(", ")}\n\n" + FileSearch.describe(hits, words), StepStatus.DONE)
        retitleLast(if (hits.isEmpty()) "Search · no file matches" else "Search · ${hits.size} file${if (hits.size == 1) "" else "s"} match")
        DebugLog.d("crew", "search in ${clock() - t0} ms · words ${words.joinToString(",")} · " +
            hits.joinToString { "${it.path} %.1f".format(it.score) }.ifBlank { "no hits" })
        return hits
    }

    private suspend fun editArchive(task: String, ask: String, archive: ArchiveInfo) {
        _state.update { it.copy(archiveId = archive.id, archiveName = archive.name) }
        // Matching files first, so they are never cut off the end of a long listing.
        val ordered = searchHits.map { it.path } + archive.allPaths.filter { p -> searchHits.none { it.path == p } }
        val listing = ordered.take(150).joinToString("\n") { p ->
            val t = archive.files[p]
            if (t != null) "$p (${t.lines().size} lines)" else "$p (binary, can't edit)"
        }
        // Small model, big zip: rather than planning from a listing of every file, look at the
        // files the search found one part at a time and ask only "does this part need to change?".
        currentArchive = archive
        if (fileAgents(task, ask, archive)) return
        val reply = call(Role.PLANNER, "Planning file changes", LlmRequest(
            system = config.prompts.editPlanner,
            prompt = "Task: $task\n" + docsBlock(Budget.DOCS_SHORT_CHARS) +
                (if (searchHits.isNotEmpty())
                    "\nThe files above that mention the request are the likeliest place to change. Change the code that " +
                        "does the work (.c, .cpp, .py, .qml, .sh, config), not only a header (.h) or a comment.\n"
                else "") +
                "\nFiles in ${archive.name}:\n${clip(listing, 3_000)}\n\n" +
                "(Lines look like EDIT path: change, CREATE path: content, or DELETE path: why.)\n" +
                "At most ${config.maxSubtasks} lines:",
            temperature = 0.1f, maxOutputTokens = 220,
        ))
        var planned = EditParsing.parsePlan(reply, archive, config.maxSubtasks)
        DebugLog.d("crew", "edit plan: ${planned.size} usable of reply:\n" + reply.trim().take(600) +
            "\nusable: " + planned.joinToString { "${it.action} ${it.path}" }.ifBlank { "none" } +
            (if (planned.isEmpty()) "\nwhy: " + reply.lines().filter { it.isNotBlank() }.take(5).joinToString(" | ") { l ->
                val p = Regex("""\b(EDIT|CREATE|DELETE|REMOVE|ADD|FIX|UPDATE|CHANGE)\s+`?([^\s`:]+)""", RegexOption.IGNORE_CASE).find(l)?.groupValues?.get(2)
                when {
                    p == null -> "not a plan line"
                    p in archive.files -> "$p ok"
                    p in archive.allPaths -> "$p is in the zip but not editable text"
                    else -> "$p not in the zip"
                }
            } else ""))
        // New files only when asked for, never a near-copy of an existing file, never outside the zip's folders.
        val dropped = mutableListOf<String>()
        val roots = archive.allPaths.map { it.substringBefore('/') }.toSet()
        fun norm(p: String) = p.substringAfterLast('/').lowercase().replace(Regex("[^a-z0-9]"), "")
        planned = planned.filter { p ->
            if (p.action != FileEdit.Action.CREATE) return@filter true
            val twin = archive.allPaths.firstOrNull { norm(it) == norm(p.path) }
            val why = when {
                !CREATE_WORDS.containsMatchIn(ask) -> "you didn't ask for a new file"
                twin != null -> "it looks like a copy of $twin"
                p.path.contains('/') && roots.size == 1 && p.path.substringBefore('/') !in roots -> "it's outside ${roots.first()}/"
                else -> null
            }
            if (why != null) dropped += "not creating ${p.path}: $why"
            why == null
        }
        if (dropped.isNotEmpty()) DebugLog.d("crew", "plan guard: " + dropped.joinToString(" | "))
        if (planned.isEmpty() && archive.files.size == 1) {
            planned = listOf(EditParsing.PlannedEdit(FileEdit.Action.EDIT, archive.files.keys.first(), task))
        }
        replaceLastText(
            (if (planned.isEmpty()) reply.ifBlank { "(no plan)" }
            else planned.joinToString("\n") { "${it.action.name} ${it.path}: ${it.instruction}" }) +
                dropped.joinToString("") { "\n⛔ $it" },
        )
        retitleLast("Plan · ${planned.size} file${if (planned.size == 1) "" else "s"}")
        if (planned.isEmpty()) {
            finish("I couldn't work out which files to change. Try naming the file, e.g. \"in README.md, fix the install steps\".")
            return
        }

        applyPlan(task, archive, planned)
    }

    private suspend fun applyPlan(task: String, archive: ArchiveInfo, planned: List<EditParsing.PlannedEdit>) {
        val edits = mutableListOf<FileEdit>()
        for (p in planned) {
            val e = when (p.action) {
                FileEdit.Action.EDIT -> editFile(task, p.path, archive.files.getValue(p.path), p.instruction)
                FileEdit.Action.CREATE -> createFile(task, p.path, p.instruction)
                FileEdit.Action.DELETE -> FileEdit(p.path, FileEdit.Action.DELETE, p.instruction, FileEdit.Status.DONE, detail = "removed", diff = "@@ whole file removed")
            }
            edits += e
            _state.update { it.copy(edits = edits.toList()) }
        }
        finish(editSummary(archive, edits))
    }

    /** The zip being edited, for checks that look across files. */
    private var currentArchive: ArchiveInfo? = null

    /**
     * One agent per file. The files the search ranked (code before docs) are taken one at a time:
     * the agent checks the file's best-matching parts ("does this need to change, and how?"), and
     * if so edits that file right away, before the next agent starts. Each agent passes a
     * one-line note on to the next, so a later file can follow what an earlier one changed.
     * Returns false if no agent found anything (then the ordinary planner runs).
     */
    private suspend fun fileAgents(task: String, ask: String, archive: ArchiveInfo): Boolean {
        if (searchHits.isEmpty()) return false
        val words = FileSearch.keywords(ask).ifEmpty { searchHits.flatMap { it.words }.distinct() }
        fun score(text: String): Int {
            val low = text.lowercase()
            return words.sumOf { w -> Regex(Regex.escape(w.lowercase())).findAll(low).count() }
        }
        // Code and config first; docs only if the request is about them, data files last.
        val docsAsked = DOCS_WORDS.containsMatchIn(ask)
        fun kind(p: String): Int {
            val e = p.substringAfterLast('/').substringAfterLast('.', "").lowercase()
            return when {
                e in DOC_EXTS -> if (docsAsked) 0 else 3
                e in DATA_EXTS -> if (docsAsked) 0 else 2
                else -> 0
            }
        }
        val ranked = searchHits.withIndex().filter { kind(it.value.path) < 3 }
            .sortedWith(compareBy({ kind(it.value.path) }, { it.index })).map { it.value }
        val candidates = ranked.take(TRIAGE_FILES).mapNotNull { hit ->
            val text = archive.files[hit.path] ?: return@mapNotNull null
            val chunks = Chunker.split(text.replace("\r\n", "\n").removeSuffix("\n"))
            val best = chunks.map { it to score(it.text) }.filter { it.second > 0 || chunks.size == 1 }
                .sortedByDescending { it.second }.take(2).map { it.first }.sortedBy { it.index }
            if (best.isEmpty()) null else Triple(hit.path, chunks.size, best)
        }
        if (candidates.isEmpty()) return false
        DebugLog.d("crew", "file agents: " + candidates.joinToString { "${it.first} (${it.third.size} parts)" })

        val edits = mutableListOf<FileEdit>()
        val notes = mutableListOf<String>()
        var calls = 0
        for ((n, cand) in candidates.withIndex()) {
            val (path, total, parts) = cand
            if (edits.count { it.status == FileEdit.Status.DONE } >= config.maxSubtasks || calls >= TRIAGE_CALLS) break
            val name = path.substringAfterLast('/')
            val agent = "Agent ${n + 1} · $name"
            val context = task + if (notes.isEmpty()) "" else "\n\nWhat the agents on other files found:\n" + notes.joinToString("\n") { "- $it" }
            val what = linkedMapOf<Int, String>()
            for (c in parts) {
                if (calls >= TRIAGE_CALLS) break
                calls++
                val where = if (total == 1) "" else " · part ${c.index + 1}/$total"
                val reply = call(Role.PLANNER, "$agent · checking$where", LlmRequest(
                    system = TRIAGE,
                    prompt = clip(
                        "Task: $context\n\nFile `$path`" + (if (total == 1) "" else ", part ${c.index + 1} of $total (lines ${c.startLine + 1}–${c.endLine})") +
                            ":\n```\n${c.text}\n```\n\nDoes this code need to change to do the task? One line:",
                        Budget.PROMPT_CHARS,
                    ),
                    temperature = 0.1f, maxOutputTokens = 90,
                ))
                val change = parseTriage(reply)
                retitleLast("$agent · checked$where · " + if (change == null) "no change here" else "needs a change")
                if (change != null) what[c.index] = change
            }
            if (what.isEmpty()) {
                notes += "$name: nothing to change"
                continue
            }
            val instruction = clip(what.values.distinct().joinToString("; "), 400)
            // Edit only the parts this agent flagged, each with what it found there.
            val e = editFile(context, path, archive.files.getValue(path), instruction, parts = what, label = "$agent · editing")
            edits += e
            notes += "$name: " + (if (e.status == FileEdit.Status.DONE) "changed — $instruction" else "wanted to: $instruction (not applied: ${e.detail.take(80)})")
            _state.update { it.copy(edits = edits.toList()) }
        }
        DebugLog.d("crew", "file agents: $calls checks, ${edits.size} files tried, ${edits.count { it.status == FileEdit.Status.DONE }} changed")
        if (edits.isEmpty()) return false
        finish(editSummary(archive, edits))
        return true
    }

    /**
     * One part's change, checked on its own: the original lines, the change as a diff, and what it
     * was meant to do. Returns the problem, or null when the reviewer says OK.
     */
    private suspend fun reviewPart(task: String, path: String, before: String, change: String, instruction: String, title: String): String? {
        val reply = call(Role.CRITIC, title, LlmRequest(
            system = REVIEWER,
            prompt = clip(
                "Task: $task\n\nThe change was meant to: $instruction\n\nFile `$path`, the part before the change:\n```\n$before\n```\n\n" +
                    "The change (- removed, + added):\n```\n$change\n```\n\nOne line, OK or PROBLEM: …",
                Budget.PROMPT_CHARS,
            ),
            temperature = 0.0f, maxOutputTokens = 80,
        ))
        val line = reply.lines().map { it.trim().trim('*', '`', '-', ' ') }.firstOrNull { it.isNotBlank() } ?: return null
        val m = Regex("""^(?:PROBLEM|ISSUE|WRONG|BUG|NO)\b\s*[:\-–—]?\s*(.*)$""", RegexOption.IGNORE_CASE).find(line)
        val problem = m?.groupValues?.get(1)?.trim()?.takeIf { it.length >= 6 }
        retitleLast(title + if (problem == null) " · OK" else " · problem found")
        return problem
    }

    /** "CHANGE: make minimize() hide the window instead of calling home()" → the sentence; "NO" → null. */
    private fun parseTriage(reply: String): String? {
        val line = reply.lines().map { it.trim().trim('*', '`', '-', ' ') }.firstOrNull { it.isNotBlank() } ?: return null
        val m = Regex("""^(?:CHANGE|YES|EDIT|FIX)\b\s*[:\-–—]?\s*(.*)$""", RegexOption.IGNORE_CASE).find(line) ?: return null
        val what = m.groupValues[1].trim().ifBlank {
            reply.lines().drop(1).firstOrNull { it.isNotBlank() }?.trim().orEmpty()
        }
        return what.takeIf { it.length >= 8 }
    }

    private suspend fun createFile(task: String, path: String, instruction: String): FileEdit {
        val reply = call(Role.EDITOR, "Creating $path", LlmRequest(
            system = config.prompts.editor,
            prompt = "Task: $task\n" + docsBlock(Budget.DOCS_SHORT_CHARS) +
                "\nWrite the complete content of the new file `$path`: $instruction\n\nThe file:",
            temperature = config.temperature, maxOutputTokens = 1_200,
        ))
        val content = EditParsing.extractCode(reply)
        return if (content.isBlank()) FileEdit(path, FileEdit.Action.CREATE, instruction, FileEdit.Status.SKIPPED, detail = "Editor returned nothing")
        else FileEdit(path, FileEdit.Action.CREATE, instruction, FileEdit.Status.DONE, content = content, detail = "${content.lines().size} lines", diff = Patching.created(content))
    }

    /**
     * The Editor never retypes the file: for each relevant part it names the exact lines to change
     * and what replaces them ([Patching]). Lines it can't find exactly are rejected, and an edit that
     * changes far more than asked is dropped, so a small model can't quietly damage the file.
     */
    /**
     * [parts]: when an agent already checked the file part by part, the parts to change (by chunk
     * index) with what to do in each; only those parts are edited, each with its own instruction.
     */
    private suspend fun editFile(
        task: String, path: String, original: String, instruction: String,
        parts: Map<Int, String>? = null, label: String = "Editing $path",
    ): FileEdit {
        val text = original.replace("\r\n", "\n")
        // "Add #include <X>" / "import a.b.C": the app does it itself — an addition, never a swap.
        EditGuard.includeShortcut(path, text, instruction)?.let { s ->
            DebugLog.d("crew", "edit $path: ${s.detail}")
            if (!s.changed) return FileEdit(path, FileEdit.Action.EDIT, instruction, FileEdit.Status.SKIPPED, detail = s.detail)
            val out = if (original.contains("\r\n")) s.text.replace("\n", "\r\n") else s.text
            return FileEdit(path, FileEdit.Action.EDIT, instruction, FileEdit.Status.DONE, content = out, detail = s.detail, diff = s.diff)
        }
        val chunks = Chunker.split(text.removeSuffix("\n"))
        val outline = if (chunks.size > 1) EditParsing.outline(text) else ""

        // Which parts need the change? Keywords first (free), then ask the model part by part.
        val targets: List<Chunk> = when {
            parts != null -> chunks.filter { it.index in parts.keys }
            chunks.size == 1 -> chunks
            else -> locate(path, chunks, instruction)
        }
        if (targets.isEmpty()) {
            return FileEdit(path, FileEdit.Action.EDIT, instruction, FileEdit.Status.SKIPPED, detail = "no part of the file matched the change")
        }
        var current = text
        var applied = 0
        var changedLines = 0
        val failed = mutableListOf<String>()
        val diff = StringBuilder()
        /** What the Editor proposed and why it was turned down, kept for the answer and follow-ups. */
        val rejected = StringBuilder()
        for (c in targets.take(MAX_REWRITE_CHUNKS)) {
            val partInstruction = parts?.get(c.index) ?: instruction
            val part = if (chunks.size == 1) "" else " · part ${c.index + 1}/${chunks.size} (lines ${c.startLine + 1}–${c.endLine})"
            val prompt = buildString {
                append("Task: ").append(task).append('\n')
                append("Change to make in `").append(path).append("`: ").append(partInstruction).append("\n\n")
                if (chunks.size > 1) {
                    append("Outline of the whole file:\n").append(outline).append("\n\n")
                    append("Part ${c.index + 1} of ${chunks.size} (lines ${c.startLine + 1}–${c.endLine}):\n")
                }
                append("```\n").append(c.text).append("\n```\n\n")
                append("Write only the change blocks (or NONE if this part needs no change):")
            }
            val reply = call(Role.EDITOR, "$label$part", LlmRequest(
                system = PATCH_EDITOR,
                prompt = clip(prompt, Budget.PROMPT_CHARS + 1_500),
                temperature = 0.1f,
                maxOutputTokens = 700,
            ))
            var blocks = Patching.parse(reply)
            if (blocks.isEmpty()) {
                if (reply.trim().trim('`').trim().uppercase().startsWith("NONE") || reply.isBlank()) continue
                // Wrote code, but not as change blocks: show the format once more.
                retitleLast("$label$part · not in change-block format, retrying")
                val again = call(Role.EDITOR, "$label$part · retry (format)", LlmRequest(
                    system = PATCH_EDITOR,
                    prompt = clip(prompt, Budget.PROMPT_CHARS) +
                        "\n\nYour previous answer was code, not change blocks, so it could not be applied:\n" + clip(reply, 800) +
                        "\n\nRewrite it as change blocks. Copy the existing lines it belongs to into FIND exactly, " +
                        "and put those same lines plus your new code into REPLACE:\n" +
                        "<<<<<<< FIND\n(existing lines)\n=======\n(existing lines + new code)\n>>>>>>> REPLACE",
                    temperature = 0.2f,
                    maxOutputTokens = 900,
                ))
                blocks = Patching.parse(again)
                if (blocks.isEmpty()) {
                    failed += "change 0: the Editor didn't write change blocks (twice)"
                    rejected.append("@@ rejected: not in change-block format\n").append(clip(reply, 400)).append('\n')
                    continue
                }
            }
            val guard: (Patching.Block) -> String? = { b ->
                EditGuard.checkBlock(path, b.find, b.replace, partInstruction)
                    ?: currentArchive?.let { a -> EditGuard.checkCalls(b.find, b.replace, a.files) }
            }
            var r = Patching.apply(current, blocks, check = guard)
            // Everything rejected: tell the Editor why and let it try once more.
            if (r.applied == 0 && r.alreadyDone == 0 && r.failed.isNotEmpty()) {
                rejected.append(Patching.describeRejected(blocks, r.failed))
                retitleLast("$label$part · rejected (${r.failed.first().substringAfter(": ")}), retrying")
                val retry = call(Role.EDITOR, "$label$part · retry", LlmRequest(
                    system = PATCH_EDITOR,
                    prompt = clip(prompt, Budget.PROMPT_CHARS) +
                        "\n\nYour previous answer was rejected:\n" + r.failed.joinToString("\n") { "- $it" } +
                        "\nYou wrote:\n" + clip(reply, 1_000) +
                        "\n\nFix it. " + (if (r.failed.any { "same as FIND" in it })
                            "Keep FIND exactly as it is. In REPLACE write the NEW version of those lines that does this: " +
                                partInstruction + ". REPLACE must contain new or changed code. "
                        else "REPLACE must be different from FIND and keep every FIND line that should stay. ") +
                        "Write only the corrected change blocks:",
                    temperature = 0.5f,
                    maxOutputTokens = 700,
                ))
                val again = Patching.parse(retry)
                if (again.isNotEmpty()) {
                    val r2 = Patching.apply(current, again, check = guard)
                    if (r2.applied > 0) { blocks = again; r = r2 } else {
                        rejected.append(Patching.describeRejected(again, r2.failed))
                        r = r2
                    }
                }
            }
            // Review: a second look at just this part — the original, the change, the instruction.
            if (r.applied > 0) {
                val reviewLabel = if ("editing" in label) label.replace("editing", "reviewing") else "Reviewing $path"
                val problem = reviewPart(task, path, c.text, r.diff, partInstruction, "$reviewLabel$part")
                if (problem != null) {
                    rejected.append("@@ rejected by review: ").append(problem).append('\n').append(r.diff).append('\n')
                    val fix = call(Role.EDITOR, "$label$part · fixing after review", LlmRequest(
                        system = PATCH_EDITOR,
                        prompt = clip(prompt, Budget.PROMPT_CHARS) +
                            "\n\nYou proposed this change:\n" + clip(r.diff, 1_000) +
                            "\nThe reviewer found a problem: " + problem +
                            "\n\nWrite corrected change blocks against the part shown above (FIND copied from it exactly):",
                        temperature = 0.2f,
                        maxOutputTokens = 900,
                    ))
                    val fixed = Patching.parse(fix).let { if (it.isEmpty()) null else Patching.apply(current, it, check = guard) }
                    val second = fixed?.takeIf { it.applied > 0 }?.let { f ->
                        reviewPart(task, path, c.text, f.diff, partInstruction, "$reviewLabel$part · again")
                    }
                    if (fixed != null && fixed.applied > 0 && second == null) {
                        r = fixed
                    } else {
                        val why = second ?: problem
                        failed += "change ${if (fixed == null) 1 else fixed.applied}: review: $why"
                        retitleLast("$label$part · dropped after review")
                        r = r.copy(applied = 0)
                    }
                }
            }
            failed += r.failed
            if (r.applied > 0) {
                current = r.text
                applied += r.applied
                changedLines += r.changedLines
                if (diff.isNotEmpty()) diff.append('\n')
                diff.append(r.diff)
            }
            retitleLast("$label$part · ${r.applied} of ${blocks.size} change${if (blocks.size == 1) "" else "s"} fit" +
                if (r.failed.isNotEmpty()) " (${r.failed.size} rejected)" else "")
        }
        DebugLog.d("crew", "edit $path: applied $applied, $changedLines lines changed, rejected ${failed.size}: ${failed.joinToString(" | ")}")

        if (applied == 0) {
            return FileEdit(
                path, FileEdit.Action.EDIT, instruction, FileEdit.Status.SKIPPED,
                detail = if (failed.isNotEmpty()) "rejected, nothing was changed: " + failed.distinct().take(3).joinToString("; ") { it.substringAfter(": ") }
                else "no changes were needed",
                diff = rejected.toString().trimEnd(),
            )
        }
        // Damage check: a request touches a few lines; dozens changed means the model went astray.
        val fileLines = text.split("\n").size
        val limit = maxOf(30, fileLines / 5)
        if (changedLines > limit) {
            return FileEdit(
                path, FileEdit.Action.EDIT, instruction, FileEdit.Status.SKIPPED,
                detail = "dropped: it changed $changedLines lines (limit $limit for this file), far more than the request needs",
                diff = diff.toString(),
            )
        }
        // Syntax check: the brackets must balance the way they did before the change.
        EditGuard.checkFile(path, text, current, instruction)?.let { why ->
            DebugLog.d("crew", "edit $path: dropped by file check: $why")
            return FileEdit(
                path, FileEdit.Action.EDIT, instruction, FileEdit.Status.SKIPPED,
                detail = "dropped: $why", diff = diff.toString(),
            )
        }
        val result = if (original.contains("\r\n")) current.replace("\n", "\r\n") else current
        val detail = "$applied change${if (applied == 1) "" else "s"}, $changedLines line${if (changedLines == 1) "" else "s"}" +
            if (failed.isNotEmpty()) " · ${failed.size} rejected (${failed.first().substringAfter(": ")})" else ""
        return FileEdit(path, FileEdit.Action.EDIT, instruction, FileEdit.Status.DONE, content = result, detail = detail, diff = diff.toString())
    }

    private suspend fun locate(path: String, chunks: List<Chunk>, instruction: String): List<Chunk> {
        // The most specific keyword (fewest matching parts) usually pinpoints where the change goes.
        val hits = EditParsing.keywords(instruction)
            .map { k -> chunks.filter { c -> c.text.contains(k, ignoreCase = true) } }
            .filter { it.isNotEmpty() }
            .minByOrNull { it.size }
            .orEmpty()
        run {
            if (hits.isNotEmpty() && hits.size <= MAX_REWRITE_CHUNKS && hits.size < chunks.size) {
                val step = startStep(Role.EDITOR, "Locating in $path")
                endStep(step, "Matched by name: parts ${hits.joinToString { (it.index + 1).toString() }} of ${chunks.size}", StepStatus.DONE)
                return hits
            }
        }
        val found = mutableListOf<Chunk>()
        for (c in chunks.take(MAX_LOCATE_CHUNKS)) {
            val reply = call(Role.EDITOR, "Locating in $path · part ${c.index + 1}/${chunks.size}", LlmRequest(
                system = "You decide whether a part of a file must change. Reply with exactly one word: YES or NO.",
                prompt = "Change to make: $instruction\n\nPart ${c.index + 1} of ${chunks.size} of `$path` " +
                    "(lines ${c.startLine + 1}–${c.endLine}):\n```\n${clip(c.text, 5_500)}\n```\n\nMust this part change? YES or NO:",
                temperature = 0f, topK = 1, maxOutputTokens = 3,
            ), stream = false)
            if (reply.trim().uppercase().startsWith("Y")) found += c
            if (found.size >= MAX_REWRITE_CHUNKS) break
        }
        return found
    }

    private fun editSummary(archive: ArchiveInfo, edits: List<FileEdit>): String = buildString {
        val done = edits.count { it.status == FileEdit.Status.DONE }
        if (done > 0) {
            append("Proposed changes to $done file${if (done == 1) "" else "s"} in **${archive.name}**. Check them below, then tap **Apply & pack** to build the new zip.\n\n")
        } else {
            append("No files were changed in **${archive.name}**.\n\n")
        }
        edits.forEach { e ->
            val icon = when {
                e.status == FileEdit.Status.SKIPPED -> "⏭"
                e.action == FileEdit.Action.CREATE -> "➕"
                e.action == FileEdit.Action.DELETE -> "🗑"
                else -> "✏️"
            }
            append("- $icon `${e.path}` — ${e.instruction}")
            if (e.detail.isNotBlank()) append(" *(${e.detail})*")
            append('\n')
        }
    }

    private suspend fun plan(task: String): List<String> {
        val reply = call(Role.PLANNER, "Planning", LlmRequest(
            system = config.prompts.planner,
            prompt = "Task: $task\n" + docsBlock(Budget.DOCS_SHORT_CHARS) +
                "\nWrite at most ${config.maxSubtasks} subtasks as a numbered list:",
            temperature = 0.2f, maxOutputTokens = 200,
        ))
        val subtasks = PlanParser.parse(reply, task, config.maxSubtasks)
        replaceLastText(subtasks.mapIndexed { i, s -> "${i + 1}. $s" }.joinToString("\n"))
        retitleLast("Plan · ${subtasks.size} subtask${if (subtasks.size == 1) "" else "s"}")
        return subtasks
    }

    private suspend fun work(
        task: String,
        subtask: String?,
        index: Int,
        total: Int,
        notes: List<Pair<String, String>>,
        asFinal: Boolean,
    ): String {
        val title = if (subtask == null) "Answering" else "Worker ${index + 1}/$total · ${clip(subtask, 60)}"
        val system = buildString {
            append(if (asFinal) config.prompts.chat else config.prompts.worker)
            if (config.useTools) { append("\n\n"); append(tools.manual()) }
        }
        val context = buildString {
            if (subtask != null) {
                appendLine("Overall task: $task")
                if (notes.isNotEmpty()) {
                    appendLine()
                    appendLine("What teammates already found:")
                    appendLine(formatNotes(notes, Budget.ALL_FINDINGS_CHARS / 2))
                }
                append(docsBlock(Budget.DOCS_CHARS))
                appendLine()
                append("Your subtask: $subtask")
            } else {
                append(task)
                append(docsBlock(Budget.DOCS_CHARS))
            }
        }

        var reply = call(
            Role.WORKER, title,
            LlmRequest(system, clip(context, Budget.PROMPT_CHARS), config.temperature),
            mirrorToFinal = asFinal,
        )
        if (!config.useTools) return reply

        // Up to two rounds of tool use (ReAct-style, but in a format a small model can follow).
        val transcript = StringBuilder()
        repeat(2) { round ->
            val calls = ToolParser.parse(reply)
            if (calls.isEmpty()) return reply
            transcript.appendLine(if (round == 0) "\n\nTool results:" else "")
            for (c in calls) {
                val result = runTool(c)
                transcript.appendLine("${c.name}(${c.argument}) → $result")
            }
            val leftover = ToolParser.stripCalls(reply)
            val followUp = buildString {
                append(context)
                if (leftover.isNotBlank()) { append("\n\nYour draft so far: "); append(clip(leftover, 600)) }
                append(transcript)
                append("\nNow write your answer using these results. Only write another CALL line if you truly need one more tool.")
            }
            reply = call(
                Role.WORKER, "$title · with tool results",
                LlmRequest(system, clip(followUp, Budget.PROMPT_CHARS), config.temperature),
                mirrorToFinal = asFinal,
            )
        }
        return ToolParser.stripCalls(reply).ifBlank { reply }
    }

    private suspend fun runTool(c: ToolCall): String {
        val step = startStep(Role.TOOL, "${c.name}${if (c.argument.isNotBlank()) ": ${clip(c.argument, 40)}" else ""}")
        val result = tools.execute(c)
        endStep(step, result, if (result.startsWith("error")) StepStatus.ERROR else StepStatus.DONE)
        return result
    }

    private suspend fun critique(task: String, notes: List<Pair<String, String>>): String {
        val reply = call(Role.CRITIC, "Reviewing", LlmRequest(
            system = config.prompts.critic,
            prompt = "Task: $task\n" + docsBlock(Budget.DOCS_SHORT_CHARS) + "\nTeam notes:\n${formatNotes(notes, Budget.ALL_FINDINGS_CHARS)}\n\nProblems (or LOOKS GOOD):",
            temperature = 0.1f, maxOutputTokens = 160,
        ))
        val ok = CriticParser.approves(reply)
        retitleLast(if (ok) "Review · looks good" else "Review · found issues")
        return if (ok) "" else reply
    }

    private suspend fun write(task: String, notes: List<Pair<String, String>>, critique: String): String {
        val prompt = buildString {
            appendLine("Task: $task")
            appendLine()
            append(docsBlock(Budget.DOCS_SHORT_CHARS))
            appendLine()
            appendLine("Team notes:")
            appendLine(formatNotes(notes, Budget.ALL_FINDINGS_CHARS))
            if (critique.isNotBlank()) {
                appendLine()
                appendLine("Critic's problems to fix:")
                appendLine(clip(critique, 700))
            }
            appendLine()
            append("Final answer:")
        }
        return call(Role.WRITER, "Writing the answer", LlmRequest(
            config.prompts.writer, clip(prompt, Budget.PROMPT_CHARS), config.temperature, maxOutputTokens = 640,
        ), mirrorToFinal = true)
    }

    private fun docsBlock(max: Int): String =
        if (docs.isBlank()) "" else "\nAttached files (from the user):\n${clip(docs, max)}\n"

    private fun formatNotes(notes: List<Pair<String, String>>, budget: Int): String {
        if (notes.isEmpty()) return "(none yet)"
        val each = (budget / notes.size).coerceIn(200, Budget.FINDING_CHARS)
        return notes.mapIndexed { i, (sub, found) -> "[${i + 1}] $sub\n${clip(found, each)}" }.joinToString("\n\n")
    }

    // ---- step bookkeeping ---------------------------------------------------------------------

    private suspend fun call(
        role: Role,
        title: String,
        request: LlmRequest,
        stream: Boolean = true,
        mirrorToFinal: Boolean = false,
    ): String {
        val step = startStep(role, title)
        _state.update { it.copy(modelCalls = it.modelCalls + 1) }
        DebugLog.d("crew", "step ${step.id} ${role.name} \"$title\" → model call (prompt ${request.prompt.length} chars)")
        val sb = StringBuilder()
        val out = engine.generate(request) { chunk ->
            if (!stream) return@generate
            if (chunk == LlmEngine.RESTART) sb.setLength(0) else sb.append(chunk)
            val text = sb.toString()
            _state.update { s ->
                s.copy(
                    steps = s.steps.map { if (it.id == step.id) it.copy(text = text) else it },
                    finalAnswer = if (mirrorToFinal) text else s.finalAnswer,
                )
            }
        }.trim()
        endStep(step, out, StepStatus.DONE)
        DebugLog.d("crew", "step ${step.id} ${role.name} done in ${clock() - step.startedAt} ms · ${out.length} chars")
        return out
    }

    private companion object {
        const val MAX_REWRITE_CHUNKS = 8

        /** The Editor's instructions for change blocks (fixed: the parser depends on this format). */
        const val PATCH_EDITOR =
            "You are the Editor. You change a file by naming the exact lines to replace.\n" +
                "For each change write one block:\n" +
                "<<<<<<< FIND\n" +
                "lines copied EXACTLY from the file (2 to 10 lines, enough to be unique)\n" +
                "=======\n" +
                "the same lines with the change made\n" +
                ">>>>>>> REPLACE\n" +
                "Rules: copy FIND lines character for character. Change only what the request needs. " +
                "To add something, REPLACE must contain every FIND line plus the new line(s). " +
                "Never delete an #include, import, class or function unless the request says so. " +
                "Do not rewrite or reformat other lines. No explanations. If nothing here needs to change, reply NONE."
        const val MAX_LOCATE_CHUNKS = 20
        /** Triage looks at this many of the best-matching files, with at most this many calls. */
        const val REVIEWER =
            "You review one change to a file. Reply with exactly one line: OK, or PROBLEM: <what is wrong>.\n" +
                "Say PROBLEM only for a real mistake: the change does not do what it was meant to, uses a name, " +
                "argument or value the code doesn't have, leaves something half-done, or changes lines that have " +
                "nothing to do with it. Style and wording are not problems."
        const val TRIAGE_FILES = 6
        const val TRIAGE_CALLS = 12
        val DOC_EXTS = setOf("md", "markdown", "txt", "rst", "adoc")
        val DATA_EXTS = setOf("json", "csv", "html", "htm", "svg", "xml", "log")
        val DOCS_WORDS = Regex("""\b(readme|docs?|documentation|text|translat\w*|typo|wording|changelog|notes|version|json|html)\b""", RegexOption.IGNORE_CASE)
        const val TRIAGE =
            "You check one part of a file for a coding task. Reply with exactly one line:\n" +
                "CHANGE: <what to change in this code, one sentence, naming the function or setting>\n" +
                "or\n" +
                "NO\n" +
                "Say CHANGE only if the code shown here is what makes the task's problem happen or must be edited to fix it."
        /** The user asked for a new file (otherwise the crew only changes existing ones). */
        val CREATE_WORDS = Regex(
            """\b(create|new\s+file|add\s+(a\s+|an\s+)?(new\s+)?(file|script|module|page|readme|test)|write\s+(a\s+)?(new\s+)?(file|script)|generate)\b""",
            RegexOption.IGNORE_CASE,
        )
        val EDIT_WORDS = Regex(
            "\\b(fix|edit|change|modify|update|rewrite|refactor|rename|translate|add|create|remove|delete|replace|comment|format|correct)\\b",
            RegexOption.IGNORE_CASE,
        )
    }

    private fun startStep(role: Role, title: String): Step {
        val step = Step(id = nextStepId++, role = role, title = title, startedAt = clock())
        _state.update { it.copy(steps = it.steps + step) }
        return step
    }

    private fun endStep(step: Step, text: String, status: StepStatus) {
        val now = clock()
        _state.update { s ->
            s.copy(steps = s.steps.map { if (it.id == step.id) it.copy(text = text, status = status, endedAt = now) else it })
        }
    }

    private fun retitleLast(title: String) = _state.update { s ->
        s.copy(steps = s.steps.dropLast(1) + listOfNotNull(s.steps.lastOrNull()?.copy(title = title)))
    }

    private fun replaceLastText(text: String) = _state.update { s ->
        s.copy(steps = s.steps.dropLast(1) + listOfNotNull(s.steps.lastOrNull()?.copy(text = text)))
    }

    private fun markRunningSteps(status: StepStatus, note: String) {
        val now = clock()
        _state.update { s ->
            s.copy(steps = s.steps.map {
                if (it.status == StepStatus.RUNNING) it.copy(status = status, endedAt = now, text = it.text.ifBlank { note }) else it
            })
        }
    }

    private fun finish(answer: String) {
        _state.update { it.copy(finalAnswer = answer.trim(), phase = Phase.DONE, endedAt = clock()) }
    }
}
