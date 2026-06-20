package com.workout.autoeditor.ui

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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.workout.autoeditor.data.EditPolicy
import com.workout.autoeditor.pipeline.EditPipelineService

@Composable
fun ReviewScreen(
    policy: EditPolicy,
    service: EditPipelineService,
    onConfirmed: () -> Unit,
    onCancel: () -> Unit,
) {
    // Re-key all editable state to the incoming policy so a re-parse cleanly
    // resets the toggles instead of silently keeping stale values from the
    // previous attempt.
    var dropWarmups by remember(policy) { mutableStateOf(policy.dropWarmups) }
    var dropRest by remember(policy) { mutableStateOf(policy.dropRest) }
    var dropIdle by remember(policy) { mutableStateOf(policy.dropIdle) }
    var perExerciseCapSec by remember(policy) { mutableStateOf(policy.perExerciseCapMs / 1000f) }
    var targetTotalSec by remember(policy) {
        mutableStateOf((policy.targetTotalMs ?: 90_000L) / 1000f)
    }
    var minReps by remember(policy) { mutableStateOf(policy.minRepsPerSegment.toFloat()) }

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("Review the plan", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Adjust if you like, then confirm. Processing starts only after confirm.",
            style = MaterialTheme.typography.bodySmall,
        )
        Spacer(Modifier.height(8.dp))
        Text("Keep classes: ${policy.keepClasses.joinToString { it.name }}")
        Spacer(Modifier.height(4.dp))

        switchRow("Drop warmups", dropWarmups) { dropWarmups = it }
        switchRow("Drop rest periods", dropRest) { dropRest = it }
        switchRow("Drop idle / phone-checking", dropIdle) { dropIdle = it }

        Spacer(Modifier.height(8.dp))
        Text("Cap per exercise: ${perExerciseCapSec.toInt()} s")
        Slider(value = perExerciseCapSec, onValueChange = { perExerciseCapSec = it }, valueRange = 5f..120f)

        Text("Target total: ${targetTotalSec.toInt()} s")
        Slider(value = targetTotalSec, onValueChange = { targetTotalSec = it }, valueRange = 15f..300f)

        Text("Min reps per segment: ${minReps.toInt()}")
        Slider(value = minReps, onValueChange = { minReps = it }, valueRange = 0f..10f, steps = 9)

        Spacer(Modifier.height(16.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = {
                    val override = policy.copy(
                        dropWarmups = dropWarmups,
                        dropRest = dropRest,
                        dropIdle = dropIdle,
                        perExerciseCapMs = (perExerciseCapSec * 1000f).toLong(),
                        targetTotalMs = (targetTotalSec * 1000f).toLong(),
                        minRepsPerSegment = minReps.toInt(),
                    )
                    service.confirmAndProceed(override)
                    onConfirmed()
                },
                modifier = Modifier.fillMaxWidth().height(56.dp),
            ) { Text("Confirm and process") }
        }
        TextButton(onClick = {
            service.cancel()
            onCancel()
        }) { Text("Cancel") }
    }
}

@Composable
private fun switchRow(label: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f))
        Switch(checked = value, onCheckedChange = onChange)
    }
}
