package com.freeup.app

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import androidx.documentfile.provider.DocumentFile
import java.io.InputStream
import java.security.DigestInputStream
import java.security.MessageDigest

data class Item(val uri: Uri, val name: String, val size: Long, val date: Long, val video: Boolean)

fun scan(ctx: Context, months: Int, videosOnly: Boolean): List<Item> {
    val cutoff = System.currentTimeMillis() - months * 30L * 24 * 3600 * 1000
    val sources = buildList {
        add(MediaStore.Video.Media.EXTERNAL_CONTENT_URI to true)
        if (!videosOnly) add(MediaStore.Images.Media.EXTERNAL_CONTENT_URI to false)
    }
    val out = mutableListOf<Item>()
    for ((base, video) in sources) {
        val proj = arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.SIZE, MediaStore.MediaColumns.DATE_TAKEN)
        ctx.contentResolver.query(base, proj, "${MediaStore.MediaColumns.DATE_TAKEN} < ?",
            arrayOf(cutoff.toString()), "${MediaStore.MediaColumns.SIZE} DESC")?.use { c ->
            while (c.moveToNext()) {
                out += Item(ContentUris.withAppendedId(base, c.getLong(0)),
                    c.getString(1) ?: "file", c.getLong(2), c.getLong(3), video)
            }
        }
    }
    return out.sortedByDescending { it.size }
}

internal fun sha256(input: InputStream): String {
    val md = MessageDigest.getInstance("SHA-256")
    DigestInputStream(input, md).use { s -> val b = ByteArray(65536); while (s.read(b) != -1) { } }
    return md.digest().joinToString("") { "%02x".format(it) }
}

/** Copies one file, then re-reads the copy and compares size + SHA-256. Returns true only if identical. */
fun copyAndVerify(ctx: Context, item: Item, dir: DocumentFile): Boolean {
    val cr = ctx.contentResolver
    val name = if (dir.findFile(item.name) != null) "${item.uri.lastPathSegment}_${item.name}" else item.name
    val dest = dir.createFile(cr.getType(item.uri) ?: "application/octet-stream", name) ?: return false
    return try {
        val md = MessageDigest.getInstance("SHA-256")
        cr.openInputStream(item.uri)!!.use { src ->
            cr.openOutputStream(dest.uri)!!.use { dst ->
                val b = ByteArray(65536)
                while (true) { val n = src.read(b); if (n < 0) break; md.update(b, 0, n); dst.write(b, 0, n) }
                dst.flush()
            }
        }
        val srcHash = md.digest().joinToString("") { "%02x".format(it) }
        val ok = dest.length() == item.size && cr.openInputStream(dest.uri)!!.use { sha256(it) } == srcHash
        if (!ok) dest.delete()
        ok
    } catch (e: Exception) { dest.delete(); false }
}
