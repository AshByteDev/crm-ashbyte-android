package ru.ashcrm.app.share

import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.util.Log
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.ashcrm.app.MainActivity
import ru.ashcrm.app.ui.AshTheme
import java.io.File
import java.io.IOException

/**
 * «Поделиться» from another app (docs/ANDROID_CONTRACT.md §7.7): copies the files while the grant of the other app is
 * valid, keeps the text, and hands over to [MainActivity], which opens the choice of a chat of the CRM.
 */
class ShareActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // A recreated activity (the process came back) has nothing to copy any more: the grant was for the first one.
        if (savedInstanceState != null) return finish()
        setContent {
            AshTheme {
                Surface(Modifier.fillMaxSize()) {
                    Column(
                        Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        CircularProgressIndicator()
                        Text("Готовлю для отправки…", style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        }
        val incoming = intent
        lifecycleScope.launch {
            val error = withContext(Dispatchers.IO) { take(incoming) }
            if (error != null) Toast.makeText(this@ShareActivity, error, Toast.LENGTH_LONG).show()
            else startActivity(
                Intent(this@ShareActivity, MainActivity::class.java).setAction(ACTION_SHARED)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            )
            finish()
        }
    }

    /** Copies what came; returns a text for a person when nothing can be sent. */
    private fun take(intent: Intent): String? {
        val action = intent.action
        if (action != Intent.ACTION_SEND && action != Intent.ACTION_SEND_MULTIPLE) return "Нечего отправить"
        val text = ShareRules.text(intent.getCharSequenceExtra(Intent.EXTRA_SUBJECT), intent.getCharSequenceExtra(Intent.EXTRA_TEXT))
        val uris = streams(intent).distinct()
        val outside = uris.filter(::allowed)
        if (uris.isNotEmpty() && outside.isEmpty() && text.isBlank()) return "Этот файл нельзя отправить"
        val (id, dir) = ShareStore.begin(this)
        val files = mutableListOf<SharedFile>()
        try {
            for (uri in outside.take(MAX_FILES)) {
                val n = files.size
                val (rawName, size) = describe(uri)
                if (size > 0 && size > dir.usableSpace - RESERVE) {
                    ShareStore.clear(this, null)
                    return "Не хватает места на телефоне"
                }
                val type = ShareRules.type(contentResolver.getType(uri) ?: intent.type?.takeUnless { '*' in it })
                val name = ShareRules.name(rawName, type, n) { MimeTypeMap.getSingleton().getExtensionFromMimeType(it) }
                val out = File(dir, n.toString())
                val copied = contentResolver.openInputStream(uri)?.use { src -> out.outputStream().use { src.copyTo(it) } }
                    ?: throw IOException("no stream")
                files += SharedFile(name, type, copied)
            }
        } catch (e: Exception) {
            Log.w(TAG, "share copy failed: ${e.javaClass.simpleName}")
            ShareStore.clear(this, null)
            return "Не удалось получить файл"
        }
        val s = Shared(id = id, at = System.currentTimeMillis(), files = files, text = text)
        if (s.empty) {
            ShareStore.clear(this, null)
            return "Нечего отправить"
        }
        ShareStore.save(this, s)
        return null
    }

    @Suppress("DEPRECATION")
    private fun streams(intent: Intent): List<Uri> {
        val extra: List<Uri> = when (intent.action) {
            Intent.ACTION_SEND_MULTIPLE ->
                if (Build.VERSION.SDK_INT >= 33) intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
                else intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM).orEmpty()
            else -> listOfNotNull(
                if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                else intent.getParcelableExtra(Intent.EXTRA_STREAM)
            )
        }
        if (extra.isNotEmpty()) return extra
        // Some apps put the files only into the clip.
        val clip = intent.clipData ?: return emptyList()
        return List(clip.itemCount) { clip.getItemAt(it).uri }.filterNotNull()
    }

    /**
     * Only `content://` of another app: a `file://` or a uri of our own provider would let any app make us read and send
     * our private files (the keys of the device, the session) into a chat.
     */
    private fun allowed(uri: Uri): Boolean =
        uri.scheme == ContentResolver.SCHEME_CONTENT && uri.authority?.startsWith(packageName) != true

    private fun describe(uri: Uri): Pair<String?, Long> = runCatching {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (!c.moveToFirst()) return@use null
            val name = c.getColumnIndex(OpenableColumns.DISPLAY_NAME).takeIf { it >= 0 }?.let { c.getString(it) }
            val size = c.getColumnIndex(OpenableColumns.SIZE).takeIf { it >= 0 && !c.isNull(it) }?.let { c.getLong(it) } ?: -1L
            name to size
        }
    }.getOrNull() ?: (uri.lastPathSegment to -1L)

    companion object {
        const val ACTION_SHARED = "ru.ashcrm.app.SHARED"
        private const val TAG = "AshShare"
        private const val MAX_FILES = 50
        /** Left free on the phone after a copy. */
        private const val RESERVE = 100L * 1024 * 1024
    }
}
