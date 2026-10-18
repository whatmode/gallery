package ua.volodymyr.nanocrew.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AssistChip
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ua.volodymyr.nanocrew.core.Phase
import ua.volodymyr.nanocrew.core.Role
import ua.volodymyr.nanocrew.core.RunState
import ua.volodymyr.nanocrew.core.Step
import ua.volodymyr.nanocrew.core.StepStatus
import ua.volodymyr.nanocrew.engine.ModelStatus

private val examples = listOf(
    "Plan a 3-day trip to Lviv on 6,000 UAH: transport, stay, food and what to see",
    "Compare learning Kotlin vs Flutter for my next Android app, then recommend one",
    "Write a short polite email asking my landlord to fix the heating this week",
    "I run 5 km in 31:20. What's my pace per km, and a 4-week plan to get under 30 min?",
)

@Composable
fun CrewScreen(
    run: RunState?,
    modelStatus: ModelStatus,
    prefill: String?,
    onPrefillUsed: () -> Unit,
    onStart: (String) -> Unit,
    onStop: () -> Unit,
    onNew: () -> Unit,
    onCheckModel: () -> Unit,
    onDownload: () -> Unit,
    modifier: Modifier = Modifier,
    modelLog: List<String> = emptyList(),
    files: List<ua.volodymyr.nanocrew.core.Attachment> = emptyList(),
    importing: Boolean = false,
    fileError: String? = null,
    onFilesPicked: (List<android.net.Uri>) -> Unit = {},
    onRemoveFile: (Int) -> Unit = {},
    onDismissFileError: () -> Unit = {},
    onSaveOutput: (RunState, android.net.Uri) -> Unit = { _, _ -> },
    outputMessage: String? = null,
    onDismissOutputMessage: () -> Unit = {},
    waiting: String? = null,
    onApplyReview: (Set<String>) -> Unit = {},
    onDiscardReview: () -> Unit = {},
) {
    var input by rememberSaveable { mutableStateOf("") }
    val pickFiles = rememberFilePicker(onFilesPicked)
    LaunchedEffect(prefill) {
        if (!prefill.isNullOrBlank()) { input = prefill; onPrefillUsed() }
    }
    val ready = modelStatus is ModelStatus.Ready
    val running = run?.phase == Phase.RUNNING

    Column(modifier.fillMaxSize()) {
        ModelGate(modelStatus, onCheckModel, onDownload, log = modelLog)

        Box(Modifier.weight(1f)) {
            if (run == null) {
                IdleCrew(onPick = { input = it })
            } else {
                RunTimeline(run, onSaveOutput, outputMessage, onDismissOutputMessage, waiting, onApplyReview, onDiscardReview)
            }
        }

        if (running) WaitingBanner(waiting)
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        if (run != null && !running) {
            Row(
                Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Your next message continues this task",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { onNew() }) {
                    Icon(Icons.Default.Add, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("New task")
                }
            }
        }
        FileTray(files, importing, fileError, onRemoveFile, onDismissFileError)
        Row(
            Modifier.fillMaxWidth().padding(start = 4.dp, end = 8.dp, top = 8.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AttachButton(onClick = pickFiles, enabled = !running)
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier.weight(1f).heightIn(max = 160.dp),
                placeholder = {
                    Text(
                        when {
                            files.isNotEmpty() -> "What should the crew do with these?"
                            run != null && !running -> "Reply to continue…"
                            else -> "Give the crew a task…"
                        },
                    )
                },
                enabled = !running,
                shape = RoundedCornerShape(20.dp),
            )
            Spacer(Modifier.width(8.dp))
            if (running) {
                FilledIconButton(onClick = onStop, Modifier.size(52.dp)) {
                    Icon(Icons.Default.Close, contentDescription = "Stop")
                }
            } else {
                FilledIconButton(
                    onClick = { onStart(input.trim()); input = "" },
                    enabled = ready && !importing && (input.isNotBlank() || files.isNotEmpty()),
                    modifier = Modifier.size(52.dp),
                ) {
                    Icon(Icons.Default.PlayArrow, contentDescription = "Run crew")
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun IdleCrew(onPick: (String) -> Unit) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp)) {
        item {
            Text("A crew of agents,\nall on your phone.", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(10.dp))
            Text(
                "Attach files with + (text, code, PDF, Word, photos, zip — the crew can even edit a zip and give you a new one) and a Reader goes through them first. " +
                    "A Router sizes up the task, a Planner splits it, Workers solve each part " +
                    "(with a calculator, clock and notes), a Critic checks them, and a Writer delivers the answer. " +
                    "Every agent is Gemini Nano running on the Tensor G4 — offline, private, free.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(18.dp))
            CrewLegend()
            Spacer(Modifier.height(22.dp))
            SectionLabel("Try")
            Spacer(Modifier.height(6.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                examples.forEach { ex ->
                    AssistChip(onClick = { onPick(ex) }, label = { Text(ex, maxLines = 2, overflow = TextOverflow.Ellipsis) })
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CrewLegend() {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(Role.READER, Role.ROUTER, Role.PLANNER, Role.WORKER, Role.TOOL, Role.CRITIC, Role.WRITER, Role.EDITOR).forEach { r ->
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.size(10.dp).clip(CircleShape).background(roleColor(r)))
                Spacer(Modifier.height(4.dp))
                Text(r.label, style = MonoSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun RunTimeline(
    run: RunState,
    onSaveOutput: (RunState, android.net.Uri) -> Unit,
    outputMessage: String?,
    onDismissOutputMessage: () -> Unit,
    waiting: String? = null,
    onApplyReview: (Set<String>) -> Unit = {},
    onDiscardReview: () -> Unit = {},
) {
    val listState = rememberLazyListState()
    val expanded = remember(run.id, run.earlier.size) { mutableStateMapOf<Int, Boolean>() }
    val context = LocalContext.current

    // Follow the newest step while the crew is working.
    LaunchedEffect(run.steps.size, run.phase) {
        if (run.phase == Phase.RUNNING && run.steps.isNotEmpty()) listState.animateScrollToItem(run.steps.size)
        if (run.phase == Phase.DONE) listState.animateScrollToItem(run.steps.size + 1)
    }

    LazyColumn(state = listState, modifier = Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 12.dp)) {
        item {
            Column(Modifier.padding(horizontal = 16.dp)) {
                run.earlier.forEach { turn -> EarlierTurn(turn) }
                SectionLabel(if (run.earlier.isEmpty()) "Task" else "Follow-up")
                Text(run.task, style = MaterialTheme.typography.titleMedium)
                AttachedNames(run.attachments, Modifier.padding(top = 4.dp))
                Spacer(Modifier.height(14.dp))
            }
        }
        items(run.steps, key = { it.id }) { step ->
            val isOpen = expanded[step.id] ?: (step.status == StepStatus.RUNNING || step.role == Role.PLANNER)
            StepRow(
                step, isOpen, isLast = step == run.steps.last(),
                waiting = if (step.status == StepStatus.RUNNING && run.phase == Phase.RUNNING) waiting else null,
            ) { expanded[step.id] = !isOpen }
        }
        item {
            Column(Modifier.padding(16.dp)) {
                RunSummary(run)
                if (run.finalAnswer.isNotBlank()) {
                    Spacer(Modifier.height(12.dp))
                    Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                        Column(Modifier.padding(16.dp).animateContentSize()) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                SectionLabel("Answer", Modifier.weight(1f))
                                if (run.phase == Phase.DONE) {
                                    IconButton(onClick = { shareText(context, run.finalAnswer) }, Modifier.size(32.dp)) {
                                        Icon(Icons.Default.Share, "Share", Modifier.size(18.dp))
                                    }
                                }
                            }
                            MarkdownText(run.finalAnswer)
                            if (run.reviewPending) {
                                Spacer(Modifier.height(12.dp))
                                ReviewChanges(run, onApplyReview, onDiscardReview)
                            }
                            if (run.outputPath != null) {
                                Spacer(Modifier.height(12.dp))
                                OutputActions(run, onSaveOutput, outputMessage, onDismissOutputMessage)
                            }
                        }
                    }
                }
            }
        }
    }
}

/** An earlier exchange of this conversation: the question, and the answer folded to a few lines. */
@Composable
private fun EarlierTurn(turn: ua.volodymyr.nanocrew.core.Turn) {
    var open by remember(turn) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(bottom = 14.dp)) {
        SectionLabel("Task")
        Text(turn.task, style = MaterialTheme.typography.titleSmall)
        AttachedNames(turn.attachments, Modifier.padding(top = 2.dp))
        Spacer(Modifier.height(8.dp))
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = MaterialTheme.colorScheme.surfaceContainer,
            modifier = Modifier.fillMaxWidth().clickable { open = !open },
        ) {
            Column(Modifier.padding(12.dp).animateContentSize()) {
                SectionLabel("Answer")
                if (open) {
                    MarkdownText(turn.answer.ifBlank { "(no answer)" }, style = MaterialTheme.typography.bodySmall)
                } else {
                    Text(
                        turn.answer.ifBlank { "(no answer)" }.replace('\n', ' '),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        HorizontalDivider(Modifier.padding(top = 14.dp), color = MaterialTheme.colorScheme.outlineVariant)
    }
}

@Composable
private fun RunSummary(run: RunState) {
    val text = when (run.phase) {
        Phase.RUNNING -> "Working · ${run.modelCalls} model call${if (run.modelCalls == 1) "" else "s"}"
        Phase.DONE -> "Done in ${formatDuration((run.endedAt ?: run.startedAt) - run.startedAt)} · ${run.modelCalls} on-device calls"
        Phase.CANCELLED -> "Stopped"
        Phase.FAILED -> "Failed: ${run.error ?: "unknown error"}"
    }
    Text(
        text,
        style = MonoSmall,
        color = if (run.phase == Phase.FAILED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun StepRow(step: Step, open: Boolean, isLast: Boolean, waiting: String? = null, onToggle: () -> Unit) {
    val color = roleColor(step.role)
    Row(
        Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .clickable(onClick = onToggle)
            .padding(horizontal = 16.dp),
    ) {
        // Timeline rail
        Column(Modifier.width(18.dp).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(Modifier.height(6.dp))
            Box(
                Modifier.size(if (step.role == Role.TOOL) 8.dp else 11.dp).clip(CircleShape)
                    .background(if (step.status == StepStatus.ERROR) MaterialTheme.colorScheme.error else color),
            )
            if (!isLast) {
                Box(Modifier.width(1.5.dp).weight(1f).background(MaterialTheme.colorScheme.outlineVariant))
            }
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f).padding(bottom = 14.dp).animateContentSize()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(step.role.label.uppercase(), style = MonoSmall, color = color)
                Spacer(Modifier.width(8.dp))
                Text(
                    step.title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f),
                    maxLines = if (open) 3 else 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.width(6.dp))
                StepMeta(step)
            }
            if (waiting != null) {
                Text(
                    "⏸ $waiting",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.tertiary,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            val body = step.text.ifBlank { if (step.status == StepStatus.RUNNING && waiting == null) "thinking…" else "" }
            if (body.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                if (open) {
                    MarkdownText(
                        body,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Text(
                        body.replace('\n', ' '),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun StepMeta(step: Step) {
    val parts = buildList {
        step.durationMs?.let { add(formatDuration(it)) }
        step.tokensPerSecond?.let { add("~%.0f t/s".format(it)) }
    }
    when {
        step.status == StepStatus.RUNNING ->
            androidx.compose.material3.CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 1.5.dp, color = roleColor(step.role))
        parts.isNotEmpty() ->
            Text(parts.joinToString(" · "), style = MonoSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** The proposed changes, file by file, as removed/added lines; you keep or drop each before packing. */
@Composable
private fun ReviewChanges(run: RunState, onApply: (Set<String>) -> Unit, onDiscard: () -> Unit) {
    val proposed = run.edits.filter { it.status == ua.volodymyr.nanocrew.core.FileEdit.Status.DONE }
    val keep = remember(run.id, run.edits) { mutableStateMapOf<String, Boolean>().apply { proposed.forEach { put(it.path, true) } } }
    val removed = Color(0xFFE57373)
    val added = Color(0xFF66BB6A)
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SectionLabel("Review changes")
        proposed.forEach { e ->
            var all by remember(e.path) { mutableStateOf(false) }
            Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
                Column(Modifier.fillMaxWidth().padding(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        androidx.compose.material3.Checkbox(checked = keep[e.path] == true, onCheckedChange = { keep[e.path] = it })
                        Column(Modifier.weight(1f)) {
                            Text("${e.action.name.lowercase()} ${e.path}", style = MaterialTheme.typography.bodyMedium)
                            if (e.detail.isNotBlank()) Text(e.detail, style = MonoSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    val lines = e.diff.ifBlank { "(no preview)" }.split("\n")
                    val shown = if (all) lines else lines.take(40)
                    Column(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(top = 6.dp)) {
                        shown.forEach { l ->
                            Text(
                                l,
                                style = MonoSmall,
                                softWrap = false,
                                color = when {
                                    l.startsWith("- ") -> removed
                                    l.startsWith("+ ") -> added
                                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                                },
                            )
                        }
                    }
                    if (lines.size > 40) TextButton(onClick = { all = !all }) { Text(if (all) "Show less" else "Show all ${lines.size} lines") }
                }
            }
        }
        val n = keep.count { it.value }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            androidx.compose.material3.Button(onClick = { onApply(keep.filterValues { it }.keys) }, enabled = n > 0) {
                Text("Apply & pack ($n)")
            }
            androidx.compose.material3.OutlinedButton(onClick = onDiscard) { Text("Discard") }
        }
    }
}

/** Save / Share for the zip the Editor rebuilt. */
@Composable
private fun OutputActions(
    run: RunState,
    onSave: (RunState, android.net.Uri) -> Unit,
    message: String?,
    onDismissMessage: () -> Unit,
) {
    val context = LocalContext.current
    val file = remember(run.outputPath) { run.outputPath?.let { java.io.File(it) } }
    val saver = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/zip"),
    ) { uri -> if (uri != null) onSave(run, uri) }

    if (file == null || !file.exists()) {
        Text("The edited zip is no longer on this phone.", style = MonoSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        androidx.compose.material3.Button(onClick = { saver.launch(file.name) }) { Text("Save zip") }
        androidx.compose.material3.OutlinedButton(onClick = { shareFile(context, file) }) {
            Icon(Icons.Default.Share, null, Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text("Share")
        }
    }
    Text(
        "${file.name} · ${"%.1f".format(file.length() / 1_048_576.0)} MB",
        style = MonoSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 6.dp),
    )
    if (message != null) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(message, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
            TextButton(onClick = onDismissMessage) { Text("OK") }
        }
    }
}

fun shareFile(context: android.content.Context, file: java.io.File) {
    val uri = androidx.core.content.FileProvider.getUriForFile(context, context.packageName + ".provider", file)
    val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
        type = "application/zip"
        putExtra(android.content.Intent.EXTRA_STREAM, uri)
        addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    startChooser(context, android.content.Intent.createChooser(send, file.name))
}

fun shareText(context: android.content.Context, text: String) {
    val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(android.content.Intent.EXTRA_TEXT, text)
    }
    startChooser(context, android.content.Intent.createChooser(send, null))
}

/** Opens the share sheet from any context (Gallery's screens may hand us a wrapped one). */
fun startChooser(context: android.content.Context, chooser: android.content.Intent) {
    try {
        if (context.findActivity() == null) chooser.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        (context.findActivity() ?: context).startActivity(chooser)
    } catch (e: Exception) {
        ua.volodymyr.nanocrew.core.DebugLog.e("share", "couldn't open the share sheet", e)
        android.widget.Toast.makeText(context, "Couldn't share: ${e.message}", android.widget.Toast.LENGTH_LONG).show()
    }
}

private fun android.content.Context.findActivity(): android.app.Activity? {
    var c: android.content.Context? = this
    while (c is android.content.ContextWrapper) {
        if (c is android.app.Activity) return c
        c = c.baseContext
    }
    return null
}
