package ua.volodymyr.nanocrew.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ua.volodymyr.nanocrew.core.Phase
import ua.volodymyr.nanocrew.core.RunState
import java.text.DateFormat
import java.util.Date

@Composable
fun HistoryScreen(
    runs: List<RunState>,
    onOpen: (RunState) -> Unit,
    onDelete: (String) -> Unit,
    onClearAll: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var confirmClear by remember { mutableStateOf(false) }
    val fmt = remember { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT) }

    if (runs.isEmpty()) {
        EmptyHint("No runs yet", "Crew runs are saved here, on this phone only.", modifier.padding(top = 80.dp))
        return
    }

    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 8.dp)) {
        item {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                SectionLabel("${runs.size} saved run${if (runs.size == 1) "" else "s"}", Modifier.weight(1f))
                TextButton(onClick = { confirmClear = true }) { Text("Clear all") }
            }
        }
        items(runs, key = { it.id }) { run ->
            Row(
                Modifier.fillMaxWidth().clickable { onOpen(run) }.padding(start = 16.dp, top = 10.dp, bottom = 10.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(run.earlier.firstOrNull()?.task ?: run.task, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(3.dp))
                    val status = when (run.phase) {
                        Phase.DONE -> "done · ${formatDuration((run.endedAt ?: run.startedAt) - run.startedAt)} · ${run.modelCalls} calls"
                        Phase.FAILED -> "failed"
                        Phase.CANCELLED -> "stopped"
                        Phase.RUNNING -> "interrupted"
                    }
                    val turns = if (run.earlier.isEmpty()) "" else " · ${run.earlier.size + 1} messages"
                    Text("${fmt.format(Date(run.startedAt))} · $status$turns", style = MonoSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = { onDelete(run.id) }) {
                    Icon(Icons.Default.Delete, "Delete", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            HorizontalDivider(Modifier.padding(start = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Delete all runs?") },
            text = { Text("This removes every saved crew run from this phone.") },
            confirmButton = { TextButton(onClick = { confirmClear = false; onClearAll() }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } },
        )
    }
}
