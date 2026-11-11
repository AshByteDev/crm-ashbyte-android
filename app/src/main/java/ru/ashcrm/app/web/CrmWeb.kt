package ru.ashcrm.app.web

import android.Manifest
import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.webkit.CookieManager
import android.webkit.PermissionRequest
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import ru.ashcrm.app.data.Portal
import ru.ashcrm.app.net.APP_AGENT
import ru.ashcrm.app.net.json
import java.io.File
import java.util.Base64

/** What the page and the WebView ask of the activity: it owns the launchers of files and permissions. */
interface WebHost {
    fun onConnectCallback(uri: Uri)
    fun onBridge(type: String)
    fun pickFiles(intent: Intent, callback: ValueCallback<Array<Uri>>)
    fun requestAudio(onResult: (Boolean) -> Unit)
    fun onMainFrameError(failed: Boolean)
    fun onPageFinished(url: String)
    /** The renderer died (out of memory, an update of WebView): this WebView is unusable and must be made again. */
    fun onRendererGone()
    /** The background of the page (`rgb(…)`), for the bars of the system around it. */
    fun onPageColor(css: String) = Unit
    /** Every change of the address, history.pushState of the page included. */
    fun onUrlChanged(url: String) = Unit
}

/** The page of the CRM in a WebView: only the portal opens inside, everything else goes to the browser. */
@SuppressLint("SetJavaScriptEnabled")
class CrmWeb(private val ctx: Context, private val portal: String, private val host: WebHost) {

    // MATCH_PARENT matters: with the default WRAP_CONTENT a page of `height: 100%` measures itself to 0 (100vh = 0).
    val view = WebView(ctx).apply {
        layoutParams = android.view.ViewGroup.LayoutParams(android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.MATCH_PARENT)
    }
    private var mainFrameFailed = false

    init {
        with(view.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            allowFileAccess = false
            allowContentAccess = false
            setSupportMultipleWindows(false)
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            userAgentString = "$userAgentString $APP_AGENT"
        }
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(view, false)
        view.webViewClient = Client()
        view.webChromeClient = Chrome()
        view.setDownloadListener { url, _, disposition, mime, _ -> download(url, disposition, mime) }
        installBridge()
    }

    fun load(path: String) {
        view.loadUrl(if (path.startsWith("http")) path else portal + path)
    }

    /**
     * The app goes to the background: write the cookies (the refresh key of the page rotates; one left only in memory
     * would be lost with the process, and the old one on disk is a replay the CRM ends the session for) and stop the
     * scripts of the page so nothing rotates while Android may unload the app. Notifications come through FCM anyway.
     */
    fun pause() {
        CookieManager.getInstance().flush()
        view.onPause()
        view.pauseTimers()
    }

    fun resume() {
        view.resumeTimers()
        view.onResume()
    }

    fun reload() {
        mainFrameFailed = false
        if (view.url.isNullOrEmpty() || view.url == "about:blank") load("/") else view.reload()
    }

    /**
     * The bridge of the page (docs/ANDROID_CONTRACT.md §7.1), only for the origin of the portal, and a script at the
     * start of every page: remember the name of a `download` link and keep `blob:` links alive long enough to read.
     */
    /** The bars of the system take the colour of the page (light, dark — whatever theme the CRM has on). */
    fun readPageColor() {
        view.evaluateJavascript("getComputedStyle(document.body).backgroundColor") { v ->
            v?.trim('"')?.takeIf { it.startsWith("rgb") }?.let(host::onPageColor)
        }
    }

