package com.workout.autoeditor.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.workout.autoeditor.data.ExerciseClass
import com.workout.autoeditor.ml.SampleCollector
import com.workout.autoeditor.ml.TrainingDataStore
import kotlinx.coroutines.launch

@Composable
fun TrainingScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val store = remember { TrainingDataStore(ctx) }
    val collector = remember { SampleCollector(ctx, store) }
    val scope = rememberCoroutineScope()
    var counts by remember { mutableStateOf(store.countsByClass()) }
    var selectedLabel by remember { mutableStateOf<ExerciseClass?>(null) }
    var pickedClip by remember { mutableStateOf<Uri?>(null) }
    var status by remember { mutableStateOf("idle") }
    var progress by remember { mutableFloatStateOf(0f) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        pickedClip = uri
    }

    LaunchedEffect(status) {
        if (status == "done") {
            counts = store.countsByClass()
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("Train exercise classifier", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Pick a short clip of one exercise, label it, and the app extracts pose embeddings.",
            style = MaterialTheme.typography.bodySmall,
        )

        Spacer(Modifier.height(8.dp))
        Text("Current samples", style = MaterialTheme.typography.titleSmall)
        Text(
            counts.entries.joinToString(", ") { "${it.key.name}=${it.value}" }
                .ifEmpty { "no samples yet" },
            style = MaterialTheme.typography.bodySmall,
        )

        Spacer(Modifier.height(16.dp))
        Text("1. Pick clip", style = MaterialTheme.typography.titleSmall)
        OutlinedButton(onClick = { picker.launch(arrayOf("video/*")) }, modifier = Modifier.fillMaxWidth()) {
            Text(pickedClip?.lastPathSegment ?: "Choose video")
        }

        Spacer(Modifier.height(8.dp))
        Text("2. Label", style = MaterialTheme.typography.titleSmall)
        val choices = listOf(
            ExerciseClass.SQUAT, ExerciseClass.PUSHUP,
            ExerciseClass.BICEP_CURL, ExerciseClass.OVERHEAD_PRESS,
            ExerciseClass.WARMUP, ExerciseClass.REST, ExerciseClass.IDLE,
        )
        Column {
            choices.chunked(2).forEach { row ->
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { c ->
                        if (selectedLabel == c) {
                            Button(onClick = { selectedLabel = c }, modifier = Modifier.weight(1f)) {
                                Text(c.name)
                            }
                        } else {
                            OutlinedButton(onClick = { selectedLabel = c }, modifier = Modifier.weight(1f)) {
                                Text(c.name)
                            }
                        }
                    }
                    if (row.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }

        Spacer(Modifier.height(16.dp))
        Button(
            onClick = {
                val uri = pickedClip
                val label = selectedLabel
                if (uri == null || label == null) return@Button
                status = "running"
                progress = 0f
                scope.launch {
                    val n = try {
                        collector.ingestClip(uri, label) { progress = it }
                    } catch (_: Throwable) {
                        -1
                    }
                    status = if (n > 0) "done" else "error"
                }
            },
            enabled = pickedClip != null && selectedLabel != null && status != "running",
            modifier = Modifier.fillMaxWidth().height(56.dp),
        ) { Text("Ingest") }

        if (status == "running") {
            LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
            Text("Extracting pose embeddings...", style = MaterialTheme.typography.bodySmall)
        }
        if (status == "done") {
            Text("Saved.", color = MaterialTheme.colorScheme.primary)
        } else if (status == "error") {
            Text("Failed.", color = MaterialTheme.colorScheme.error)
        }

        Spacer(Modifier.height(24.dp))
        TextButton(onClick = onBack) { Text("Back") }
    }
}
