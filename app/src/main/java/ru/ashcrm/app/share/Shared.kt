package ru.ashcrm.app.share

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import ru.ashcrm.app.net.json
import java.io.File
import java.util.UUID

/** One file another app shared, copied into the app (docs/ANDROID_CONTRACT.md §7.7). */
@Serializable
data class SharedFile(val name: String, val type: String, val size: Long)

/** What came through «Поделиться»: files and/or text, waiting for the page of the CRM to take them into a chat. */
@Serializable
data class Shared(val id: String, val at: Long, val files: List<SharedFile> = emptyList(), val text: String = "") {
    val empty get() = files.isEmpty() && text.isBlank()
}

/** The pure rules of «Поделиться»: no Android here, for the tests. */
object ShareRules {
    /** Files are served to the page at `<CRM>/.ashcrm-share/<id>/<n>`; the WebView answers, the CRM never sees it. */
    const val PREFIX = "/.ashcrm-share/"

    /** A share waits for the page at most this long. */
    const val KEEP_MS = 60 * 60 * 1000L

    fun url(id: String, n: Int) = "$PREFIX$id/$n"

    /** `/.ashcrm-share/<id>/<n>` → (id, n); anything else → null. */
    fun parse(path: String?): Pair<String, Int>? {
        if (path == null || !path.startsWith(PREFIX)) return null
        val parts = path.removePrefix(PREFIX).split('/')
        if (parts.size != 2 || !parts[0].matches(Regex("[a-f0-9]{32}"))) return null
        val n = parts[1].toIntOrNull()?.takeIf { it >= 0 } ?: return null
        return parts[0] to n
    }

    /** A subject (a title of the page in a browser) and the text (its link): one text, the subject only when not in it. */
    fun text(subject: CharSequence?, text: CharSequence?): String {
        val s = subject?.toString()?.trim().orEmpty()
        val t = text?.toString()?.trim().orEmpty()
        return when {
            s.isEmpty() -> t
            t.isEmpty() -> s
            t.contains(s) -> t
            else -> "$s\n$t"
        }
    }

    /** A name for a person and the CRM: no folders, no control characters, an extension from the type if there is none. */
    fun name(raw: String?, type: String, n: Int, ext: (String) -> String?): String {
        val base = raw.orEmpty().substringAfterLast('/').substringAfterLast('\\')
            .filter { it >= ' ' && it != '\u007f' }.trim().trimStart('.').take(150)
        val named = base.ifEmpty { "file-${n + 1}" }
        if (named.contains('.')) return named
        return ext(type)?.let { "$named.$it" } ?: named
    }

    /** A type the CRM can trust: `type/subtype`, else the generic one. */
    fun type(raw: String?): String {
        val t = raw?.trim()?.lowercase().orEmpty()
        return if (t.matches(Regex("[a-z0-9][a-z0-9.+-]*/[a-z0-9][a-z0-9.+-]*"))) t else "application/octet-stream"
    }

    fun expired(s: Shared, now: Long) = now - s.at > KEEP_MS || now < s.at - 60_000

    /** The answer to `{t: "share"}`: what the page shows and where it fetches each file. */
    fun reply(s: Shared?): String = JsonObject(
        mapOf(
            "t" to JsonPrimitive("share"),
            "id" to (s?.id?.let(::JsonPrimitive) ?: JsonNull),
            "text" to JsonPrimitive(s?.text.orEmpty()),
            "files" to JsonArray(
                s?.files.orEmpty().mapIndexed { i, f ->
                    JsonObject(
                        mapOf(
                            "url" to JsonPrimitive(url(s!!.id, i)),
                            "name" to JsonPrimitive(f.name),
                            "type" to JsonPrimitive(f.type),
                            "size" to JsonPrimitive(f.size),
                        )
                    )
                }
            ),
        )
    ).toString()

    fun newId(): String = UUID.randomUUID().toString().replace("-", "")
}

/**
 * The one share waiting for the page: files in `files/share/<id>/<n>`, the list in `share.json` (survives the process
 * being unloaded while the person logs in). A new share replaces the old one; done, cancelled or an hour old — removed.
 */
object ShareStore {
    private const val DIR = "share"
    private const val META = "share.json"

    private fun root(ctx: Context) = File(ctx.filesDir, DIR)

    /** A fresh folder for the files of a new share; the old share goes. */
    fun begin(ctx: Context): Pair<String, File> {
        clear(ctx)
        val id = ShareRules.newId()
        return id to File(root(ctx), id).apply { mkdirs() }
    }

    fun save(ctx: Context, s: Shared) {
        val f = File(root(ctx), META)
        val tmp = File(root(ctx), "$META.tmp")
        tmp.writeText(json.encodeToString(Shared.serializer(), s))
        tmp.renameTo(f)
    }

    /** The waiting share; an old or broken one is removed. */
    @Synchronized
    fun current(ctx: Context, now: Long = System.currentTimeMillis()): Shared? {
        val f = File(root(ctx), META)
        if (!f.exists()) return null
        val s = runCatching { json.decodeFromString(Shared.serializer(), f.readText()) }.getOrNull()
        if (s == null || ShareRules.expired(s, now) || s.files.indices.any { !File(File(root(ctx), s.id), it.toString()).isFile }) {
            clear(ctx)
            return null
        }
        return s
    }

    /** The file [n] of the share [id], only while that share is the waiting one. */
    fun file(ctx: Context, id: String, n: Int): Pair<SharedFile, File>? {
        val s = current(ctx) ?: return null
        if (s.id != id) return null
        val meta = s.files.getOrNull(n) ?: return null
        return meta to File(File(root(ctx), id), n.toString())
    }

    /** Removes the share; with [id] — only when it is still that one (a newer share stays). */
    @Synchronized
    fun clear(ctx: Context, id: String? = null) {
        if (id != null) {
            val s = runCatching { json.decodeFromString(Shared.serializer(), File(root(ctx), META).readText()) }.getOrNull()
            if (s != null && s.id != id) return
        }
        root(ctx).deleteRecursively()
    }
}
