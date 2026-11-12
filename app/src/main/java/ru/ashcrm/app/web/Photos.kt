package ru.ashcrm.app.web

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.MediaStore
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Photos from the camera for an `<input type=file>` (docs/ANDROID_CONTRACT.md §7.3): the camera app writes into a
 * file of the cache, the page gets it through the FileProvider. The page reads the file only when the form is sent, so
 * a file stays until the next start of the process or for a day at most.
 */
object Photos {
    private const val DIR = "photos"
    private const val KEEP_MS = 24 * 60 * 60 * 1000L

    fun canShoot(ctx: Context): Boolean =
        ctx.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY) &&
            Intent(MediaStore.ACTION_IMAGE_CAPTURE).resolveActivity(ctx.packageManager) != null

    fun newFile(ctx: Context): File {
        val dir = File(ctx.cacheDir, DIR).apply { mkdirs() }
        val now = System.currentTimeMillis()
        dir.listFiles()?.filter { now - it.lastModified() > KEEP_MS }?.forEach { it.delete() }
        val name = "photo-" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(Date(now))
        var f = File(dir, "$name.jpg")
        var n = 2
        while (f.exists()) f = File(dir, "$name-${n++}.jpg")
        return f
    }

    fun uri(ctx: Context, f: File): Uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", f)

    /** A new process has no page that could still read an old photo. */
    fun clear(ctx: Context) {
        File(ctx.cacheDir, DIR).listFiles()?.forEach { it.delete() }
    }
}