    private fun installBridge() {
        val origins = setOf(portal)
        if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            WebViewCompat.addWebMessageListener(view, "AshCRMAndroid", origins) { _, message, _, isMainFrame, _ ->
                if (!isMainFrame) return@addWebMessageListener
                val msg = runCatching { json.decodeFromString<JsonObject>(message.data ?: "") }.getOrNull() ?: return@addWebMessageListener
                when (val t = msg["t"]?.jsonPrimitive?.content) {
                    "blob" -> saveBlob(msg["name"]?.jsonPrimitive?.content, msg["data"]?.jsonPrimitive?.content)
                    "blob-error" -> toast("Не удалось сохранить файл")
                    null -> Unit
                    else -> host.onBridge(t)
                }
            }
        }
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            WebViewCompat.addDocumentStartJavaScript(view, START_SCRIPT, origins)
        }
    }

    private inner class Client : WebViewClient() {
        override fun shouldOverrideUrlLoading(v: WebView, request: WebResourceRequest): Boolean {
            if (!request.isForMainFrame) return false
            val uri = request.url
            return when {
                uri.scheme == "ashcrm" -> { host.onConnectCallback(uri); true }
                (uri.scheme == "https" || uri.scheme == "http") && Portal.sameOrigin(portal, uri.toString()) -> false
                else -> { openOutside(uri); true }
            }
        }

        override fun onPageStarted(v: WebView, url: String, favicon: Bitmap?) {
            mainFrameFailed = false
        }

        override fun onPageFinished(v: WebView, url: String) {
            host.onMainFrameError(mainFrameFailed)
            if (!mainFrameFailed) host.onPageFinished(url)
            readPageColor()
        }

        override fun doUpdateVisitedHistory(v: WebView, url: String, isReload: Boolean) {
            host.onUrlChanged(url)
        }

        override fun onRenderProcessGone(v: WebView, detail: android.webkit.RenderProcessGoneDetail): Boolean {
            host.onRendererGone()
            return true
        }

        override fun onReceivedError(v: WebView, request: WebResourceRequest, error: WebResourceError) {
            if (request.isForMainFrame) {
                mainFrameFailed = true
                host.onMainFrameError(true)
            }
        }
    }

    private inner class Chrome : WebChromeClient() {
        override fun onShowFileChooser(v: WebView, callback: ValueCallback<Array<Uri>>, params: FileChooserParams): Boolean {
            host.pickFiles(pickIntent(params), callback)
            return true
        }

        override fun onPermissionRequest(request: PermissionRequest) {
            if (!Portal.sameOrigin(portal, request.origin.toString())) return request.deny()
            val audio = PermissionRequest.RESOURCE_AUDIO_CAPTURE
            if (audio !in request.resources) return request.deny()
            val grant = { ok: Boolean -> if (ok) request.grant(arrayOf(audio)) else request.deny() }
            if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) grant(true)
            else host.requestAudio(grant)
        }
    }

    /**
     * The system picker for an `<input type=file>`. `accept` may hold extensions (".pdf,.docx"), which Android
     * does not take as a type: they turn into MIME types here, and what has no known type opens the picker for any file.
     */
    private fun pickIntent(params: WebChromeClient.FileChooserParams): Intent {
        val types = params.acceptTypes.orEmpty()
            .flatMap { it.split(',') }.map { it.trim().lowercase() }.filter { it.isNotEmpty() }
            .map { if (it.startsWith('.')) android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(it.drop(1)) ?: "*/*" else it }
            .distinct()
        return Intent(Intent.ACTION_GET_CONTENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            if (types.isEmpty() || "*/*" in types) type = "*/*"
            else if (types.size == 1) type = types[0]
            else { type = "*/*"; putExtra(Intent.EXTRA_MIME_TYPES, types.toTypedArray()) }
            if (params.mode == WebChromeClient.FileChooserParams.MODE_OPEN_MULTIPLE) putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        }
    }

    private fun openOutside(uri: Uri) {
        try {
            ctx.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: ActivityNotFoundException) {
            toast("Нет приложения, чтобы открыть ссылку")
        }
    }

    private fun download(url: String, disposition: String?, mime: String?) {
        if (url.startsWith("blob:")) {
            // A blob lives in the page only: the page reads it and hands it over through the bridge.
            view.evaluateJavascript(BLOB_SCRIPT.replace("__URL__", json.encodeToString(url)), null)
            return
        }
        if (!url.startsWith("https://") && !url.startsWith("http://")) return
        val name = URLUtil.guessFileName(url, disposition, mime)
        val req = DownloadManager.Request(url.toUri())
            .setTitle(name)
            .setMimeType(mime)
            .addRequestHeader("User-Agent", view.settings.userAgentString)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name)
        if (Portal.sameOrigin(portal, url)) CookieManager.getInstance().getCookie(url)?.let { req.addRequestHeader("Cookie", it) }
        try {
            ctx.getSystemService(DownloadManager::class.java).enqueue(req)
            toast("Скачивается: $name")
        } catch (_: Exception) {
            openOutside(url.toUri())
        }
    }

    private fun saveBlob(name: String?, dataUrl: String?) {
        val comma = dataUrl?.indexOf(',') ?: -1
        if (dataUrl == null || comma < 0) return toast("Не удалось сохранить файл")
        val mime = dataUrl.substring(5, comma).substringBefore(';').ifEmpty { "application/octet-stream" }
        val bytes = runCatching { Base64.getDecoder().decode(dataUrl.substring(comma + 1)) }.getOrNull() ?: return toast("Не удалось сохранить файл")
        val file = (name?.takeIf { it.isNotBlank() } ?: URLUtil.guessFileName(null, null, mime)).replace('/', '_')
        runCatching {
            if (Build.VERSION.SDK_INT >= 29) {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, file)
                    put(MediaStore.Downloads.MIME_TYPE, mime)
                }
                val uri = ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: error("no uri")
                ctx.contentResolver.openOutputStream(uri)!!.use { it.write(bytes) }
                toast("Сохранено в «Загрузки»: $file")
            } else {
                val dir = ctx.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: ctx.filesDir
                File(dir, file).writeBytes(bytes)
                toast("Сохранено: ${dir.path}/$file")
            }
        }.onFailure { toast("Не удалось сохранить файл") }
    }

    private fun toast(text: String) = Toast.makeText(ctx, text, Toast.LENGTH_SHORT).show()

    companion object {
        private val START_SCRIPT = """
            (() => {
              const click = HTMLAnchorElement.prototype.click;
              HTMLAnchorElement.prototype.click = function () {
                if (this.hasAttribute('download')) window.__ashDownload = this.getAttribute('download') || '';
                return click.apply(this, arguments);
              };
              document.addEventListener('click', (e) => {
                const a = e.target && e.target.closest && e.target.closest('a[download]');
                if (a) window.__ashDownload = a.getAttribute('download') || '';
              }, true);
              const revoke = URL.revokeObjectURL.bind(URL);
              URL.revokeObjectURL = (u) => setTimeout(() => revoke(u), 60000);
            })();
        """.trimIndent()

        private val BLOB_SCRIPT = """
            (async () => {
              try {
                const b = await (await fetch(__URL__)).blob();
                const r = new FileReader();
                r.onload = () => AshCRMAndroid.postMessage(JSON.stringify({t: 'blob', name: window.__ashDownload || '', data: r.result}));
                r.readAsDataURL(b);
              } catch (e) { AshCRMAndroid.postMessage(JSON.stringify({t: 'blob-error'})); }
            })();
        """.trimIndent()
    }
}
