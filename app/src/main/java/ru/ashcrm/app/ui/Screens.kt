package ru.ashcrm.app.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.net.toUri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import kotlinx.coroutines.launch
import ru.ashcrm.app.BuildConfig
import ru.ashcrm.app.data.Prefs
import ru.ashcrm.app.graph
import ru.ashcrm.app.update.CrmState
import ru.ashcrm.app.update.Release

private const val LOCAL_NETWORK = "android.permission.ACCESS_LOCAL_NETWORK"

private val Accent = Color(0xFF2481CC)
private val Ok = Color(0xFF2E9D57)
private val Warn = Color(0xFFD08A1B)

@Composable
fun AshTheme(content: @Composable () -> Unit) {
    val scheme = if (isSystemInDarkTheme()) darkColorScheme(primary = Accent, secondary = Accent)
    else lightColorScheme(primary = Accent, secondary = Accent)
    MaterialTheme(colorScheme = scheme, content = content)
}

// ---------------------------------------------------------------- the address of the portal

@Composable
fun PortalScreen(model: AppModel) {
    var text by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    // A login code (§3.4) gives the address and the page to open; a typed address opens the start page.
    var openUrl by remember { mutableStateOf<String?>(null) }
    val qr by model.qrPortal.collectAsState()
    val check: () -> Unit = { scope.launch { error = model.setPortal(text, openUrl); busy = false } }
    val localNet = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) check() else { busy = false; error = "Без доступа к локальной сети приложение не увидит CRM по этому адресу" }
    }
    val go: () -> Unit = {
        if (!busy) {
            busy = true
            error = null
            scope.launch {
                val p = ru.ashcrm.app.data.Portal.normalize(text)
                if (p != null && Build.VERSION.SDK_INT >= 37 && ru.ashcrm.app.data.Portal.needsLocalNetwork(p) &&
                    ContextCompat.checkSelfPermission(ctx, LOCAL_NETWORK) != PackageManager.PERMISSION_GRANTED
                ) localNet.launch(LOCAL_NETWORK) else check()
            }
        }
    }
    val submit: () -> Unit = { openUrl = null; go() }
    qr?.let { code ->
        AlertDialog(
            onDismissRequest = model::dismissQrPortal,
            title = { Text("Подключиться к ${code.host}?") },
            text = { Text("Вход подтвердите на компьютере, где показан QR-код.") },
            confirmButton = {
                TextButton(onClick = {
                    model.dismissQrPortal()
                    text = code.portal
                    openUrl = code.url
                    go()
                }) { Text("Подключиться") }
            },
            dismissButton = { TextButton(onClick = model::dismissQrPortal) { Text("Отмена") } },
        )
    }
    Surface(Modifier.fillMaxSize()) {
        Column(
            Modifier.windowInsetsPadding(WindowInsets.safeDrawing).padding(24.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Spacer(Modifier.height(32.dp))
            Text("AshCRM", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.SemiBold)
            Text("Введите адрес CRM вашей компании. Его можно узнать у администратора или в адресной строке браузера на компьютере.")
            OutlinedTextField(
                value = text, onValueChange = { text = it; error = null },
                label = { Text("Адрес CRM") }, placeholder = { Text("crm.company.ru") },
                singleLine = true, isError = error != null, enabled = !busy,
                supportingText = error?.let { { Text(it) } },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go, autoCorrectEnabled = false),
                keyboardActions = KeyboardActions(onGo = { submit() }),
                modifier = Modifier.fillMaxWidth(),
            )
            Button(onClick = submit, enabled = text.isNotBlank() && !busy, modifier = Modifier.fillMaxWidth()) {
                if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Text("Продолжить")
            }
            OutlinedButton(onClick = { model.openScanner(ScanFrom.Portal) }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                Text("Вход по QR")
            }
            Text(
                "На компьютере, где вы уже вошли в CRM: меню аватарки → «Вход по QR».",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

// ---------------------------------------------------------------- notifications are needed (§3.5)

@Composable
fun NeedNotificationsScreen(model: AppModel) {
    val ctx = LocalContext.current
    var refused by remember { mutableStateOf(false) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        if (notificationsAllowed(ctx)) model.notificationsDone(later = false) else refused = true
    }
    val openSettings = {
        ctx.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName))
    }
    // Android 13+ asks with its own dialog while it may; before 13, or once it stops asking, only the settings help.
    val canAsk = Build.VERSION.SDK_INT >= 33 &&
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
    Surface(Modifier.fillMaxSize()) {
        Column(
            Modifier.windowInsetsPadding(WindowInsets.safeDrawing).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        ) {
            Text("Для работы нужны уведомления", style = MaterialTheme.typography.headlineSmall)
            Text("Без них вы не узнаете о новых сообщениях и клиентах, пока CRM закрыта.")
            if (!refused) {
                // Only the settings can help: back from them without turning on — «Открыть настройки» and «Позже».
                Button(onClick = { if (canAsk) ask.launch(Manifest.permission.POST_NOTIFICATIONS) else { refused = true; openSettings() } }, modifier = Modifier.fillMaxWidth()) {
                    Text("Разрешить")
                }
            } else {
                Button(onClick = openSettings, modifier = Modifier.fillMaxWidth()) { Text("Открыть настройки") }
                OutlinedButton(onClick = { model.notificationsDone(later = true) }, modifier = Modifier.fillMaxWidth()) { Text("Позже") }
            }
        }
    }
}

// ---------------------------------------------------------------- settings and the first run

internal fun notificationsAllowed(ctx: Context) = NotificationManagerCompat.from(ctx).areNotificationsEnabled() &&
    (Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)

private fun batteryFree(ctx: Context) = ctx.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(ctx.packageName)

@Composable
fun SettingsScreen(model: AppModel, firstRun: Boolean) {
    val ctx = LocalContext.current
    val g = ctx.graph
    val scope = rememberCoroutineScope()
    val prefs by g.prefs.data.collectAsState(initial = null)
    val connected by model.connected.collectAsState()
    val crm by model.crm.collectAsState()
    val test by model.test.collectAsState()
    val portal by model.portal.collectAsState()
    var tick by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) { tick++; onPauseOrDispose { } }

    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { tick++ }
    val notifOk = remember(tick) { notificationsAllowed(ctx) }
    val batteryOk = remember(tick) { batteryFree(ctx) }
    val oem = remember { Oem.step() }
    val autostartOk = prefs?.get(Prefs.AUTOSTART_DONE) == true
    val supported = crm == CrmState.Supported
    var confirmPortal by remember { mutableStateOf(false) }
    var confirmDisconnect by remember { mutableStateOf(false) }
    var update by remember { mutableStateOf<Release?>(null) }
    var checking by remember { mutableStateOf(false) }

    Surface(Modifier.fillMaxSize()) {
        Column(
            Modifier.windowInsetsPadding(WindowInsets.safeDrawing).verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(if (firstRun) "Настройка уведомлений" else "Настройки приложения", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
                if (!firstRun) TextButton(onClick = model::closeSettings) { Text("Закрыть") }
            }
            if (firstRun) Text("Чтобы уведомления приходили сразу, даже когда телефон заблокирован, пройдите шаги ниже. Это один раз.")

            // --- connection
            val regError = prefs?.get(Prefs.REGISTER_ERROR)
            val (status, ok) = when {
                crm == CrmState.Incompatible -> "Эта версия приложения не подходит к CRM — обновите приложение или обратитесь к администратору" to false
                crm == CrmState.Unknown -> "Нет связи с CRM — проверим при следующем открытии" to false
                crm == CrmState.Unsupported -> "CRM пока не поддерживает уведомления приложения. Работать в CRM можно, уведомления появятся после обновления CRM." to false
                !BuildConfig.HAS_FIREBASE -> "Эта сборка без Firebase: уведомления недоступны" to false
                connected != true -> "Уведомления не подключены к этому телефону" to false
                regError != null -> regError to false
                prefs?.get(Prefs.ENDPOINT) == null -> "Регистрируем телефон в CRM…" to false
                else -> "Подключено: ${prefs?.get(Prefs.USER_NAME).orEmpty()}${prefs?.get(Prefs.TENANT_NAME)?.let { " · $it" }.orEmpty()}" to true
            }
            Step(title = "Подключение к CRM", text = status, ok = ok) {
                if (supported && connected != true) Button(onClick = model::connect) { Text("Подключить") }
            }
            Step(
                title = "Разрешение на уведомления",
                text = if (notifOk) "Разрешено" else "Без него Android не покажет ни одного уведомления",
                ok = notifOk,
            ) {
                if (!notifOk) Button(onClick = {
                    if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
                        notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    else ctx.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName))
                }) { Text("Разрешить") }
            }
            Step(
                title = "Работа без ограничений батареи",
                text = if (batteryOk) "Включено" else "Иначе система может задерживать уведомления. Заряд это почти не тратит: приложение не работает в фоне, его будит только пришедшее уведомление.",
                ok = batteryOk,
            ) {
                if (!batteryOk) Button(onClick = {
                    runCatching {
                        @Suppress("BatteryLife")
                        ctx.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, "package:${ctx.packageName}".toUri()))
                    }.onFailure { ctx.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
                }) { Text("Разрешить") }
            }
            if (oem != null) Step(title = oem.title, text = oem.hint, ok = autostartOk, unknown = !autostartOk) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { Oem.open(ctx, oem) }) { Text("Открыть") }
                    OutlinedButton(onClick = { scope.launch { g.prefs.set(Prefs.AUTOSTART_DONE, !autostartOk) } }) {
                        Text(if (autostartOk) "Не включено" else "Я включил")
                    }
                }
            }
            Step(
                title = "Проверка",
                text = when (val t = test) {
                    TestState.Idle -> "Отправим тестовое уведомление через CRM на все ваши устройства"
                    TestState.Sending -> "Ждём уведомление…"
                    TestState.Arrived -> "Уведомление пришло — всё работает"
                    is TestState.Failed -> t.why
                },
                ok = test == TestState.Arrived,
                unknown = test == TestState.Idle || test == TestState.Sending,
            ) {
                Button(onClick = model::sendTest, enabled = connected == true && notifOk && test != TestState.Sending) { Text("Проверить") }
            }

            if (firstRun) {
                Button(onClick = model::closeSettings, modifier = Modifier.fillMaxWidth()) { Text("Готово") }
                Text("Вернуться сюда можно позже: долгое нажатие на иконку AshCRM → «Настройки уведомлений».", style = MaterialTheme.typography.bodySmall)
            }

            HorizontalDivider()
            TextButton(onClick = {
                ctx.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName))
            }) { Text("Звуки и виды уведомлений") }
            Text("CRM: ${portal.orEmpty()}", style = MaterialTheme.typography.bodyMedium)
            Text("Версия ${BuildConfig.VERSION_NAME} · контракт ${BuildConfig.CONTRACT}", style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(enabled = !checking, onClick = {
                    val p = portal ?: return@OutlinedButton
                    checking = true
                    scope.launch {
                        val release = g.updater.latest()
                        when {
                            release == null -> android.widget.Toast.makeText(ctx, "Нет связи с сервером обновлений — попробуйте позже", android.widget.Toast.LENGTH_SHORT).show()
                            release.versionCode > BuildConfig.VERSION_CODE -> update = release
                            else -> android.widget.Toast.makeText(ctx, "Установлена последняя версия", android.widget.Toast.LENGTH_SHORT).show()
                        }
                        checking = false
                    }
                }) { Text("Проверить обновления") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (connected == true) OutlinedButton(onClick = { confirmDisconnect = true }) { Text("Отключить уведомления") }
                OutlinedButton(onClick = { confirmPortal = true }) { Text("Сменить адрес CRM") }
            }
        }
    }

    if (confirmPortal) AlertDialog(
        onDismissRequest = { confirmPortal = false },
        title = { Text("Сменить адрес CRM?") },
        text = { Text("Уведомления этого телефона отключатся, вход в CRM придётся выполнить заново.") },
        confirmButton = { TextButton(onClick = { confirmPortal = false; model.changePortal() }) { Text("Сменить") } },
        dismissButton = { TextButton(onClick = { confirmPortal = false }) { Text("Отмена") } },
    )
    if (confirmDisconnect) AlertDialog(
        onDismissRequest = { confirmDisconnect = false },
        title = { Text("Отключить уведомления?") },
        text = { Text("Телефон перестанет получать уведомления CRM. Работать в CRM можно будет как раньше.") },
        confirmButton = { TextButton(onClick = { confirmDisconnect = false; model.disconnect() }) { Text("Отключить") } },
        dismissButton = { TextButton(onClick = { confirmDisconnect = false }) { Text("Отмена") } },
    )
    update?.let { r -> UpdateDialog(r, mandatory = false, onLater = { update = null }) }
}

