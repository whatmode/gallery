package ua.volodymyr.nanocrew.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import ua.volodymyr.nanocrew.core.AgentPrompts
import ua.volodymyr.nanocrew.core.ChatMessage
import ua.volodymyr.nanocrew.core.CrewConfig
import ua.volodymyr.nanocrew.core.FileEdit
import ua.volodymyr.nanocrew.core.NoteStore
import ua.volodymyr.nanocrew.core.Phase
import ua.volodymyr.nanocrew.core.Role
import ua.volodymyr.nanocrew.core.RunState
import ua.volodymyr.nanocrew.core.Step
import ua.volodymyr.nanocrew.core.StepStatus
import java.io.File

/** Everything is stored privately on the phone; nothing leaves it. */
class Storage(context: Context) {
    private val dir = File(context.filesDir, "crew").apply { mkdirs() }
    private val runsDir = File(dir, "runs").apply { mkdirs() }
    private val prefs = context.getSharedPreferences("nanocrew_crew", Context.MODE_PRIVATE)
    private val lock = Mutex()

    // ---- runs ---------------------------------------------------------------------------------

    suspend fun saveRun(run: RunState) = io {
        File(runsDir, "${run.id}.json").writeText(runToJson(run).toString())
    }

    suspend fun loadRuns(): List<RunState> = io {
        runsDir.listFiles { f -> f.extension == "json" }.orEmpty()
            .mapNotNull { f -> runCatching { runFromJson(JSONObject(f.readText())) }.getOrNull() }
            .sortedByDescending { it.startedAt }
    }

    suspend fun deleteRun(id: String) = io {
        File(runsDir, "$id.json").delete()
        File(outputsDir, id).deleteRecursively()
        Unit
    }

    /** Edited zips live here, one folder per run. */
    val outputsDir = File(dir, "outputs").apply { mkdirs() }
    fun outputFile(runId: String, name: String) = File(File(outputsDir, runId), name)

    suspend fun clearRuns() = io {
        runsDir.listFiles().orEmpty().forEach { it.delete() }
        outputsDir.listFiles().orEmpty().forEach { it.deleteRecursively() }
    }

    // ---- chat ---------------------------------------------------------------------------------

    private val chatFile = File(dir, "chat.json")

    suspend fun saveChat(messages: List<ChatMessage>) = io {
        val arr = JSONArray()
        messages.takeLast(200).forEach {
            arr.put(
                JSONObject().put("u", it.fromUser).put("t", it.text).put("at", it.at)
                    .put("a", JSONArray(it.attachments)).put("c", it.fileContext),
            )
        }
        chatFile.writeText(arr.toString())
    }

