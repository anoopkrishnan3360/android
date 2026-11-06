package com.freeup.app

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

data class JobState(
    val running: Boolean = false, val done: Int = 0, val total: Int = 0, val current: String = "",
    val verified: List<Item> = emptyList(), val message: String = ""
)

/** Shared state between the UI and the foreground service. */
object CopyJob {
    val state = MutableStateFlow(JobState())
    @Volatile var pending: List<Item> = emptyList()
    @Volatile var dest: Uri? = null
    @Volatile var cancelled = false

    fun start(ctx: Context, items: List<Item>, dest: Uri) {
        if (state.value.running) return
        pending = items; this.dest = dest; cancelled = false
        state.value = JobState(running = true, total = items.size)
        ContextCompat.startForegroundService(ctx, Intent(ctx, CopyService::class.java))
    }
}

class CopyService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "CANCEL") { CopyJob.cancelled = true; return START_NOT_STICKY }
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("copy", "Transfers", NotificationManager.IMPORTANCE_LOW))
        startForeground(1, notif("Starting...", 0, 0), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        scope.launch {
            val ctx = applicationContext
            val items = CopyJob.pending
            val dir = CopyJob.dest?.let { DocumentFile.fromTreeUri(ctx, it) }
            val prefs = ctx.getSharedPreferences("journal", MODE_PRIVATE)
            val journal = prefs.getStringSet("ok", emptySet())!!.toMutableSet()
            val ok = mutableListOf<Item>(); var failed = 0
            if (dir == null || !dir.canWrite()) {
                CopyJob.state.value = JobState(message = "Cannot write to the destination. Is the drive connected?")
            } else {
                for ((i, item) in items.withIndex()) {
                    if (CopyJob.cancelled) break
                    CopyJob.state.update { it.copy(done = i, current = item.name) }
                    nm.notify(1, notif(item.name, i, items.size))
                    // Resume: skip files already verified in an earlier run and still present on the drive.
                    val existing = dir.findFile(item.name)
                    val already = journal.contains(item.uri.toString()) && existing != null && existing.length() == item.size
                    if (already || copyAndVerify(ctx, item, dir)) {
                        ok += item; journal += item.uri.toString()
                        prefs.edit().putStringSet("ok", journal.toSet()).apply()
                    } else failed++
                }
                val note = if (CopyJob.cancelled) "Cancelled. " else ""
                CopyJob.state.value = JobState(done = ok.size, total = items.size, verified = ok,
                    message = "$note Verified ${ok.size} of ${items.size}" + (if (failed > 0) ", $failed failed (originals kept)." else ".") +
                        (if (ok.isNotEmpty()) " Safe to delete originals." else ""))
            }
            stopForeground(STOP_FOREGROUND_REMOVE)
            nm.notify(2, NotificationCompat.Builder(ctx, "copy").setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle("FreeUp").setContentText(CopyJob.state.value.message).setAutoCancel(true).build())
            stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun notif(text: String, done: Int, total: Int): Notification {
        val cancel = PendingIntent.getService(this, 0, Intent(this, CopyService::class.java).setAction("CANCEL"), PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, "copy").setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("FreeUp: copying and verifying").setContentText(text).setOnlyAlertOnce(true).setOngoing(true)
            .setProgress(total, done, total == 0).addAction(0, "Cancel", cancel).build()
    }

    override fun onDestroy() { scope.cancel(); super.onDestroy() }
}
