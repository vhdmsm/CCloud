package com.pira.ccloud.components

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.pira.ccloud.BuildConfig
import com.pira.ccloud.data.repository.AppUpdater
import com.pira.ccloud.utils.AppRelease
import com.pira.ccloud.utils.tr
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.File

private sealed class UpdateStep {
    object Offer : UpdateStep()
    /** Android 8+ needs the user to allow CCloud to install apps; asked before the download. */
    object NeedPermission : UpdateStep()
    data class Downloading(val progress: Float) : UpdateStep()
    data class Failed(val file: File?) : UpdateStep()
}

/**
 * When the app opens, looks for a newer release on GitHub and, if there is one, offers it; on
 * Update it downloads the APK and opens the system installer. Nothing is shown when the check
 * fails (e.g. offline) or the app is up to date.
 */
@Composable
fun UpdatePrompt() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var release by remember { mutableStateOf<AppRelease?>(null) }
    var step by remember { mutableStateOf<UpdateStep>(UpdateStep.Offer) }
    var downloadJob by remember { mutableStateOf<Job?>(null) }

    LaunchedEffect(Unit) {
        release = AppUpdater.check(context)
    }

    val current = release ?: return
    fun close() {
        downloadJob?.cancel()
        release = null
    }

    fun install(file: File) {
        if (AppUpdater.install(context, file)) close() else step = UpdateStep.Failed(file)
    }

    fun download() {
        step = UpdateStep.Downloading(0f)
        downloadJob = scope.launch {
            when (val result = AppUpdater.download(context, current) { step = UpdateStep.Downloading(it) }) {
                is AppUpdater.Download.Ready -> install(result.file)
                AppUpdater.Download.NotNewer -> close()
                AppUpdater.Download.Failed -> step = UpdateStep.Failed(null)
            }
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        // Back from the settings page: go on if it was allowed, otherwise ask again
        if (AppUpdater.canInstall(context)) download()
    }

    fun start() {
        if (AppUpdater.canInstall(context)) {
            download()
            return
        }
        val settings = AppUpdater.installPermissionIntent(context)
        // Without the settings page (some TVs), the installer asks for itself
        if (settings == null) download() else step = UpdateStep.NeedPermission
    }

    val firstButton = remember { FocusRequester() }
    // On a TV the remote starts on the first button of each step
    key(step::class) { FocusWhenReady(firstButton, ready = true) }

    AlertDialog(
        // Only the buttons close it, so a stray tap or Back doesn't stop a download
        onDismissRequest = { if (step !is UpdateStep.Downloading) close() },
        title = {
            Text(
                text = when (step) {
                    is UpdateStep.Downloading -> tr("Downloading update…")
                    is UpdateStep.Failed -> tr("Update failed")
                    UpdateStep.NeedPermission -> tr("Allow installing the update")
                    UpdateStep.Offer -> tr("Update available")
                },
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(4.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                when (val s = step) {
                    UpdateStep.Offer -> {
                        Text(tr("CCloud %s is available. You have %s.", current.version, BuildConfig.VERSION_NAME))
                        val notes = plainNotes(current.notes)
                        if (notes.isNotEmpty()) {
                            Text(
                                text = notes,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier
                                    .heightIn(max = 200.dp)
                                    .verticalScroll(rememberScrollState())
                            )
                        }
                        OptionButton(
                            text = tr("Update"),
                            icon = Icons.Default.SystemUpdate,
                            style = OptionStyle.MAIN,
                            onClick = { start() },
                            modifier = Modifier.focusRequester(firstButton)
                        )
                        OptionButton(text = tr("Later"), style = OptionStyle.QUIET, onClick = { close() })
                    }
                    UpdateStep.NeedPermission -> {
                        Text(tr("To install the update, allow CCloud to install apps, then come back."))
                        OptionButton(
                            text = tr("Open settings"),
                            style = OptionStyle.MAIN,
                            onClick = {
                                AppUpdater.installPermissionIntent(context)?.let { permissionLauncher.launch(it) } ?: download()
                            },
                            modifier = Modifier.focusRequester(firstButton)
                        )
                        OptionButton(text = tr("Later"), style = OptionStyle.QUIET, onClick = { close() })
                    }
                    is UpdateStep.Downloading -> {
                        if (s.progress < 0f) {
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        } else {
                            LinearProgressIndicator(progress = { s.progress }, modifier = Modifier.fillMaxWidth())
                            Text(tr("%d%%", (s.progress * 100).toInt()), style = MaterialTheme.typography.bodySmall)
                        }
                        OptionButton(
                            text = tr("Cancel"),
                            style = OptionStyle.QUIET,
                            onClick = { close() },
                            modifier = Modifier.focusRequester(firstButton)
                        )
                    }
                    is UpdateStep.Failed -> {
                        Text(
                            if (s.file == null) tr("The update could not be downloaded.")
                            else tr("The installer could not be opened.")
                        )
                        OptionButton(
                            text = tr("Retry"),
                            style = OptionStyle.MAIN,
                            onClick = { if (s.file != null) install(s.file) else start() },
                            modifier = Modifier.focusRequester(firstButton)
                        )
                        OptionButton(text = tr("Close"), style = OptionStyle.QUIET, onClick = { close() })
                    }
                }
            }
        },
        confirmButton = {},
        containerColor = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(20.dp),
        tonalElevation = 6.dp
    )
}

/** Release notes without Markdown marks (headings, bold, list dashes), for a plain text view. */
private fun plainNotes(markdown: String): String =
    markdown.lines()
        .map { it.trim().removePrefix("#").trimStart('#').trim().replace("**", "").replace("`", "") }
        .map { if (it.startsWith("- ") || it.startsWith("* ")) "• " + it.drop(2) else it }
        .joinToString("\n")
        .replace(Regex("\n{3,}"), "\n\n")
        .trim()
