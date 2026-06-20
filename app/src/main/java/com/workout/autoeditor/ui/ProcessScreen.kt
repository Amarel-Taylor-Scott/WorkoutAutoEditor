package com.workout.autoeditor.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.workout.autoeditor.pipeline.EditPipeline
import com.workout.autoeditor.pipeline.EditPipelineService
import java.io.File

@Composable
fun ProcessScreen(
    service: EditPipelineService,
    onDone: (File) -> Unit,
    onCancel: () -> Unit,
) {
    val phase by service.phase.collectAsState()
    val stage by service.stage.collectAsState()
    val output by service.outputFile.collectAsState()
    val error by service.error.collectAsState()

    val label = when (val s = stage) {
        EditPipeline.Stage.Idle -> "Starting..."
        EditPipeline.Stage.ParsingInstruction -> "Parsing instruction"
        EditPipeline.Stage.Pose -> "Analyzing pose (5 fps over the clip)"
        EditPipeline.Stage.Audio -> "Analyzing audio"
        EditPipeline.Stage.Timeline -> "Building timeline"
        EditPipeline.Stage.Keyframes -> "Reviewing key moments with Gemma"
        EditPipeline.Stage.Cutting -> "Computing cut list"
        EditPipeline.Stage.Rendering -> "Rendering output video"
        is EditPipeline.Stage.Done -> "Done"
        is EditPipeline.Stage.Failed -> "Failed: ${s.reason}"
    }

    val progress = when (stage) {
        EditPipeline.Stage.Pose -> 0.20f
        EditPipeline.Stage.Audio -> 0.40f
        EditPipeline.Stage.Timeline -> 0.50f
        EditPipeline.Stage.Keyframes -> 0.65f
        EditPipeline.Stage.Cutting -> 0.85f
        EditPipeline.Stage.Rendering -> 0.95f
        is EditPipeline.Stage.Done -> 1f
        else -> 0.05f
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(label, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(16.dp))
        LinearProgressIndicator(
            progress = { progress },
            modifier = Modifier.fillMaxSize(0.9f),
        )
        Spacer(Modifier.height(24.dp))
        Text("Phase: $phase", style = MaterialTheme.typography.bodySmall)

        if (phase == EditPipelineService.Phase.DONE) {
            val f = output
            Spacer(Modifier.height(16.dp))
            if (f != null) {
                Text("Saved: ${f.absolutePath}", style = MaterialTheme.typography.bodySmall)
                Button(onClick = {
                    service.acknowledgeTerminal()
                    onDone(f)
                }, modifier = Modifier.padding(top = 16.dp)) { Text("Open output") }
            }
        } else if (phase == EditPipelineService.Phase.FAILED) {
            Spacer(Modifier.height(16.dp))
            Text("${error ?: "unknown error"}", color = MaterialTheme.colorScheme.error)
            Button(onClick = {
                service.acknowledgeTerminal()
                onCancel()
            }, modifier = Modifier.padding(top = 16.dp)) { Text("Back") }
        } else {
            TextButton(onClick = {
                service.cancel()
                onCancel()
            }, modifier = Modifier.padding(top = 16.dp)) { Text("Cancel") }
        }
    }
}
