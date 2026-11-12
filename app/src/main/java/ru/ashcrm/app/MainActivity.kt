package ru.ashcrm.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.webkit.ValueCallback
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
import androidx.compose.foundation.layout.navigationBars
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
import ru.ashcrm.app.web.Photos
import ru.ashcrm.app.ui.AshTheme
import ru.ashcrm.app.ui.NeedNotificationsScreen
import ru.ashcrm.app.ui.PortalScreen
import ru.ashcrm.app.ui.ScannerScreen
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
    private var fileIntent: Intent? = null
    private val files = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        filesDone(picked(r.resultCode, r.data))
    }

    /** «Камера» or «Галерея и файлы» for an input of images (§7.3). */
    private var photoChoice by mutableStateOf(false)
    private var photo: java.io.File? = null
    private val capture = registerForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val f = photo
        photo = null
        if (ok && f != null && f.length() > 0) filesDone(arrayOf(Photos.uri(this, f)))
        else { f?.delete(); filesDone(null) }
    }
    private val cameraPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) shoot() else fileIntent?.let(::launchPicker) ?: filesDone(null)
    }
    private var locationCallback: ((Boolean) -> Unit)? = null
    private val location = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r ->
        locationCallback?.invoke(r.values.any { it })
        locationCallback = null
    }

    /** With «several files» allowed the picker hands them in clipData, even one file; parseResult reads only data. */
    private fun picked(code: Int, data: Intent?): Array<Uri>? {
        if (code != RESULT_OK || data == null) return null
        val clip = data.clipData
        val uris = if (clip != null && clip.itemCount > 0) List(clip.itemCount) { clip.getItemAt(it).uri }.filterNotNull()
        else listOfNotNull(data.data)
        return uris.takeIf { it.isNotEmpty() }?.toTypedArray()
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
                    model.scan.value != null -> model.closeScanner()
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
        val onLogin by model.onLogin.collectAsState()
        val scan by model.scan.collectAsState()
        val scanHint by model.scanHint.collectAsState()
        var confirmPortal by remember { mutableStateOf(false) }
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
                // The login form of the CRM has no way back to the address screen: the app adds its own, under the form.
                if (onLogin && screen == Screen.Web && !offline) TextButton(
                    onClick = { confirmPortal = true },
                    modifier = Modifier.align(Alignment.BottomCenter).windowInsetsPadding(WindowInsets.navigationBars).padding(bottom = 12.dp),
                ) { Text("Сменить адрес CRM") }
                if (offline) Offline(onRetry = { offline = false; w.reload() })
            }
            when (val s = screen) {
                Screen.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                Screen.Portal -> PortalScreen(model)
                is Screen.Settings -> SettingsScreen(model, s.firstRun)
                is Screen.MustUpdate -> UpdateDialog(s.release, mandatory = true, onLater = {})
                Screen.NeedNotifications -> NeedNotificationsScreen(model)
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
            if (confirmPortal) AlertDialog(
                onDismissRequest = { confirmPortal = false },
                title = { Text("Сменить адрес CRM?") },
                text = { Text("Вы точно хотите сменить адрес CRM? Приложение вернётся к вводу адреса.") },
                confirmButton = { TextButton(onClick = { confirmPortal = false; model.changePortal() }) { Text("Сменить") } },
                dismissButton = { TextButton(onClick = { confirmPortal = false }) { Text("Отмена") } },
            )
            if (photoChoice) AlertDialog(
                onDismissRequest = { photoChoice = false; filesDone(null) },
                title = { Text("Приложить фото") },
                confirmButton = { TextButton(onClick = { photoChoice = false; takePhoto() }) { Text("Камера") } },
                dismissButton = { TextButton(onClick = { photoChoice = false; fileIntent?.let(::launchPicker) ?: filesDone(null) }) { Text("Галерея и файлы") } },
            )
            // The QR scanner (§3.4) lies over everything; «Закрыть» leaves the page as it was.
            if (scan != null) ScannerScreen(hint = scanHint, onText = model::onScanned, onClose = model::closeScanner)
            // The address check failed and GitVerse has a newer app: offer it right on the address screen.
            if (screen == Screen.Portal) (decision as? UpdateDecision.Optional)?.let { d ->
                UpdateDialog(d.release, mandatory = false, onLater = { scope.launch { model.declineUpdate() } })
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

    override fun pickFiles(intent: Intent, callback: ValueCallback<Array<Uri>>, camera: Boolean, cameraOnly: Boolean) {
        fileCallback?.onReceiveValue(null)
        fileCallback = callback
        fileIntent = intent
        when {
            !camera || !Photos.canShoot(this) -> launchPicker(intent)
            cameraOnly -> takePhoto()
            else -> photoChoice = true
        }
    }

    private fun filesDone(result: Array<Uri>?) {
        fileCallback?.onReceiveValue(result)
        fileCallback = null
        fileIntent = null
    }

    private fun launchPicker(intent: Intent) {
        try {
            files.launch(intent)
        } catch (_: Exception) {
            filesDone(null)
        }
    }

    /** CAMERA is asked right when «Камера» is chosen; refused — the picker of files, as before 1.5. */
    private fun takePhoto() {
        if (androidx.core.content.ContextCompat.checkSelfPermission(this, android.Manifest.permission.CAMERA) == android.content.pm.PackageManager.PERMISSION_GRANTED) shoot()
        else cameraPermission.launch(android.Manifest.permission.CAMERA)
    }

    private fun shoot() {
        val f = Photos.newFile(this)
        photo = f
        try {
            capture.launch(Photos.uri(this, f))
        } catch (_: Exception) {
            f.delete()
            photo = null
            fileIntent?.let(::launchPicker) ?: filesDone(null)
        }
    }

    override fun requestLocation(onResult: (Boolean) -> Unit) {
        locationCallback?.invoke(false)
        locationCallback = onResult
        location.launch(arrayOf(android.Manifest.permission.ACCESS_FINE_LOCATION, android.Manifest.permission.ACCESS_COARSE_LOCATION))
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
