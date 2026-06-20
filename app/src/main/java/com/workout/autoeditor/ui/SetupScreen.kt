package com.workout.autoeditor.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.workout.autoeditor.data.AppPrefs
import com.workout.autoeditor.pipeline.ModelDownloader
import kotlinx.coroutines.flow.catch

private enum class Status { CHECKING, IDLE, DOWNLOADING, ERROR, READY }

@Composable
fun SetupScreen(onReady: () -> Unit) {
    val ctx = LocalContext.current
    val downloader = remember { ModelDownloader(ctx) }
    val prefs = remember { AppPrefs(ctx) }

    var status by remember { mutableStateOf(Status.CHECKING) }
    var downloaded by remember { mutableLongStateOf(0L) }
    var total by remember { mutableLongStateOf(-1L) }
    var error by remember { mutableStateOf<String?>(null) }
    var attempt by remember { mutableIntStateOf(0) }
    var configOpen by remember { mutableStateOf(false) }
    var urlField by remember { mutableStateOf(prefs.customModelUrl ?: "") }
    var tokenField by remember { mutableStateOf(prefs.hfToken ?: "") }

    // Decide initial state once: model present, user previously skipped, or fresh install needs download.
    LaunchedEffect(Unit) {
        when {
            downloader.isModelPresent() -> { status = Status.READY; onReady() }
            prefs.skippedModel -> { status = Status.READY; onReady() }
            else -> { status = Status.IDLE }
        }
    }

    // Download trigger: re-runs whenever attempt counter increments.
    LaunchedEffect(attempt) {
        if (attempt == 0) return@LaunchedEffect
        status = Status.DOWNLOADING
        error = null
        downloaded = 0L
        total = -1L
        downloader.download()
            .catch {
                error = it.message ?: it::class.simpleName ?: "unknown"
                status = Status.ERROR
            }
            .collect { ev ->
                when (ev) {
                    is ModelDownloader.Event.Progress -> {
                        downloaded = ev.downloadedBytes
                        total = ev.totalBytes
                    }
                    is ModelDownloader.Event.Done -> {
                        status = Status.READY
                        onReady()
                    }
                    is ModelDownloader.Event.Failed -> {
                        error = ev.reason
                        status = Status.ERROR
                    }
                }
            }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Top,
    ) {
        Spacer(Modifier.height(48.dp))
        Text("Workout Auto Editor", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(8.dp))
        Text(
            "On-device editor. Pose detection works offline; Gemma 4 (E2B, ~1.9 GB) is downloaded once if you want LLM-driven instruction parsing.",
            style = MaterialTheme.typography.bodySmall,
        )
        Spacer(Modifier.height(24.dp))

        when (status) {
            Status.CHECKING -> Text("Checking for model...")
            Status.IDLE -> idleControls(
                onDownload = { attempt += 1 },
                onSkip = {
                    prefs.skippedModel = true
                    status = Status.READY
                    onReady()
                },
                onConfigure = { configOpen = true },
            )
            Status.DOWNLOADING -> downloadingView(downloaded, total)
            Status.ERROR -> errorView(
                error = error,
                onRetry = { attempt += 1 },
                onConfigure = { configOpen = true },
                onSkip = {
                    prefs.skippedModel = true
                    status = Status.READY
                    onReady()
                },
            )
            Status.READY -> {
                Text("Ready")
                Button(onClick = onReady, modifier = Modifier.padding(top = 16.dp)) {
                    Text("Continue")
                }
            }
        }

        if (configOpen) {
            Spacer(Modifier.height(24.dp))
            Text("Configure model source", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = urlField,
                onValueChange = { urlField = it },
                label = { Text("Custom model URL (leave blank for default)") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = tokenField,
                onValueChange = { tokenField = it },
                label = { Text("Hugging Face token (optional)") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "Default URL is the public Gemma 4 E2B LiteRT release - no token required. " +
                    "If you want to use a different/gated model (e.g. Gemma 4 E4B), paste a URL and HF token here.",
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = {
                    prefs.customModelUrl = urlField.trim().ifBlank { null }
                    prefs.hfToken = tokenField.trim().ifBlank { null }
                    configOpen = false
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Save") }
        }
    }
}

@Composable
private fun idleControls(
    onDownload: () -> Unit,
    onSkip: () -> Unit,
    onConfigure: () -> Unit,
) {
    Button(
        onClick = onDownload,
        modifier = Modifier.fillMaxWidth().height(52.dp),
    ) { Text("Download Gemma 4 E2B (~1.9 GB)") }
    Spacer(Modifier.height(8.dp))
    OutlinedButton(
        onClick = onConfigure,
        modifier = Modifier.fillMaxWidth(),
    ) { Text("Configure URL / token") }
    Spacer(Modifier.height(8.dp))
    OutlinedButton(
        onClick = onSkip,
        modifier = Modifier.fillMaxWidth(),
    ) { Text("Skip - use rule-based mode") }
}

@Composable
private fun downloadingView(downloaded: Long, total: Long) {
    val pct = if (total > 0) (downloaded.toFloat() / total).coerceIn(0f, 1f) else 0f
    Text("Downloading model...", style = MaterialTheme.typography.titleSmall)
    Spacer(Modifier.height(8.dp))
    LinearProgressIndicator(
        progress = { pct },
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(8.dp))
    val mbDown = downloaded / 1_000_000
    val mbTotal = total / 1_000_000
    Text(
        if (total > 0) "$mbDown MB / $mbTotal MB" else "$mbDown MB downloaded",
        style = MaterialTheme.typography.bodySmall,
    )
}

@Composable
private fun errorView(
    error: String?,
    onRetry: () -> Unit,
    onConfigure: () -> Unit,
    onSkip: () -> Unit,
) {
    Text(
        error ?: "Download failed.",
        color = MaterialTheme.colorScheme.error,
        style = MaterialTheme.typography.bodyMedium,
    )
    Spacer(Modifier.height(16.dp))
    Button(
        onClick = onRetry,
        modifier = Modifier.fillMaxWidth().height(52.dp),
    ) { Text("Retry download") }
    Spacer(Modifier.height(8.dp))
    OutlinedButton(
        onClick = onConfigure,
        modifier = Modifier.fillMaxWidth(),
    ) { Text("Configure URL / token") }
    Spacer(Modifier.height(8.dp))
    TextButton(onClick = onSkip, modifier = Modifier.fillMaxWidth()) {
        Text("Skip - use rule-based mode")
    }
}
