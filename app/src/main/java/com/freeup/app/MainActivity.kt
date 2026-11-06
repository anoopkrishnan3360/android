package com.freeup.app

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

const val FREE_LIMIT_BYTES = 1L * 1024 * 1024 * 1024

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(Modifier.fillMaxSize()) {
                    FreeUpScreen()
                }
            }
        }
    }
}

/** Hands files to the Google Drive app, which uploads them itself. FreeUp stays offline (no INTERNET permission). */
fun sendToDrive(ctx: Context, items: List<Item>): String {
    val batch = items.take(200)
    val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
        type = "*/*"
        putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(batch.map { it.uri }))
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        setPackage("com.google.android.apps.docs")
    }
    return try {
        ctx.startActivity(intent)
        "Sent ${batch.size} items to the Google Drive app" +
            (if (items.size > batch.size) " (first 200; send the rest next)." else ".") +
            " Drive uploads in the background. FreeUp cannot confirm the upload, so check Drive before deleting anything."
    } catch (e: ActivityNotFoundException) {
        "Google Drive app not found. Install it from Google Play first."
    }
}

fun fmt(b: Long): String =
    if (b >= (1L shl 30))
        "%.2f GB".format(b / 1073741824.0)
    else
        "%.1f MB".format(b / 1048576.0)

