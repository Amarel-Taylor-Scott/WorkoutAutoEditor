package com.workout.autoeditor.ui

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import com.workout.autoeditor.pipeline.EditPipelineService

/**
 * Compose-friendly binding to EditPipelineService. Returns a State<EditPipelineService?>
 * that updates when the service is connected/disconnected.
 *
 * Starts the service as a foreground service before binding so connect() succeeds even
 * if the user backgrounds the app.
 */
@Composable
fun rememberPipelineService(): State<EditPipelineService?> {
    val ctx = LocalContext.current
    val state = remember { mutableStateOf<EditPipelineService?>(null) }

    DisposableEffect(ctx) {
        val intent = Intent(ctx, EditPipelineService::class.java)
        ContextCompat.startForegroundService(ctx, intent)

        val conn = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                val b = binder as? EditPipelineService.LocalBinder
                state.value = b?.service()
            }
            override fun onServiceDisconnected(name: ComponentName?) {
                state.value = null
            }
        }
        ctx.bindService(intent, conn, Context.BIND_AUTO_CREATE)
        onDispose {
            // Unbind first, then stop. The service kills itself when no
            // clients are bound, but stopService is the explicit close.
            try { ctx.unbindService(conn) } catch (_: Throwable) {}
            try { ctx.stopService(intent) } catch (_: Throwable) {}
        }
    }
    return state
}
