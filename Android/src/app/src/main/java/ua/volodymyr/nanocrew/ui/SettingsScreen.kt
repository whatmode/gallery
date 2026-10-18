package ua.volodymyr.nanocrew.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import ua.volodymyr.nanocrew.core.CrewConfig
import ua.volodymyr.nanocrew.core.Role
import ua.volodymyr.nanocrew.engine.ModelStatus
import kotlin.math.roundToInt

@Composable
fun SettingsScreen(
    config: CrewConfig,
    modelStatus: ModelStatus,
    noteCount: Int,
    onChange: ((CrewConfig) -> CrewConfig) -> Unit,
    onResetPrompts: () -> Unit,
    onClearNotes: () -> Unit,
    onCheckModel: () -> Unit,
    modifier: Modifier = Modifier,
    debugLines: List<String> = emptyList(),
    debugBytes: Long = 0,
    onShareDebug: () -> Unit = {},
    onClearDebug: () -> Unit = {},
    memoryText: String = "",
    onFreeMemory: (() -> Unit)? = null,
) {
    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        SectionLabel("Model")
        when (modelStatus) {
            is ModelStatus.Ready -> {
                Info("Model", modelStatus.modelName)
                Info("Context", "${modelStatus.tokenLimit} tokens per call")
            }
            is ModelStatus.Preparing -> Text(modelStatus.title + "\n" + modelStatus.detail, style = MaterialTheme.typography.bodyMedium)
            is ModelStatus.Failed -> Text(modelStatus.message, style = MaterialTheme.typography.bodyMedium)
            else -> Text("Loading…", style = MaterialTheme.typography.bodyMedium)
        }
        Text(
            "Pick the model with the chip at the top. Its ⚙ settings (top right) set the context: " +
                "Max tokens 16000 lets every agent see ~4× more of your files than 4000.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TextButton(onClick = onCheckModel) { Text("Re-check model") }

        if (onFreeMemory != null) {
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            SectionLabel("Phone memory")
            Text(memoryText, style = MaterialTheme.typography.bodyMedium)
            Text(
                "Free memory unloads every other model this app has loaded (Chat, Agent Skills…) and " +
                    "releases what it can. Android doesn't let one app close others: to free more, swipe " +
                    "away apps in Recents.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(onClick = onFreeMemory) { Text("Free memory") }
        }

        HorizontalDivider(Modifier.padding(vertical = 8.dp))
        SectionLabel("Crew")
        Toggle("Auto-route simple requests", "Skip the full crew for quick questions", config.autoRoute) { v -> onChange { it.copy(autoRoute = v) } }
        Toggle("Critic review", "One extra pass that checks the workers' notes", config.useCritic) { v -> onChange { it.copy(useCritic = v) } }
        Toggle("Tools", "Calculator, clock and notes for the workers", config.useTools) { v -> onChange { it.copy(useTools = v) } }
        Toggle(
            "Think before editing",
            "Gemma thinks first when it edits and reviews files. More careful, but each of those steps takes a few times longer.",
            config.think,
        ) { v -> onChange { it.copy(think = v) } }

        Spacer(Modifier.height(4.dp))
        Text("Max subtasks: ${config.maxSubtasks}", style = MaterialTheme.typography.bodyLarge)
        Slider(
            value = config.maxSubtasks.toFloat(),
            onValueChange = { v -> onChange { it.copy(maxSubtasks = v.roundToInt()) } },
            valueRange = 1f..5f,
            steps = 3,
        )
        Text(
            "More subtasks = more thorough, but each one is another model call (and AICore limits calls per app).",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(4.dp))
        Text("Creativity: %.1f".format(config.temperature), style = MaterialTheme.typography.bodyLarge)
        Slider(
            value = config.temperature,
            onValueChange = { v -> onChange { it.copy(temperature = (v * 10).roundToInt() / 10f) } },
            valueRange = 0f..1f,
        )

        HorizontalDivider(Modifier.padding(vertical = 8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            SectionLabel("Agent instructions", Modifier.weight(1f))
            TextButton(onClick = onResetPrompts) { Text("Reset") }
        }
        Text(
            "Every agent is the same Gemini Nano with a different instruction. Keep them short — it's a small model.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        PromptField(Role.ROUTER.label, config.prompts.router) { v -> onChange { it.copy(prompts = it.prompts.copy(router = v)) } }
        PromptField(Role.PLANNER.label, config.prompts.planner) { v -> onChange { it.copy(prompts = it.prompts.copy(planner = v)) } }
        PromptField(Role.WORKER.label, config.prompts.worker) { v -> onChange { it.copy(prompts = it.prompts.copy(worker = v)) } }
        PromptField(Role.CRITIC.label, config.prompts.critic) { v -> onChange { it.copy(prompts = it.prompts.copy(critic = v)) } }
        PromptField(Role.WRITER.label, config.prompts.writer) { v -> onChange { it.copy(prompts = it.prompts.copy(writer = v)) } }
        PromptField("Chat / simple answers", config.prompts.chat) { v -> onChange { it.copy(prompts = it.prompts.copy(chat = v)) } }
        PromptField("${Role.READER.label} (long files)", config.prompts.reader) { v -> onChange { it.copy(prompts = it.prompts.copy(reader = v)) } }
        PromptField("Vision (photos, scans)", config.prompts.vision) { v -> onChange { it.copy(prompts = it.prompts.copy(vision = v)) } }
        PromptField("Edit planner (zip edits)", config.prompts.editPlanner) { v -> onChange { it.copy(prompts = it.prompts.copy(editPlanner = v)) } }
        PromptField("${Role.EDITOR.label} (rewrites files)", config.prompts.editor) { v -> onChange { it.copy(prompts = it.prompts.copy(editor = v)) } }

        HorizontalDivider(Modifier.padding(vertical = 8.dp))
        SectionLabel("Agent memory")
        Text(
            "$noteCount saved note${if (noteCount == 1) "" else "s"} — agents save and search these with the notes tools.",
            style = MaterialTheme.typography.bodyMedium,
        )
        OutlinedButton(onClick = onClearNotes, enabled = noteCount > 0) { Text("Clear notes") }

        HorizontalDivider(Modifier.padding(vertical = 8.dp))
        SectionLabel("Debug log")
        Text(
            "Every model call, agent step, retry, pause, file import and UI freeze is written here " +
                "(${"%.0f".format(debugBytes / 1024.0)} KB). If something gets stuck or goes wrong, share the log and send it to Claude.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            androidx.compose.material3.Button(onClick = onShareDebug) { Text("Share debug log") }
            OutlinedButton(onClick = onClearDebug) { Text("Clear") }
        }
        var showLog by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(false) }
        TextButton(onClick = { showLog = !showLog }) { Text(if (showLog) "Hide latest lines" else "Show latest lines") }
        if (showLog) {
            androidx.compose.material3.Surface(
                shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceContainer,
            ) {
                androidx.compose.foundation.text.selection.SelectionContainer {
                    Text(
                        debugLines.takeLast(60).joinToString("\n").ifBlank { "(empty)" },
                        style = MonoSmall,
                        modifier = Modifier.padding(10.dp),
                    )
                }
            }
        }
        Spacer(Modifier.height(24.dp))
        Text(
            "Nano Crew has no internet permission. Tasks, answers and notes never leave this phone.",
            style = MonoSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun Info(label: String, value: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(label, Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value)
    }
}

@Composable
private fun Toggle(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun PromptField(label: String, value: String, onSave: (String) -> Unit) {
    var text by remember(value) { mutableStateOf(value) }
    OutlinedTextField(
        value = text,
        onValueChange = { text = it; if (it.isNotBlank()) onSave(it) },
        label = { Text(label) },
        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
        textStyle = MaterialTheme.typography.bodySmall,
        minLines = 2,
        maxLines = 8,
    )
}
