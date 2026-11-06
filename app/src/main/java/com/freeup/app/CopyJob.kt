
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
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID

import android.content.ContentResolver

data class JobState(
    val running: Boolean = false,
    val done: Int = 0,
    val total: Int = 0,
    val current: String = "",
    val verified: List<Item> = emptyList(),
    val message: String = ""
)

/**
 * Shared transfer state between UI and foreground service.
 */
object CopyJob {

    val state = MutableStateFlow(JobState())

    @Volatile
    var pending: List<Item> = emptyList()

    @Volatile
    var dest: Uri? = null

    @Volatile
    var cancelled = false

    fun start(ctx: Context, items: List<Item>, dest: Uri) {

        if (state.value.running || items.isEmpty()) return

        pending = items.toList()
        this.dest = dest
        cancelled = false

        state.value = JobState(
            running = true,
            total = items.size
        )

        try {
            ContextCompat.startForegroundService(
                ctx,
                Intent(ctx, CopyService::class.java)
            )
        } catch (e: Exception) {
            state.value = JobState(
                message = "Unable to start transfer: ${e.message}"
            )
        }
    }
}

private class CopyCancelledException : Exception()

class CopyService : Service() {

    private val scope =
        CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int
    ): Int {

        if (intent?.action == "CANCEL") {
            CopyJob.cancelled = true
            return START_NOT_STICKY
        }

        val nm = getSystemService(NotificationManager::class.java)

        nm.createNotificationChannel(
            NotificationChannel(
                "copy",
                "Transfers",
                NotificationManager.IMPORTANCE_LOW
            )
        )

        try {
            startForeground(
                1,
                notif("Starting...", 0, 0),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } catch (e: Exception) {
            CopyJob.state.value = JobState(
                message = "Could not start foreground service: ${e.message}"
            )
            stopSelf(startId)
            return START_NOT_STICKY
        }

        scope.launch {

            val ctx = applicationContext
            val items = CopyJob.pending
            val destinationUri = CopyJob.dest

            val dir = destinationUri?.let {
                DocumentFile.fromTreeUri(ctx, it)
            }

            val verified = mutableListOf<Item>()
            var failed = 0
            var processed = 0

            try {

                if (dir == null || !dir.canWrite()) {

                    CopyJob.state.value = JobState(
                        message = "Cannot write to destination. Check whether the drive is connected and writable."
                    )

                } else {

                    for ((index, item) in items.withIndex()) {

                        if (CopyJob.cancelled) break

                        CopyJob.state.update {
                            it.copy(
                                done = processed,
                                current = item.name
                            )
                        }

                        nm.notify(
                            1,
                            notif(item.name, index, items.size)
                        )

                        val success = try {
                            copyAndVerify(ctx, item, dir)
                        } catch (e: Exception) {
                            false
                        }

                        if (success) {
                            verified.add(item)
                        } else if (!CopyJob.cancelled) {
                            failed++
                        }

                        processed++

                        CopyJob.state.update {
                            it.copy(
                                done = processed,
                                verified = verified.toList()
                            )
                        }
                    }

                    val cancelled = CopyJob.cancelled

                    val message = buildString {

                        if (cancelled) {
                            append("Cancelled. ")
                        }

                        append(
                            "Verified ${verified.size} of ${items.size} files."
                        )

                        if (failed > 0) {
                            append(" $failed files failed. Originals kept.")
                        }

                        if (verified.isNotEmpty()) {
                            append(" Verified copies are ready for deletion.")
                        } else {
                            append(" No files are ready for deletion.")
                        }
                    }

                    CopyJob.state.value = JobState(
                        running = false,
                        done = processed,
                        total = items.size,
                        verified = verified.toList(),
                        message = message
                    )
                }

            } catch (e: Exception) {

                CopyJob.state.value = JobState(
                    running = false,
                    done = processed,
                    total = items.size,
                    verified = verified.toList(),
                    message = "Transfer error: ${e.message}. Verified originals remain safe."
                )

            } finally {

                CopyJob.cancelled = false

                stopForeground(STOP_FOREGROUND_REMOVE)

                nm.notify(
                    2,
                    NotificationCompat.Builder(ctx, "copy")
                        .setSmallIcon(
                            android.R.drawable.stat_sys_download_done
                        )
                        .setContentTitle("FreeUp")
                        .setContentText(
                            CopyJob.state.value.message
                        )
                        .setAutoCancel(true)
                        .build()
                )

                stopSelf(startId)
            }
        }

        return START_NOT_STICKY
    }

    /**
     * Copies a file to the destination.
     * Checks SHA-256 of source and destination before marking verified.
     */
    private fun copyAndVerify(
        ctx: Context,
        item: Item,
        dir: DocumentFile
    ): Boolean {

        var tempFile: DocumentFile? = null

        try {

            if (CopyJob.cancelled) return false

            val resolver = ctx.contentResolver

            // Check whether an identical verified copy already exists.
            val existing = dir.findFile(item.name)

            if (
                existing != null &&
                existing.isFile &&
                existing.length() == item.size
            ) {

                val sourceHash = hashSource(resolver, item.uri)
                val destinationHash = hashDestination(existing)

                if (sourceHash == destinationHash) {
                    return true
                }
            }

            if (CopyJob.cancelled) return false

            val mimeType =
                resolver.getType(item.uri) ?: "application/octet-stream"

            // Create a unique temporary file to avoid exposing incomplete copies.
            val tempName = ".__freeup_${UUID.randomUUID()}.tmp"

            tempFile = dir.createFile(
                "application/octet-stream",
                tempName
            ) ?: throw IOException("Cannot create temporary destination file")

            val sourceDigest = MessageDigest.getInstance("SHA-256")

            val input = resolver.openInputStream(item.uri)
                ?: throw IOException("Cannot open source file")

            val output = resolver.openOutputStream(tempFile.uri)
                ?: throw IOException("Cannot open destination file")

            input.use { source ->

                output.use { destination ->

                    val buffer = ByteArray(1024 * 1024)

                    while (true) {

                        if (CopyJob.cancelled) {
                            throw CopyCancelledException()
                        }

                        val count = source.read(buffer)

                        if (count == -1) break

                        sourceDigest.update(buffer, 0, count)

                        destination.write(buffer, 0, count)
                    }

                    destination.flush()
                }
            }

            if (CopyJob.cancelled) {
                throw CopyCancelledException()
            }

            // Verify destination by reading it back.
            val sourceHash = sourceDigest.digest()
            val destinationHash = hashDestination(tempFile)

            if (!sourceHash.contentEquals(destinationHash)) {
                throw IOException("SHA-256 verification failed")
            }

            if (CopyJob.cancelled) {
                throw CopyCancelledException()
            }

            // Do not expose temporary extension in final filename.
            val finalName = uniqueFileName(dir, item.name)

            if (!tempFile.renameTo(finalName)) {
                throw IOException("Could not finalize verified file")
            }

            tempFile = null

            return true

        } catch (e: CopyCancelledException) {

            return false

        } catch (e: Exception) {

            return false

        } finally {

            // Remove incomplete temporary files.
            tempFile?.delete()
        }
    }

    /**
     * Calculate SHA-256 directly from original media URI.
     */
    private fun hashSource(
        resolver: ContentResolver,
        uri: Uri
    ): ByteArray {

        val digest = MessageDigest.getInstance("SHA-256")

        val input = resolver.openInputStream(uri)
            ?: throw IOException("Cannot read original file")

        input.use {

            val buffer = ByteArray(1024 * 1024)

            while (true) {

                if (CopyJob.cancelled) {
                    throw CopyCancelledException()
                }

                val count = it.read(buffer)

                if (count == -1) break

                digest.update(buffer, 0, count)
            }
        }

        return digest.digest()
    }

    /**
     * Calculate SHA-256 from copied destination file.
     */
    private fun hashDestination(
        file: DocumentFile
    ): ByteArray {

        val digest = MessageDigest.getInstance("SHA-256")

        val input = contentResolver.openInputStream(file.uri)
            ?: throw IOException("Cannot read copied file")

        input.use {

            val buffer = ByteArray(1024 * 1024)

            while (true) {

                if (CopyJob.cancelled) {
                    throw CopyCancelledException()
                }

                val count = it.read(buffer)

                if (count == -1) break

                digest.update(buffer, 0, count)
            }
        }

        return digest.digest()
    }

    /**
     * Avoid overwriting files with duplicate names.
     */
    private fun uniqueFileName(
        dir: DocumentFile,
        originalName: String
    ): String {

        if (dir.findFile(originalName) == null) {
            return originalName
        }

        val dot = originalName.lastIndexOf('.')

        val base =
            if (dot > 0) originalName.substring(0, dot)
            else originalName

        val extension =
            if (dot > 0) originalName.substring(dot)
            else ""

        var index = 1

        while (true) {

            val candidate =
                "$base ($index)$extension"

            if (dir.findFile(candidate) == null) {
                return candidate
            }

            index++
        }
    }

    private fun notif(
        text: String,
        done: Int,
        total: Int
    ): Notification {

        val cancel = PendingIntent.getService(
            this,
            0,
            Intent(this, CopyService::class.java)
                .setAction("CANCEL"),
            PendingIntent.FLAG_UPDATE_CURRENT or
                PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, "copy")
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("FreeUp: copying and verifying")
            .setContentText(text)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setProgress(total, done, total == 0)
            .addAction(0, "Cancel", cancel)
            .build()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
