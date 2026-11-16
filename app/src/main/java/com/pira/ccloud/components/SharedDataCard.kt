package com.pira.ccloud.components

import android.text.format.DateUtils
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.pira.ccloud.data.repository.GitHubDeviceLogin
import com.pira.ccloud.data.repository.MetadataSnapshot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Settings card for the shared movie data: when this device last took the shared snapshot, and,
 * for the publisher, signing in to GitHub (a code approved on the phone, or a pasted token; kept on
 * this device only) so it uploads its data.
 */
@Composable
fun SharedDataCard() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var snapshotTime by remember { mutableStateOf(MetadataSnapshot.snapshotTime) }
    var hasToken by remember { mutableStateOf(MetadataSnapshot.hasToken) }
    var autoPublish by remember { mutableStateOf(MetadataSnapshot.autoPublish) }
    var lastPublish by remember { mutableStateOf(MetadataSnapshot.lastPublish) }
    var showTokenField by remember { mutableStateOf(false) }
    var token by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var signInCode by remember { mutableStateOf<GitHubDeviceLogin.Code?>(null) }
    var signInJob by remember { mutableStateOf<Job?>(null) }
    var signInError by remember { mutableStateOf<String?>(null) }

    fun ago(time: Long) = if (time <= 0) "never" else DateUtils.getRelativeTimeSpanString(time).toString()

    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.CloudSync,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp)
                )
                Text(
                    text = "Shared Movie Data",
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(start = 8.dp)
                )
            }
            Text(
                text = if (MetadataSnapshot.canPublish) {
                    "Ratings, popularity and awards already fetched are downloaded from a shared copy, " +
                        "so the ranked sorts need fewer API requests."
                } else {
                    "The ranked sorts use a shared copy of ratings, popularity and awards, downloaded " +
                        "automatically every few hours. Movies not in it yet are ranked by IMDB score."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = "Shared copy from ${ago(snapshotTime)}",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 8.dp)
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                OutlinedButton(
                    enabled = !busy,
                    onClick = {
                        busy = true
                        scope.launch {
                            val result = MetadataSnapshot.download(force = true)
                            busy = false
                            snapshotTime = MetadataSnapshot.snapshotTime
                            val text = result.fold(
                                { "Took $it newer answers" },
                                { "Couldn't download: ${it.message}" }
                            )
                            Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
                        }
                    },
                    modifier = Modifier.focusHighlight()
                ) { Text("Download now") }
                // Only the owner's build (with API keys) fetches data, so only it can share some
                if (MetadataSnapshot.canPublish && !hasToken && signInCode == null) {
                    TextButton(
                        onClick = {
                            if (!GitHubDeviceLogin.isConfigured) {
                                showTokenField = !showTokenField
                                return@TextButton
                            }
                            signInError = null
                            signInJob = scope.launch {
                                try {
                                    val code = GitHubDeviceLogin.start()
                                    signInCode = code
                                    val tokens = GitHubDeviceLogin.awaitTokens(code)
                                    MetadataSnapshot.setToken(context, tokens.accessToken, tokens.refreshToken, tokens.expiresAt)
                                    hasToken = true
                                    Toast.makeText(context, "Signed in: this device now shares its data", Toast.LENGTH_SHORT).show()
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (e: Exception) {
                                    signInError = e.message ?: "Sign-in failed"
                                } finally {
                                    signInCode = null
                                }
                            }
                        },
                        modifier = Modifier.focusHighlight()
                    ) { Text("I share the data") }
                }
            }

            // Signing in on the phone: the TV shows the code to enter at github.com/login/device
            signInCode?.let { code ->
                Text(
                    text = "On your phone, open ${code.verificationUri.removePrefix("https://")} and enter this code:",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 12.dp)
                )
                Text(
                    text = code.userCode,
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 4.dp)
                )
                Text(
                    text = "Then approve CCloud. This page continues by itself.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
                    TextButton(
                        onClick = {
                            signInJob?.cancel()
                            signInCode = null
                        },
                        modifier = Modifier.focusHighlight()
                    ) { Text("Cancel") }
                    TextButton(
                        onClick = {
                            signInJob?.cancel()
                            signInCode = null
                            showTokenField = true
                        },
                        modifier = Modifier.focusHighlight()
                    ) { Text("Paste a token instead") }
                }
            }
            signInError?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }

            // Or a GitHub token typed in (e.g. on a phone), with write access to the repository
            if (showTokenField && !hasToken) {
                Text(
                    text = "A GitHub token that can write to ${MetadataSnapshot.repo} (a fine-grained token for that " +
                        "repository with Contents: read and write). It stays on this device.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp)
                )
                OutlinedTextField(
                    value = token,
                    onValueChange = { token = it.trim() },
                    label = { Text("GitHub token") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                )
                OutlinedButton(
                    enabled = token.isNotBlank(),
                    onClick = {
                        MetadataSnapshot.setToken(context, token)
                        token = ""
                        hasToken = true
                        showTokenField = false
                    },
                    modifier = Modifier.padding(top = 8.dp).focusHighlight()
                ) { Text("Save token") }
            }
            if (hasToken && MetadataSnapshot.canPublish) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Share this device's data", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            text = "Uploads when the app opens or closes, after new data was fetched. " +
                                "Last upload: ${ago(lastPublish)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = autoPublish,
                        onCheckedChange = {
                            autoPublish = it
                            MetadataSnapshot.setAutoPublish(context, it)
                        },
                        modifier = Modifier.focusHighlight()
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                    OutlinedButton(
                        enabled = !busy,
                        onClick = {
                            busy = true
                            scope.launch {
                                val result = MetadataSnapshot.publish()
                                busy = false
                                lastPublish = MetadataSnapshot.lastPublish
                                val text = result.fold(
                                    { "Uploaded $it answers" },
                                    { "Couldn't upload: ${it.message}" }
                                )
                                Toast.makeText(context, text, Toast.LENGTH_LONG).show()
                            }
                        },
                        modifier = Modifier.focusHighlight()
                    ) { Text("Upload now") }
                    TextButton(
                        onClick = {
                            MetadataSnapshot.setToken(context, null)
                            hasToken = false
                        },
                        modifier = Modifier.focusHighlight()
                    ) { Text("Sign out") }
                }
            }
        }
    }
}
