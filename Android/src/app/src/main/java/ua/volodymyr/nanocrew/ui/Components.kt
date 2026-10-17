package ua.volodymyr.nanocrew.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import ua.volodymyr.nanocrew.engine.ModelStatus

/** Shows where Gemini Nano is (checking, downloading, ready…) and offers the next action. */
@Composable
fun ModelGate(status: ModelStatus, onCheck: () -> Unit, onDownload: () -> Unit, modifier: Modifier = Modifier, log: List<String> = emptyList()) {
    when (status) {
        is ModelStatus.Ready -> Row(
            modifier.padding(horizontal = 16.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(Modifier.size(7.dp).clip(CircleShape), color = MaterialTheme.colorScheme.primary) {}
            Spacer(Modifier.size(8.dp))
            Text(
                "${status.modelName} · on-device",
                style = MonoSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        else -> Surface(
            modifier.fillMaxWidth().padding(16.dp),
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                when (status) {
                    ModelStatus.Checking -> Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.size(12.dp))
                        Text("Looking for Gemini Nano on this phone…")
                    }
                    ModelStatus.Downloadable -> {
                        Text("Gemini Nano needs a one-time download", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "Android's AICore service downloads it once and shares it between apps. " +
                                "After that, Nano Crew works fully offline.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Button(onClick = onDownload) { Text("Download model") }
                    }
                    is ModelStatus.Downloading -> {
                        Text("Downloading Gemini Nano…", style = MaterialTheme.typography.titleSmall)
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                        Text(
                            if (status.bytes > 0) "%.0f MB so far".format(status.bytes / 1_048_576.0)
                            else "Requested from Android AICore. It can take a few minutes to start (it may wait for Wi-Fi) — keep this screen open.",
                            style = MonoSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        OutlinedButton(onClick = onCheck) { Text("Check again") }
                    }
                    is ModelStatus.Unavailable -> {
                        Text("Gemini Nano not ready", style = MaterialTheme.typography.titleSmall)
                        Text(status.reason, style = MaterialTheme.typography.bodySmall)
                        OutlinedButton(onClick = onCheck) { Text("Check again") }
                    }
                    is ModelStatus.Failed -> {
                        Text("Something went wrong", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.error)
                        Text(status.message, style = MaterialTheme.typography.bodySmall)
                        OutlinedButton(onClick = onCheck) { Text("Try again") }
                    }
                    is ModelStatus.Preparing -> {
                        Text(status.title, style = MaterialTheme.typography.titleSmall)
                        Text(status.detail, style = MaterialTheme.typography.bodySmall)
                        status.progress?.let { LinearProgressIndicator(progress = { it }, modifier = Modifier.fillMaxWidth()) }
                        if (status.canImport) Text("Settings → Model → Import Gemma, or switch back to Gemini Nano.", style = MonoSmall)
                    }
                    is ModelStatus.Ready -> Unit
                }
                if (log.isNotEmpty() && status !is ModelStatus.Checking) {
                    Text(
                        log.takeLast(6).joinToString("\n"),
                        style = MonoSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/**
 * Minimal Markdown for what Gemini Nano actually emits: **bold**, *italic*, `code`,
 * # headings and "-" or "*" bullets. Selectable so answers can be copied in parts.
 */
@Composable
fun MarkdownText(text: String, modifier: Modifier = Modifier, style: TextStyle = MaterialTheme.typography.bodyLarge, color: Color = Color.Unspecified) {
    val codeBg = MaterialTheme.colorScheme.surfaceContainerHighest
    val annotated = remember(text, codeBg) { renderMarkdown(text, codeBg) }
    SelectionContainer(modifier) {
        Text(annotated, style = style, color = color)
    }
}

private val inline = Regex("""\*\*(.+?)\*\*|__(.+?)__|`([^`]+)`|(?<![*\w])\*(?!\s)(.+?)(?<!\s)\*(?!\w)""")

fun renderMarkdown(src: String, codeBg: Color): AnnotatedString = buildAnnotatedString {
    val lines = src.trimEnd().lines()
    lines.forEachIndexed { idx, raw ->
        var line = raw
        var headingStyle: SpanStyle? = null
        val h = Regex("""^\s{0,3}(#{1,6})\s+(.*)$""").matchEntire(line)
        if (h != null) {
            line = h.groupValues[2]
            headingStyle = SpanStyle(fontWeight = FontWeight.Bold)
        } else {
            Regex("""^(\s*)[-*•]\s+(.*)$""").matchEntire(line)?.let { m ->
                val indent = if (m.groupValues[1].length >= 2) "    " else ""
                line = "$indent•  ${m.groupValues[2]}"
            }
        }
        val start = length
        var last = 0
        inline.findAll(line).forEach { m ->
            append(line.substring(last, m.range.first))
            val (bold, bold2, code, italic) = m.destructured
            when {
                bold.isNotEmpty() -> withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(bold) }
                bold2.isNotEmpty() -> withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(bold2) }
                code.isNotEmpty() -> withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = codeBg)) { append(code) }
                else -> withStyle(SpanStyle(fontStyle = androidx.compose.ui.text.font.FontStyle.Italic)) { append(italic) }
            }
            last = m.range.last + 1
        }
        append(line.substring(last))
        if (headingStyle != null) addStyle(headingStyle, start, length)
        if (idx != lines.lastIndex) append('\n')
    }
}

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(),
        modifier = modifier.padding(top = 4.dp, bottom = 2.dp),
        style = MonoSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
fun EmptyHint(title: String, body: String, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(6.dp))
        Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

fun formatDuration(ms: Long): String = when {
    ms < 1_000 -> "${ms}ms"
    ms < 60_000 -> "%.1fs".format(ms / 1000.0)
    else -> "${ms / 60_000}m ${(ms % 60_000) / 1000}s"
}


/** Shown while a model call waits to be retried (busy, quota, app off screen, a hung call). */
@androidx.compose.runtime.Composable
fun WaitingBanner(text: String?) {
    if (text == null) return
    androidx.compose.material3.Surface(
        color = androidx.compose.material3.MaterialTheme.colorScheme.tertiaryContainer,
        shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        androidx.compose.foundation.layout.Row(
            Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        ) {
            androidx.compose.material3.CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 1.5.dp)
            androidx.compose.foundation.layout.Spacer(Modifier.width(10.dp))
            androidx.compose.material3.Text(
                text,
                style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                color = androidx.compose.material3.MaterialTheme.colorScheme.onTertiaryContainer,
            )
        }
    }
}
