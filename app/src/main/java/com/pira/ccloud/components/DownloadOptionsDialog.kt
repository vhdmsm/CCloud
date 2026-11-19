package com.pira.ccloud.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.pira.ccloud.data.model.Source
import com.pira.ccloud.utils.tr

/**
 * Other ways to watch or get a quality: in another player first (watching comes before
 * downloading), then the download choices. The remote starts on the first player.
 */
@Composable
fun DownloadOptionsDialog(
    source: Source,
    onDismiss: () -> Unit,
    onCopyLink: () -> Unit,
    onDownloadWithBrowser: () -> Unit,
    onDownloadWithADM: () -> Unit,
    onOpenInVLC: () -> Unit,
    onOpenInMXPlayer: () -> Unit,
    onOpenInKMPlayer: () -> Unit
) {
    val firstOption = remember { FocusRequester() }
    FocusWhenReady(firstOption, ready = true)

    fun run(action: () -> Unit): () -> Unit = {
        action()
        onDismiss()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = tr("Download & Play Options"),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 4.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = source.quality,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                SectionTitle(tr("Play Options"))
                OptionButton(
                    text = tr("Open in VLC Player"),
                    icon = Icons.Default.PlayArrow,
                    style = OptionStyle.MAIN,
                    onClick = run(onOpenInVLC),
                    modifier = Modifier.focusRequester(firstOption)
                )
                OptionButton(
                    text = tr("Open in MX Player"),
                    icon = Icons.Default.PlayArrow,
                    style = OptionStyle.MAIN,
                    onClick = run(onOpenInMXPlayer)
                )
                OptionButton(
                    text = tr("Open in KM Player"),
                    icon = Icons.Default.PlayArrow,
                    style = OptionStyle.MAIN,
                    onClick = run(onOpenInKMPlayer)
                )

                SectionTitle(tr("Download Options"))
                OptionButton(
                    text = tr("Download with Browser"),
                    icon = Icons.Default.Download,
                    onClick = run(onDownloadWithBrowser)
                )
                OptionButton(
                    text = tr("Download with ADM"),
                    icon = Icons.Default.Download,
                    onClick = run(onDownloadWithADM)
                )
                OptionButton(
                    text = tr("Copy Link"),
                    icon = Icons.Default.ContentCopy,
                    onClick = run(onCopyLink)
                )

                OptionButton(
                    text = tr("Cancel"),
                    style = OptionStyle.QUIET,
                    onClick = onDismiss
                )
            }
        },
        confirmButton = {},
        containerColor = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(20.dp),
        tonalElevation = 6.dp
    )
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 4.dp)
    )
}
