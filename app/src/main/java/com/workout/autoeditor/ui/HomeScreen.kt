package com.workout.autoeditor.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun HomeScreen(
    onRecord: () -> Unit,
    onPickClip: () -> Unit,
    onTrain: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("Workout Auto Editor", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(8.dp))
        Text(
            "Film, describe how you want it cut, get a render.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.height(32.dp))
        Button(
            onClick = onRecord,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Record a workout") }
        Spacer(Modifier.height(12.dp))
        OutlinedButton(
            onClick = onPickClip,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Edit an existing clip") }
        Spacer(Modifier.height(12.dp))
        OutlinedButton(
            onClick = onTrain,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Train exercise classifier") }
    }
}