    suspend fun loadChat(): List<ChatMessage> = io {
        if (!chatFile.exists()) return@io emptyList()
        runCatching {
            val arr = JSONArray(chatFile.readText())
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                ChatMessage(
                    o.getBoolean("u"), o.getString("t"), o.getLong("at"),
                    attachments = o.optJSONArray("a")?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty(),
                    fileContext = o.optString("c", ""),
                )
            }
        }.getOrDefault(emptyList())
    }

    // ---- notes (the agents' long-term memory) ----------------------------------------------

    val notes: NoteStore = object : NoteStore {
        private val file = File(dir, "notes.json")
        override suspend fun add(text: String) = io {
            val arr = readNotes(file)
            arr.put(text)
            file.writeText(arr.toString())
        }
        override suspend fun all(): List<String> = io {
            val arr = readNotes(file)
            (0 until arr.length()).map { arr.getString(it) }
        }
    }

    suspend fun clearNotes() = io { File(dir, "notes.json").delete(); Unit }

    private fun readNotes(file: File): JSONArray =
        if (file.exists()) runCatching { JSONArray(file.readText()) }.getOrDefault(JSONArray()) else JSONArray()

    // ---- settings -----------------------------------------------------------------------------

    fun loadConfig(): CrewConfig {
        val d = AgentPrompts()
        return CrewConfig(
            maxSubtasks = prefs.getInt("maxSubtasks", 3),
            useCritic = prefs.getBoolean("useCritic", true),
            useTools = prefs.getBoolean("useTools", true),
            autoRoute = prefs.getBoolean("autoRoute", true),
            temperature = prefs.getFloat("temperature", 0.3f),
            model = runCatching { ua.volodymyr.nanocrew.core.ModelChoice.valueOf(prefs.getString("model", "NANO")!!) }
                .getOrDefault(ua.volodymyr.nanocrew.core.ModelChoice.NANO),
            prompts = AgentPrompts(
                router = prefs.getString("p_router", null) ?: d.router,
                planner = prefs.getString("p_planner", null) ?: d.planner,
                worker = prefs.getString("p_worker", null) ?: d.worker,
                critic = prefs.getString("p_critic", null) ?: d.critic,
                writer = prefs.getString("p_writer", null) ?: d.writer,
                chat = prefs.getString("p_chat", null) ?: d.chat,
                reader = prefs.getString("p_reader", null) ?: d.reader,
                vision = prefs.getString("p_vision", null) ?: d.vision,
                editPlanner = prefs.getString("p_editPlanner", null) ?: d.editPlanner,
                editor = prefs.getString("p_editor", null) ?: d.editor,
            ),
        )
    }

    fun saveConfig(c: CrewConfig) {
        prefs.edit()
            .putInt("maxSubtasks", c.maxSubtasks)
            .putBoolean("useCritic", c.useCritic)
            .putBoolean("useTools", c.useTools)
            .putBoolean("autoRoute", c.autoRoute)
            .putFloat("temperature", c.temperature)
            .putString("model", c.model.name)
            .putString("p_router", c.prompts.router)
            .putString("p_planner", c.prompts.planner)
            .putString("p_worker", c.prompts.worker)
            .putString("p_critic", c.prompts.critic)
            .putString("p_writer", c.prompts.writer)
            .putString("p_chat", c.prompts.chat)
            .putString("p_reader", c.prompts.reader)
            .putString("p_vision", c.prompts.vision)
            .putString("p_editPlanner", c.prompts.editPlanner)
            .putString("p_editor", c.prompts.editor)
            .apply()
    }

    // ---- json ---------------------------------------------------------------------------------

    private fun runToJson(r: RunState) = JSONObject()
        .put("id", r.id).put("task", r.task).put("final", r.finalAnswer)
        .put("phase", r.phase.name).put("error", r.error ?: "")
        .put("startedAt", r.startedAt).put("endedAt", r.endedAt ?: 0L)
        .put("calls", r.modelCalls)
        .put("files", JSONArray(r.attachments))
        .put("archiveId", r.archiveId ?: "").put("archiveName", r.archiveName ?: "").put("output", r.outputPath ?: "")
        .put("digest", r.digest)
        .put("earlier", JSONArray().apply {
            r.earlier.forEach { t -> put(JSONObject().put("task", t.task).put("answer", t.answer).put("files", JSONArray(t.attachments))) }
        })
        .put("edits", JSONArray().apply {
            r.edits.forEach { e ->
                put(JSONObject().put("path", e.path).put("action", e.action.name).put("instruction", e.instruction)
                    .put("status", e.status.name).put("detail", e.detail))
            }
        })
        .put("steps", JSONArray().apply {
            r.steps.forEach { s ->
                put(JSONObject()
                    .put("id", s.id).put("role", s.role.name).put("title", s.title).put("text", s.text)
                    .put("status", s.status.name).put("startedAt", s.startedAt).put("endedAt", s.endedAt ?: 0L))
            }
        })

    private fun runFromJson(o: JSONObject): RunState {
        val arr = o.getJSONArray("steps")
        val steps = (0 until arr.length()).map { i ->
            val s = arr.getJSONObject(i)
            Step(
                id = s.getInt("id"),
                role = Role.valueOf(s.getString("role")),
                title = s.getString("title"),
                text = s.getString("text"),
                status = StepStatus.valueOf(s.getString("status")),
                startedAt = s.getLong("startedAt"),
                endedAt = s.getLong("endedAt").takeIf { it > 0 },
            )
        }
        return RunState(
            id = o.getString("id"),
            task = o.getString("task"),
            steps = steps,
            finalAnswer = o.getString("final"),
            phase = Phase.valueOf(o.getString("phase")),
            error = o.getString("error").ifBlank { null },
            startedAt = o.getLong("startedAt"),
            endedAt = o.getLong("endedAt").takeIf { it > 0 },
            modelCalls = o.optInt("calls", 0),
            attachments = o.optJSONArray("files")?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty(),
            archiveId = o.optString("archiveId", "").ifBlank { null },
            archiveName = o.optString("archiveName", "").ifBlank { null },
            outputPath = o.optString("output", "").ifBlank { null },
            digest = o.optString("digest", ""),
            earlier = o.optJSONArray("earlier")?.let { a ->
                (0 until a.length()).map { i ->
                    val t = a.getJSONObject(i)
                    ua.volodymyr.nanocrew.core.Turn(
                        t.optString("task"), t.optString("answer"),
                        t.optJSONArray("files")?.let { f -> (0 until f.length()).map { f.getString(it) } }.orEmpty(),
                    )
                }
            }.orEmpty(),
            edits = o.optJSONArray("edits")?.let { a ->
                (0 until a.length()).map { i ->
                    val e = a.getJSONObject(i)
                    FileEdit(
                        e.getString("path"), FileEdit.Action.valueOf(e.getString("action")), e.optString("instruction"),
                        FileEdit.Status.valueOf(e.getString("status")), detail = e.optString("detail"),
                    )
                }
            }.orEmpty(),
        )
    }

    private suspend fun <T> io(block: () -> T): T = withContext(Dispatchers.IO) { lock.withLock { block() } }
}
