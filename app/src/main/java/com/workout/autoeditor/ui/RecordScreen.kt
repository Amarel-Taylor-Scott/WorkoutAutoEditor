package com.workout.autoeditor.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.workout.autoeditor.camera.CameraController
import java.io.File
import java.util.concurrent.Executors

@Composable
fun RecordScreen(onRecorded: (File) -> Unit, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val controller = remember { CameraController(ctx) }
    val state by controller.state.collectAsState()
    val executor = remember { Executors.newSingleThreadExecutor() }
    var hasPerm by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED &&
                ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED,
        )
    }
    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        hasPerm = result.values.all { it }
    }

    LaunchedEffect(Unit) {
        if (!hasPerm) {
            permLauncher.launch(arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO))
        }
    }

    if (!hasPerm) {
        Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("Need camera + microphone permission to record.")
            Button(onClick = {
                permLauncher.launch(arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO))
            }) { Text("Grant") }
            Button(onClick = onBack) { Text("Back") }
        }
        return
    }

    val previewView = remember { PreviewView(ctx) }
    LaunchedEffect(Unit) {
        try { controller.bind(previewView, owner) } catch (_: Throwable) {}
    }

    LaunchedEffect(state) {
        val s = state
        if (s is CameraController.RecordingState.Finalized) {
            onRecorded(s.file)
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        AndroidView(
            factory = { previewView },
            modifier = Modifier.fillMaxSize(),
        )
        Column(
            modifier = Modifier.fillMaxSize().padding(16.dp),
            verticalArrangement = androidx.compose.foundation.layout.Arrangement.Bottom,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            val s = state
            val ms = (s as? CameraController.RecordingState.Recording)?.durationMs ?: 0L
            Text(
                if (s is CameraController.RecordingState.Recording) "REC ${ms / 1000}s" else "Tap to record",
                color = if (s is CameraController.RecordingState.Recording) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onBackground,
            )
            Row(
                modifier = Modifier.padding(top = 16.dp),
                horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(12.dp),
            ) {
                Button(onClick = onBack) { Text("Back") }
                if (s is CameraController.RecordingState.Recording) {
                    Button(onClick = { controller.stop() }) { Text("Stop") }
                } else {
                    Button(onClick = { controller.startRecording(executor) }) { Text("Record") }
                }
            }
            if (s is CameraController.RecordingState.Failed) {
                Text(s.reason, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp))
            }
        }
    }
}