@Composable
private fun Step(title: String, text: String, ok: Boolean, unknown: Boolean = false, actions: @Composable () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant), modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.padding(top = 4.dp).size(12.dp).background(if (ok) Ok else if (unknown) Color.Gray else Warn, CircleShape))
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(title, fontWeight = FontWeight.SemiBold)
                Text(text, style = MaterialTheme.typography.bodyMedium)
                actions()
            }
        }
    }
}

// ---------------------------------------------------------------- updates

/** Download → check → system installer; with [mandatory] there is no «Позже». */
@Composable
fun UpdateDialog(release: Release, mandatory: Boolean, onLater: () -> Unit) {
    val ctx = LocalContext.current
    val g = ctx.graph
    val scope = rememberCoroutineScope()
    var progress by remember { mutableStateOf<Float?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var needPermission by remember { mutableStateOf(false) }
    var tick by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) { tick++; onPauseOrDispose { } }
    LaunchedEffect(tick) { if (needPermission && g.updater.canInstall()) needPermission = false }

    val start: () -> Unit = start@{
        if (!g.updater.canInstall()) {
            needPermission = true
            ctx.startActivity(g.updater.installPermissionIntent())
            return@start
        }
        scope.launch {
            error = null
            progress = 0f
            try {
                val file = g.updater.download(release) { progress = it }
                g.updater.install(file)
            } catch (e: Exception) {
                error = e.message ?: "Не удалось обновить"
            }
            progress = null
        }
    }
    val body: @Composable () -> Unit = {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                if (mandatory) "Эта версия приложения больше не поддерживается. Установите версию ${release.versionName}."
                else "Доступна версия ${release.versionName} (у вас ${BuildConfig.VERSION_NAME})."
            )
            if (release.notes.isNotEmpty()) {
                Text("Что нового", fontWeight = FontWeight.SemiBold)
                release.notes.take(5).forEach { Text("• $it", style = MaterialTheme.typography.bodyMedium) }
            }
            if (needPermission) Text("Разрешите AshCRM устанавливать приложения и вернитесь сюда.", color = Warn)
            progress?.let { LinearProgressIndicator(progress = { it }, modifier = Modifier.fillMaxWidth()) }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }
    if (mandatory) {
        Surface(Modifier.fillMaxSize()) {
            Column(
                Modifier.windowInsetsPadding(WindowInsets.safeDrawing).padding(24.dp).widthIn(max = 480.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Spacer(Modifier.height(32.dp))
                Text("Обновите приложение", style = MaterialTheme.typography.headlineSmall)
                body()
                Button(onClick = start, enabled = progress == null, modifier = Modifier.fillMaxWidth()) { Text("Скачать и установить") }
            }
        }
    } else {
        AlertDialog(
            onDismissRequest = { if (progress == null) onLater() },
            title = { Text("Обновление AshCRM") },
            text = body,
            confirmButton = { TextButton(onClick = start, enabled = progress == null) { Text("Обновить") } },
            dismissButton = { TextButton(onClick = onLater, enabled = progress == null) { Text("Позже") } },
        )
    }
}