@Composable
fun FreeUpScreen() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    val prefs = remember {
        ctx.getSharedPreferences("app", Context.MODE_PRIVATE)
    }

    val billing = remember {
        Billing(ctx.applicationContext).also { it.connect() }
    }

    val isPremium by billing.premium.collectAsState()
    val price by billing.price.collectAsState()
    val job by CopyJob.state.collectAsState()

    var months by remember { mutableIntStateOf(12) }
    var videosOnly by remember { mutableStateOf(true) }
    var items by remember { mutableStateOf(listOf<Item>()) }
    var selected by remember { mutableStateOf(setOf<Uri>()) }

    var dest by remember {
        mutableStateOf(
            prefs.getString("dest", null)?.let(Uri::parse)
        )
    }

    var status by remember {
        mutableStateOf("Tap Scan to find old photos and videos.")
    }

    var scanning by remember { mutableStateOf(false) }
    var driveNote by remember { mutableStateOf("") }
    val busy = scanning || job.running

    fun doScan() = scope.launch {
        scanning = true
        status = "Scanning..."

        try {
            items = withContext(Dispatchers.IO) {
                scan(ctx, months, videosOnly)
            }

            selected = emptySet()

            status = "Found ${items.size} items (${fmt(items.sumOf { it.size })})."
        } catch (e: Exception) {
            status = "Scan failed: ${e.message ?: "Unknown error"}"
        } finally {
            scanning = false
        }
    }

    val permLauncher =
        rememberLauncherForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { r ->

            val media =
                r[Manifest.permission.READ_MEDIA_IMAGES] == true ||
                r[Manifest.permission.READ_MEDIA_VIDEO] == true ||
                r[Manifest.permission.READ_EXTERNAL_STORAGE] == true ||
                r[Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED] == true

            if (media) {
                doScan()
            } else {
                status = "Permission denied. FreeUp needs photo/video access to scan your library."
            }
        }

    val folderLauncher =
        rememberLauncherForActivityResult(
            ActivityResultContracts.OpenDocumentTree()
        ) { uri ->

            if (uri != null) {
                try {
                    ctx.contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION or
                            Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    )

                    prefs.edit()
                        .putString("dest", uri.toString())
                        .apply()

                    dest = uri
                    status = "Destination set."
                } catch (e: Exception) {
                    status = "Could not access destination: ${e.message}"
                }
            }
        }

    val deleteLauncher =
        rememberLauncherForActivityResult(
            ActivityResultContracts.StartIntentSenderForResult()
        ) { r ->

            if (r.resultCode == Activity.RESULT_OK) {
                val gone = job.verified

                status =
                    "Freed ${fmt(gone.sumOf { it.size })}. " +
                    "Empty Trash/Recently Deleted if needed."

                items = items.filter { item ->
                    item.uri !in gone.map { it.uri }.toSet()
                }

                selected = emptySet()
                CopyJob.state.value = JobState()

            } else {
                status = "Delete cancelled. Your originals are untouched."
            }
        }

    Column(
        Modifier
            .padding(16.dp)
            .fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {

        Text(
            "FreeUp",
            style = MaterialTheme.typography.headlineMedium
        )

        Text(
            "Copy to your drive, verify, then delete. Nothing leaves your phone.",
            style = MaterialTheme.typography.bodyMedium
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(6, 12, 24).forEach { m ->
                FilterChip(
                    selected = months == m,
                    onClick = { months = m },
                    label = { Text("> $m mo") }
                )
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(
                checked = videosOnly,
                onCheckedChange = { videosOnly = it }
            )

            Spacer(Modifier.width(8.dp))

            Text("Videos only (frees most space)")
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {

            Button(
                enabled = !busy,
                onClick = {
                    val permissions =
                        if (Build.VERSION.SDK_INT >= 34) {
                            arrayOf(
                                Manifest.permission.READ_MEDIA_IMAGES,
                                Manifest.permission.READ_MEDIA_VIDEO,
                                Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
                                Manifest.permission.POST_NOTIFICATIONS
                            )
                        } else if (Build.VERSION.SDK_INT >= 33) {
                            arrayOf(
                                Manifest.permission.READ_MEDIA_IMAGES,
                                Manifest.permission.READ_MEDIA_VIDEO,
                                Manifest.permission.POST_NOTIFICATIONS
                            )
                        } else {
                            arrayOf(
                                Manifest.permission.READ_EXTERNAL_STORAGE
                            )
                        }

                    permLauncher.launch(permissions)
                }
            ) {
                Text("Scan")
            }

            OutlinedButton(
                enabled = !busy,
                onClick = { folderLauncher.launch(null) }
            ) {
                Text(
                    if (dest == null)
                        "Choose drive folder"
                    else
                        "Change folder"
                )
            }
        }

        val chosen = items.filter { it.uri in selected }
        val chosenSize = chosen.sumOf { it.size }

        Button(
            enabled = !busy && dest != null && chosen.isNotEmpty(),
            onClick = {
                if (!isPremium && chosenSize > FREE_LIMIT_BYTES) {
                    status =
                        "Free limit is 1 GB per run. Select less, or upgrade."
                } else {
                    CopyJob.start(ctx, chosen, dest!!)
                }
            }
        ) {
            Text(
                "Copy & verify (${chosen.size} items, ${fmt(chosenSize)})"
            )
        }

        OutlinedButton(
            enabled = !busy && chosen.isNotEmpty(),
            onClick = {
                driveNote =
                    if (!isPremium && chosenSize > FREE_LIMIT_BYTES)
                        "Free limit is 1 GB per run. Select less, or upgrade."
                    else
                        sendToDrive(ctx, chosen)
            }
        ) {
            Text("Send to Google Drive (${chosen.size})")
        }

        // API 30+ deletion with user consent.
        // Android 10 requires a separate legacy consent flow.
        Button(
            enabled = !busy && job.verified.isNotEmpty(),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.error
            ),
            onClick = {

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {

                    try {
                        val pi = MediaStore.createDeleteRequest(
                            ctx.contentResolver,
                            job.verified.map { it.uri }
                        )

                        deleteLauncher.launch(
                            IntentSenderRequest.Builder(
                                pi.intentSender
                            ).build()
                        )

                    } catch (e: Exception) {
                        status =
                            "Unable to request deletion: ${e.message}"
                    }

                } else {
                    status =
                        "Android 10 requires the legacy media deletion consent flow. " +
                        "Your originals have not been deleted."
                }
            }
        ) {
            Text("Delete ${job.verified.size} verified originals")
        }

        if (job.running) {

            Text(
                "Copying ${job.done + 1}/${job.total}: ${job.current}",
                style = MaterialTheme.typography.bodyMedium
            )

            LinearProgressIndicator(
                progress = {
                    if (job.total == 0) 0f
                    else job.done / job.total.toFloat()
                },
                modifier = Modifier.fillMaxWidth()
            )

            OutlinedButton(
                onClick = { CopyJob.cancelled = true }
            ) {
                Text("Cancel")
            }

        } else {
            Text(
                job.message.ifBlank { status },
                style = MaterialTheme.typography.bodyMedium
            )
        }

        if (driveNote.isNotBlank()) {
            Text(driveNote, style = MaterialTheme.typography.bodySmall)
        }

        if (scanning) {
            LinearProgressIndicator(
                modifier = Modifier.fillMaxWidth()
            )
        }

        if (isPremium) {

            Text(
                "Premium unlocked. Thank you!",
                style = MaterialTheme.typography.labelLarge
            )

        } else {

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {

                Button(
                    enabled = price != null,
                    onClick = {
                        billing.buy(ctx as Activity)
                    }
                ) {
                    Text(
                        "Unlock unlimited${price?.let { " · $it" } ?: ""}"
                    )
                }

                TextButton(
                    onClick = { billing.restore() }
                ) {
                    Text("Restore purchase")
                }
            }
        }

        LazyColumn(
            Modifier.weight(1f)
        ) {

            items(
                items,
                key = { it.uri.toString() }
            ) { item ->

                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {

                    Checkbox(
                        checked = item.uri in selected,
                        onCheckedChange = { checked ->
                            selected =
                                if (checked)
                                    selected + item.uri
                                else
                                    selected - item.uri
                        }
                    )

                    Column {
                        Text(
                            item.name,
                            maxLines = 1
                        )

                        Text(
                            "${if (item.video) "Video" else "Photo"} · ${fmt(item.size)}",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
        }
    }
}
