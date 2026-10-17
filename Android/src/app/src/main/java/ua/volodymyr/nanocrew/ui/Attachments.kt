package ua.volodymyr.nanocrew.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ua.volodymyr.nanocrew.core.Attachment

/** Opens the system file picker (any file type, several at once). */
@Composable
fun rememberFilePicker(onPicked: (List<Uri>) -> Unit): () -> Unit {
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        onPicked(uris)
    }
    return { launcher.launch(arrayOf("*/*")) }
}

@Composable
fun AttachButton(onClick: () -> Unit, enabled: Boolean) {
    IconButton(onClick = onClick, enabled = enabled) {
        Icon(Icons.Default.Add, contentDescription = "Attach files")
    }
}

/** Files waiting to be sent with the next task or message. */
@Composable
fun FileTray(
    files: List<Attachment>,
    importing: Boolean,
    error: String?,
    onRemove: (Int) -> Unit,
    onDismissError: () -> Unit,
) {
    if (files.isEmpty() && !importing && error == null) return
    Column(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 8.dp)) {
        if (files.isNotEmpty() || importing) {
            Row(
                Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                files.forEachIndexed { i, f ->
                    InputChip(
                        selected = false,
                        onClick = { onRemove(i) },
                        label = {
                            Column {
                                Text(f.name, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.width(140.dp))
                                Text(
                                    listOf(f.sizeLabel, f.note).filter { it.isNotBlank() }.joinToString(" · "),
                                    style = MonoSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        },
                        leadingIcon = thumbnail(f),
                        trailingIcon = { Icon(Icons.Default.Close, "Remove", Modifier.size(16.dp)) },
                    )
                }
                if (importing) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Text("Opening…", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
        if (error != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                TextButton(onClick = onDismissError) { Text("OK") }
            }
        }
    }
}

private fun thumbnail(f: Attachment): (@Composable () -> Unit)? {
    val bmp = f.image as? android.graphics.Bitmap ?: return null
    val thumb: @Composable () -> Unit = {
        androidx.compose.foundation.Image(
            bmp.asImageBitmap(), null,
            Modifier.size(28.dp).clip(RoundedCornerShape(6.dp)),
            contentScale = ContentScale.Crop,
        )
    }
    return thumb
}

/** "📎 a.pdf, photo.jpg" line shown on runs and chat messages. */
@Composable
fun AttachedNames(names: List<String>, modifier: Modifier = Modifier) {
    if (names.isEmpty()) return
    Text(
        "📎 " + names.joinToString(", "),
        modifier = modifier,
        style = MonoSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
    )
}

