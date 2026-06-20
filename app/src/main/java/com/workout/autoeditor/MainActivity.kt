package com.workout.autoeditor

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import androidx.core.content.FileProvider
import com.workout.autoeditor.pipeline.EditPipelineService
import com.workout.autoeditor.ui.AppTheme
import com.workout.autoeditor.ui.HomeScreen
import com.workout.autoeditor.ui.PlanScreen
import com.workout.autoeditor.ui.ProcessScreen
import com.workout.autoeditor.ui.RecordScreen
import com.workout.autoeditor.ui.ReviewScreen
import com.workout.autoeditor.ui.SetupScreen
import com.workout.autoeditor.ui.TrainingScreen
import com.workout.autoeditor.ui.rememberPipelineService
import java.io.File

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            AppTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    AppNav()
                }
            }
        }
    }
}

private enum class Route { Setup, Home, Record, Plan, Review, Process, Training }

@Composable
private fun AppNav() {
    var route by remember { mutableStateOf(Route.Setup) }
    var sourceUri by remember { mutableStateOf<Uri?>(null) }

    val ctx = LocalContext.current
    val notifLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { _ -> }
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            try { ctx.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Throwable) {}
            sourceUri = uri
            route = Route.Plan
        }
    }

    when (route) {
        Route.Setup -> SetupScreen(onReady = { route = Route.Home })
        Route.Home -> HomeScreen(
            onRecord = { route = Route.Record },
            onPickClip = { picker.launch(arrayOf("video/*")) },
            onTrain = { route = Route.Training },
        )
        Route.Record -> RecordScreen(
            onRecorded = { f ->
                sourceUri = Uri.fromFile(f)
                route = Route.Plan
            },
            onBack = { route = Route.Home },
        )
        Route.Plan -> {
            val src = sourceUri
            if (src == null) {
                route = Route.Home
            } else {
                ServiceBoundPlanScreen(src, goReview = { route = Route.Review }, goBack = { route = Route.Home })
            }
        }
        Route.Review -> ServiceBoundReviewScreen(goProcess = { route = Route.Process }, goBack = { route = Route.Home })
        Route.Process -> ServiceBoundProcessScreen(goHome = { route = Route.Home }, goShare = { f -> shareOrPlay(ctx, f); route = Route.Home })
        Route.Training -> TrainingScreen(onBack = { route = Route.Home })
    }
}

@Composable
private fun ServiceBoundPlanScreen(src: Uri, goReview: () -> Unit, goBack: () -> Unit) {
    val svcState by rememberPipelineService()
    val svc = svcState
    if (svc == null) {
        WaitingForService()
    } else {
        val phase by svc.phase.collectAsState()
        LaunchedEffect(phase) {
            if (phase == EditPipelineService.Phase.AWAITING_CONFIRMATION) goReview()
        }
        PlanScreen(sourceUri = src, service = svc, onStarted = { goReview() }, onBack = goBack)
    }
}

@Composable
private fun ServiceBoundReviewScreen(goProcess: () -> Unit, goBack: () -> Unit) {
    val svcState by rememberPipelineService()
    val svc = svcState
    if (svc == null) {
        WaitingForService()
    } else {
        val phase by svc.phase.collectAsState()
        val parsed by svc.parsedPolicy.collectAsState()
        when (phase) {
            EditPipelineService.Phase.PARSING_INSTRUCTION -> WaitingFor("Parsing your instruction...")
            EditPipelineService.Phase.AWAITING_CONFIRMATION -> {
                val p = parsed
                if (p == null) WaitingFor("Awaiting policy...")
                else ReviewScreen(policy = p, service = svc, onConfirmed = goProcess, onCancel = goBack)
            }
            EditPipelineService.Phase.FAILED -> WaitingFor("Parse failed - go back and retry.")
            else -> WaitingFor("...")
        }
    }
}

@Composable
private fun ServiceBoundProcessScreen(goHome: () -> Unit, goShare: (File) -> Unit) {
    val svcState by rememberPipelineService()
    val svc = svcState
    if (svc == null) WaitingForService() else {
        ProcessScreen(service = svc, onDone = goShare, onCancel = goHome)
    }
}

@Composable
private fun WaitingForService() = WaitingFor("Connecting to service...")

@Composable
private fun WaitingFor(msg: String) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(msg) }
}

private fun shareOrPlay(ctx: android.content.Context, file: File) {
    val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", file)
    val intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, "video/mp4")
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    try {
        ctx.startActivity(intent)
    } catch (_: Throwable) {
    }
}
