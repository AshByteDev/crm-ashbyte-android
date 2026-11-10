package ru.ashcrm.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import ru.ashcrm.app.push.Notifier
import ru.ashcrm.app.ui.AppModel
import ru.ashcrm.app.ui.AshTheme
import ru.ashcrm.app.ui.PortalScreen
import ru.ashcrm.app.ui.Screen
import ru.ashcrm.app.ui.SettingsScreen
import ru.ashcrm.app.ui.UpdateDialog
import ru.ashcrm.app.update.UpdateDecision
import ru.ashcrm.app.web.CrmWeb
import ru.ashcrm.app.web.WebHost
import ru.ashcrm.app.data.Portal as PortalAddress

class MainActivity : ComponentActivity(), WebHost {
    private val model: AppModel by viewModels()
    private var web: CrmWeb? = null
    private var offline by mutableStateOf(false)
    private var pageColor by mutableStateOf<androidx.compose.ui.graphics.Color?>(null)

    private var fileCallback: ValueCallback<Array<Uri>>? = null
    private val files = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        fileCallback?.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(r.resultCode, r.data))
        fileCallback = null
    }
    private var audioCallback: ((Boolean) -> Unit)? = null
    private val audio = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        audioCallback?.invoke(ok)
        audioCallback = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        handle(intent)
        publishShortcut()
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val w = web
                when {
                    model.screen.value is Screen.Settings && (model.screen.value as Screen.Settings).firstRun.not() -> model.closeSettings()
                    w != null && model.screen.value == Screen.Web && w.view.canGoBack() -> w.view.goBack()
                    else -> moveTaskToBack(true) // keep the page alive: the next open is instant
                }
            }
        })
        lifecycleScope.launch { model.message.collect { Toast.makeText(this@MainActivity, it, Toast.LENGTH_LONG).show() } }
        setContent { AshTheme { Root() } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    private val flusher = object : Runnable {
        override fun run() {
            android.webkit.CookieManager.getInstance().flush()
            window.decorView.postDelayed(this, 15_000)
        }
    }

    override fun onStart() {
        super.onStart()
        web?.resume()
        window.decorView.postDelayed(flusher, 15_000)
    }

    override fun onStop() {
        window.decorView.removeCallbacks(flusher)
        web?.pause()
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        model.onResume()
        web?.readPageColor()
    }

    /** A tapped notification opens its path; the shortcut opens the settings. */
    private fun handle(intent: Intent?) {
        when (intent?.action) {
            Notifier.ACTION_OPEN -> {
                val path = PortalAddress.safePath(intent.getStringExtra(Notifier.EXTRA_PATH)) ?: return
                model.showWeb()
                web?.load(path) ?: run { model.pendingPath = path }
            }
            ACTION_SETTINGS -> {
                ShortcutManagerCompat.reportShortcutUsed(this, "settings")
                model.openSettings()
            }
        }
    }

    private fun publishShortcut() {
        runCatching {
            ShortcutManagerCompat.setDynamicShortcuts(
                this, listOf(
                    ShortcutInfoCompat.Builder(this, "settings")
                        .setShortLabel(getString(R.string.shortcut_settings))
                        .setIcon(IconCompat.createWithResource(this, R.mipmap.ic_launcher))
                        .setIntent(Intent(this, MainActivity::class.java).setAction(ACTION_SETTINGS))
                        .build()
                )
            )
        }
    }

    @Composable
    private fun Root() {
        val screen by model.screen.collectAsState()
        val portal by model.portal.collectAsState()
        val decision by model.decision.collectAsState()
        val incompatible by model.warnIncompatible.collectAsState()
        val scope = rememberCoroutineScope()

        val bars = pageColor.takeIf { screen == Screen.Web } ?: MaterialTheme.colorScheme.background
        Box(Modifier.fillMaxSize().background(bars)) {
            val p = portal
            if (p != null && (screen == Screen.Web || screen is Screen.Settings)) {
                val w = remember(p) { CrmWeb(this@MainActivity, p, this@MainActivity).also { it.load(model.pendingPath?.also { model.pendingPath = null } ?: model.startUrl) } }
                DisposableEffect(w) {
                    web = w
                    onDispose { if (web === w) web = null; w.view.destroy() }
                }
                LaunchedEffect(w) { model.navigate.collect { w.load(it) } }
                AndroidView(factory = { w.view }, modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing))
                if (offline) Offline(onRetry = { offline = false; w.reload() })
            }
            when (val s = screen) {
                Screen.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                Screen.Portal -> PortalScreen(model)
                is Screen.Settings -> SettingsScreen(model, s.firstRun)
                is Screen.MustUpdate -> UpdateDialog(s.release, mandatory = true, onLater = {})
                Screen.Web -> Unit
            }
            if (screen == Screen.Web) {
                (decision as? UpdateDecision.Optional)?.let { d ->
                    UpdateDialog(d.release, mandatory = false, onLater = { scope.launch { model.declineUpdate() } })
                }
                if (incompatible && decision !is UpdateDecision.Optional) AlertDialog(
                    onDismissRequest = model::dismissIncompatible,
                    title = { Text("Версии не совпадают") },
                    text = { Text("Эта версия приложения не подходит к вашей CRM: уведомления не будут приходить. Работать в CRM можно как обычно; новая версия приложения появится в обновлениях.") },
                    confirmButton = { TextButton(onClick = model::dismissIncompatible) { Text("Понятно") } },
                )
            }
        }
    }

    @Composable
    private fun Offline(onRetry: () -> Unit) {
        Surface(Modifier.fillMaxSize()) {
            Column(
                Modifier.windowInsetsPadding(WindowInsets.safeDrawing).padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("Нет связи с CRM", style = MaterialTheme.typography.headlineSmall)
                Text("Проверьте интернет. Уведомления продолжат приходить, когда связь появится.")
                Button(onClick = onRetry) { Text("Повторить") }
            }
        }
    }

    // ---- WebHost ----

    override fun onConnectCallback(uri: Uri) = model.onConnectCallback(uri)
    override fun onBridge(type: String) = model.onBridge(type)
    override fun onMainFrameError(failed: Boolean) { offline = failed }
    override fun onPageFinished(url: String) {
        android.webkit.CookieManager.getInstance().flush()
    }

    override fun onUrlChanged(url: String) = model.onUrlChanged(url)

    override fun onPageColor(css: String) {
        val n = Regex("""[\d.]+""").findAll(css).map { it.value.toFloat() }.toList()
        if (n.size < 3 || (n.size > 3 && n[3] == 0f)) return
        val c = androidx.compose.ui.graphics.Color(n[0] / 255f, n[1] / 255f, n[2] / 255f)
        pageColor = c
        // Light page — dark icons of the status bar, and the other way round.
        val light = c.red * 0.299f + c.green * 0.587f + c.blue * 0.114f > 0.6f
        androidx.core.view.WindowCompat.getInsetsController(window, window.decorView).run {
            isAppearanceLightStatusBars = light
            isAppearanceLightNavigationBars = light
        }
    }

    /** A new activity makes a new WebView; the page opens where the person was. */
    override fun onRendererGone() {
        val p = model.portal.value
        val u = web?.view?.url
        if (p != null && u != null && PortalAddress.sameOrigin(p, u)) model.pendingPath = PortalAddress.safePath(u.removePrefix(p))
        web = null
        recreate()
    }

    override fun pickFiles(intent: Intent, callback: ValueCallback<Array<Uri>>) {
        fileCallback?.onReceiveValue(null)
        fileCallback = callback
        try {
            files.launch(intent)
        } catch (_: Exception) {
            fileCallback = null
            callback.onReceiveValue(null)
        }
    }

    override fun requestAudio(onResult: (Boolean) -> Unit) {
        audioCallback?.invoke(false)
        audioCallback = onResult
        audio.launch(android.Manifest.permission.RECORD_AUDIO)
    }

    companion object {
        const val ACTION_SETTINGS = "ru.ashcrm.app.SETTINGS"
    }
}
