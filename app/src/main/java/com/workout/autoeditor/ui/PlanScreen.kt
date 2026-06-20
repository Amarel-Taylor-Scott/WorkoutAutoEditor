package com.workout.autoeditor.ui

import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.workout.autoeditor.pipeline.EditPipelineService
import com.workout.autoeditor.pipeline.ModelDownloader
import java.io.File

@Composable
fun PlanScreen(
    sourceUri: Uri,
    service: EditPipelineService,
    onStarted: () -> Unit,
    onBack: () -> Unit,
) {
    val ctx = LocalContext.current
    var instruction by remember {
        mutableStateOf("Keep heavy working sets, drop warmups and rest, max 90 seconds total.")
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Tell the editor how to cut", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Plain English. The model will turn this into editing rules. You confirm before any video processing happens.",
            style = MaterialTheme.typography.bodySmall,
        )
        OutlinedTextField(
            value = instruction,
            onValueChange = { instruction = it },
            modifier = Modifier.fillMaxWidth().height(160.dp),
            label = { Text("Editing instruction") },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        )
        Spacer(Modifier.height(16.dp))
        Button(
            onClick = {
                val downloader = ModelDownloader(ctx)
                val outDir = File(ctx.getExternalFilesDir(null), "renders").apply { mkdirs() }
                val out = File(outDir, "edited-${System.currentTimeMillis()}.mp4")
                val modelPath = if (downloader.isModelPresent()) downloader.modelFile().absolutePath else null
                service.startParsing(sourceUri, instruction, modelPath, out)
                onStarted()
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Start") }
        TextButton(onClick = onBack) { Text("Back") }
    }
}
