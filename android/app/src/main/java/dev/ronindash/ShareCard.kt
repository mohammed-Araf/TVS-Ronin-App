package dev.ronindash

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.provider.MediaStore
import androidx.core.content.FileProvider
import java.io.File

/**
 * The ride card itself is drawn by the trip page (assets/trip.html → share preview sheet), so the rider sees
 * exactly what gets posted and can pick a style/format. This side only shares or saves the finished PNG.
 * The card has no map tiles or coordinates, and the first/last 300 m of the route are cut off.
 */
object ShareCard {
    fun shareBytes(ctx: Context, id: String, png: ByteArray, km: Double) {
        val out = File(ctx.cacheDir, "share").apply { mkdirs() }
        out.listFiles()?.forEach { it.delete() }
        val f = File(out, "ride-$id.png").apply { writeBytes(png) }
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", f)
        val send = Intent(Intent.ACTION_SEND).setType("image/png")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .putExtra(Intent.EXTRA_TEXT, "%.1f km on my Ronin".format(km))
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        ctx.startActivity(Intent.createChooser(send, "Share ride").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /** Saves to Pictures/Ronin Dash (no storage permission needed on Android 10+). */
    fun saveToGallery(ctx: Context, id: String, png: ByteArray): Boolean = try {
        val cr = ctx.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "ride-$id-${System.currentTimeMillis() / 1000}.png")
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Ronin Dash")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = cr.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)!!
        cr.openOutputStream(uri)!!.use { it.write(png) }
        cr.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
        true
    } catch (e: Exception) {
        Bike.log("Save card failed: ${e.message}"); false
    }
}
