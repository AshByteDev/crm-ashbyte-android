package ru.ashcrm.app.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import ru.ashcrm.app.data.Portal
import ru.ashcrm.app.data.QrLogin
import ru.ashcrm.app.data.Prefs
import ru.ashcrm.app.graph
import ru.ashcrm.app.net.HttpError
import ru.ashcrm.app.net.Reach
import ru.ashcrm.app.push.Notifier
import ru.ashcrm.app.push.Register
import ru.ashcrm.app.update.CrmState
import ru.ashcrm.app.update.UpdateDecision
import java.io.IOException

/** What the screen shows; the WebView itself lives under all of them once the portal is known. */
sealed interface Screen {
    data object Loading : Screen
    data object Portal : Screen
    data object Web : Screen
    data class Settings(val firstRun: Boolean, val tab: SettingsTab = SettingsTab.Notifications) : Screen
    /** §3.5: connected, but Android shows nothing — the app tells it, it does not ask. */
    data object NeedNotifications : Screen
    data class MustUpdate(val release: ru.ashcrm.app.update.Release) : Screen
}

/** Where the QR scanner was opened from (§3.4). */
enum class ScanFrom {
    /** The address screen: the code gives the portal. */
    Portal,
    /** The page of the CRM (bridge `scan`): only a code of the same portal. */
    Page,
}

/** The state of the test notification on the settings screen. */
sealed interface TestState {
    data object Idle : TestState
    data object Sending : TestState
    data object Arrived : TestState
    data class Failed(val why: String) : TestState
}

class AppModel(app: Application) : AndroidViewModel(app) {
    private val g = app.graph

    private val _screen = MutableStateFlow<Screen>(Screen.Loading)
    val screen: StateFlow<Screen> = _screen

    private val _portal = MutableStateFlow<String?>(null)
    val portal: StateFlow<String?> = _portal

    /** What the CRM says about the app; Unknown until it answered. */
    private val _crm = MutableStateFlow(CrmState.Unknown)
    val crm: StateFlow<CrmState> = _crm

    /** What to offer about the version of the app (from GitVerse). */
    private val _decision = MutableStateFlow<UpdateDecision>(UpdateDecision.None)
    val decision: StateFlow<UpdateDecision> = _decision

    /** The CRM speaks another contract and GitVerse has nothing newer: told once per start. */
    private val _warnIncompatible = MutableStateFlow(false)

    /** The login form of the CRM is open: the app shows its own «Сменить адрес CRM» under it. */
    private val _onLogin = MutableStateFlow(false)
    val onLogin: StateFlow<Boolean> = _onLogin
    val warnIncompatible: StateFlow<Boolean> = _warnIncompatible

    val supported get() = _crm.value == CrmState.Supported

    /** The QR scanner is open (over everything) and from where; its hint under the frame. */
    private val _scan = MutableStateFlow<ScanFrom?>(null)
    val scan: StateFlow<ScanFrom?> = _scan
    private val _scanHint = MutableStateFlow<String?>(null)
    val scanHint: StateFlow<String?> = _scanHint

    /** A login code from the address screen waits for «Подключиться к <хост>?». */
    private val _qrPortal = MutableStateFlow<QrLogin?>(null)
    val qrPortal: StateFlow<QrLogin?> = _qrPortal

    /** «Позже» on the notifications screen: until the next start of the app. */
    private var notificationsLater = false

    /** The last path of the portal the page was on: after connecting, the page goes back there. */
    private var lastPath: String? = null
    private var returnPath: String? = null

    private val _test = MutableStateFlow<TestState>(TestState.Idle)
    val test: StateFlow<TestState> = _test

    /** Where the WebView must go (a path of the portal or a full connect address). */
    private val _navigate = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val navigate: SharedFlow<String> = _navigate

    private val _message = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val message: SharedFlow<String> = _message

    val connected get() = g.auth.connected

    /** The first page of the WebView: chosen once, before the WebView is made. */
    var startUrl: String = "/"
        private set

    /** A path a notification asked to open before the WebView was ready. */
    var pendingPath: String? = null

    /** The shortcut «Настройки уведомлений» was used before the portal was read. */
    var pendingSettings: SettingsTab? = null

    init {
        viewModelScope.launch {
            g.auth.load()
            val p = g.prefs.get(Prefs.PORTAL)
            _portal.value = p
            if (p == null) _screen.value = Screen.Portal else open(p)
        }
    }

    // ---- the portal ----

    /** Checks the address and saves it; returns a text for a person when it is not an AshCRM. */
    suspend fun setPortal(input: String, openUrl: String? = null): String? {
        val p = Portal.normalize(input) ?: return "Введите адрес вида crm.company.ru (http:// — только для локальной сети)"
        val crm = try {
            g.updater.crm(p)
        } catch (e: HttpError) {
            return "По этому адресу не отвечает AshCRM (ответ ${e.status})"
        } catch (e: IOException) {
            return "Нет связи с $p: ${e.message ?: "проверьте адрес и интернет"}"
        } catch (e: Exception) {
            // Not a network error and not an answer of the CRM: show what it was — a bare «не отвечает» hid a real bug.
            android.util.Log.w("AshPortal", "address check failed", e)
            offerUpdateIfAny()
            return "Не удалось проверить адрес (${e.javaClass.simpleName}: ${e.message?.take(160) ?: "без описания"})"
        }
        if (crm == CrmState.Unsupported && !isAshCrm(p)) {
            offerUpdateIfAny()
            return "По этому адресу не отвечает AshCRM"
        }
        g.prefs.set(Prefs.PORTAL, p)
        g.prefs.set(Prefs.CONNECT_SKIPPED, null)
        _portal.value = p
        open(p, openUrl)
        return null
    }

    /** The address check failed: if GitVerse has a newer app, the cause may be an old one — offer the update right here. */
    private suspend fun offerUpdateIfAny() {
        val r = g.updater.latest() ?: return
        if (r.versionCode > ru.ashcrm.app.BuildConfig.VERSION_CODE) _decision.value = UpdateDecision.Optional(r)
    }

    /** An AshCRM without the app API answers the probe of the Windows app with a JSON 401 (NOTIFY_CONTRACT §3.2). */
    private suspend fun isAshCrm(p: String) = try {
        g.api.token(p, "", ""); true
    } catch (e: HttpError) {
        e.status == 401 && e.code != null
    } catch (_: Exception) {
        false
    }

    private suspend fun open(p: String, openUrl: String? = null) {
        val crm = runCatching { g.updater.crm(p) }.getOrDefault(CrmState.Unknown)
        _crm.value = crm
        _warnIncompatible.value = crm == CrmState.Incompatible
        val connected = g.auth.connected.filterNotNull().first()
        startUrl = when {
            openUrl != null -> openUrl // the page /qr of a login code does the login; connecting follows the bridge `login`
            supported && !connected && g.prefs.get(Prefs.CONNECT_SKIPPED) != true -> g.auth.connectUrl(p)
            else -> pendingPath?.also { pendingPath = null } ?: "/"
        }
        _screen.value = when {
            pendingSettings != null -> Screen.Settings(firstRun = false, tab = pendingSettings!!).also { pendingSettings = null }
            else -> afterConnectScreen(connected)
        }
        // No answer of the CRM at the start (no network): the WebView shows its error, the CRM is asked again on resume.
        if (connected) Register.enqueue(getApplication())
        checkUpdate() // GitVerse may be slow or closed: never in the way of the start
    }

    private var updateJob: kotlinx.coroutines.Job? = null

    /** The latest release on GitVerse; nothing happens when GitVerse does not answer (owner: silently, next time). */
    private fun checkUpdate() {
        if (updateJob?.isActive == true) return
        updateJob = viewModelScope.launch {
            val d = g.updater.decide(_crm.value)
            _decision.value = d
            if (d is UpdateDecision.Mandatory) _screen.value = Screen.MustUpdate(d.release)
            if (d is UpdateDecision.Mandatory) _warnIncompatible.value = false
        }
    }

    /** Every return to the app: the CRM again while it did not answer or knew nothing; GitVerse at most every 6 hours. */
    fun onResume() {
        // Turned on in the system settings: the screen of §3.5 goes away by itself.
        if (_screen.value == Screen.NeedNotifications && notificationsAllowed(getApplication())) notificationsDone(later = false)
        val p = _portal.value ?: return
        viewModelScope.launch {
            if (_crm.value == CrmState.Unknown || _crm.value == CrmState.Unsupported) {
                runCatching { g.updater.crm(p) }.getOrNull()?.let { _crm.value = it }
            }
            val last = g.prefs.get(Prefs.UPDATE_CHECKED_AT) ?: 0
            if (System.currentTimeMillis() - last >= 6 * 3600_000L || _crm.value == CrmState.Incompatible) checkUpdate()
        }
    }

    suspend fun declineUpdate() {
        (_decision.value as? UpdateDecision.Optional)?.let { g.updater.decline(it.release) }
        _decision.value = UpdateDecision.None
    }

    fun dismissIncompatible() {
        _warnIncompatible.value = false
    }

    // ---- the device session ----

    /** `ashcrm://connect?…` from the connect page in the WebView (docs/ANDROID_CONTRACT.md §3). */
    fun onConnectCallback(uri: Uri) {
        val p = _portal.value ?: return
        if (uri.host != "connect") return
        val state = uri.getQueryParameter("state")
        if (!g.auth.expects(state)) return
        viewModelScope.launch {
            if (uri.getQueryParameter("cancel") != null) {
                g.auth.cancel()
                g.prefs.set(Prefs.CONNECT_SKIPPED, true)
            } else {
                val code = uri.getQueryParameter("code") ?: return@launch
                try {
                    g.auth.finish(p, code, state!!)
                    g.prefs.set(Prefs.CONNECT_SKIPPED, null)
                    loggedOut = false
                    Register.enqueue(getApplication(), replace = true)
                } catch (e: Exception) {
                    _message.emit("Не удалось подключить уведомления: ${e.message ?: "попробуйте ещё раз"}")
                }
            }
            _navigate.emit(returnPath ?: "/")
            returnPath = null
            _screen.value = afterConnectScreen(g.auth.connected.value == true)
        }
    }

    /** What follows the page once the phone is connected: §3.5 when Android shows nothing, then the first setup. */
    private suspend fun afterConnectScreen(connected: Boolean): Screen = when {
        supported && connected && !notificationsLater && !notificationsAllowed(getApplication()) -> Screen.NeedNotifications
        supported && connected && g.prefs.get(Prefs.SETUP_DONE) != true -> Screen.Settings(firstRun = true)
        else -> Screen.Web
    }

    /** The notifications screen is done: allowed, or «Позже» (until the next start). */
    fun notificationsDone(later: Boolean) {
        if (later) notificationsLater = true
        viewModelScope.launch { _screen.value = afterConnectScreen(g.auth.connected.value == true).takeIf { it != Screen.NeedNotifications } ?: Screen.Web }
    }

    fun connect() {
        val p = _portal.value ?: return
        returnPath = lastPath?.takeUnless { it == "/login" || it == "/qr" || it.startsWith("/desktop-connect") }
        _screen.value = Screen.Web
        viewModelScope.launch { _navigate.emit(g.auth.connectUrl(p)) }
    }

    fun disconnect() {
        val p = _portal.value
        viewModelScope.launch {
            val endpoint = g.prefs.get(Prefs.ENDPOINT)
            g.auth.disconnect(p) { access -> if (p != null && endpoint != null) runCatching { g.api.unsubscribe(p, access, endpoint) } }
        }
    }

    private var autoLoginAt = 0L
    /** Set at once on «Выйти» of the page: its next stop is /login, before the device session is even ended. */
    @Volatile private var loggedOut = false

    /**
     * The page landed on its login form while this phone has a device session (§3.2): open the page with the session
     * of the device instead of asking for the password again. Not after «Выйти» — that ends the device session too.
     */
    fun onUrlChanged(url: String) {
        val p = _portal.value ?: return
        if (!Portal.sameOrigin(p, url)) return
        val path = android.net.Uri.parse(url).path ?: return
        _onLogin.value = path == "/login"
        lastPath = Portal.safePath(url.removePrefix(p))
        if (path != "/login" || loggedOut || g.auth.connected.value != true) return
        val now = System.currentTimeMillis()
        if (now - autoLoginAt < 60_000) return // once a minute at most: a refused code must not loop
        autoLoginAt = now
        viewModelScope.launch {
            try {
                val access = g.auth.accessToken(p) ?: return@launch
                val code = g.api.webCode(p, access) ?: return@launch // the CRM is older: the usual login form stays
                _navigate.emit("$p/api/v1/auth/app/web-login?code=${java.net.URLEncoder.encode(code, "UTF-8")}")
            } catch (_: Exception) {
                // no network or refused: the login form stays, nothing is lost
            }
        }
    }

    /** A message of the page (§7.1). */
    fun onBridge(type: String) {
        when (type) {
            "settings" -> openSettings(fromPage = true)
            "scan" -> openScanner(ScanFrom.Page)
            "logout" -> {
                loggedOut = true
                disconnect()
                viewModelScope.launch { g.prefs.set(Prefs.CONNECT_SKIPPED, null) }
            }
            "login" -> viewModelScope.launch {
                loggedOut = false
                if (supported && g.auth.connected.value == false && g.prefs.get(Prefs.CONNECT_SKIPPED) != true) connect()
            }
        }
    }

    /** The page did not open: is it the internet of the phone or the CRM? */
    suspend fun reach(): Reach = _portal.value?.let { Reach.check(getApplication(), g.api.http, it) } ?: Reach.NoInternet

    /** Changing the address forgets the device session of the old portal. */
    fun changePortal() {
        viewModelScope.launch {
            disconnect()
            g.prefs.set(Prefs.PORTAL, null)
            g.prefs.set(Prefs.CONNECT_SKIPPED, null)
            g.prefs.set(Prefs.BLOCKED, null)
            android.webkit.CookieManager.getInstance().removeAllCookies(null)
            android.webkit.WebStorage.getInstance().deleteAllData()
            _portal.value = null
            _onLogin.value = false
            _crm.value = CrmState.Unknown
            _screen.value = Screen.Portal
        }
    }

    // ---- the QR login (§3.4) ----

    fun openScanner(from: ScanFrom) {
        if (from == ScanFrom.Page && _portal.value == null) return
        _scanHint.value = null
        _scan.value = from
    }

    fun closeScanner() {
        _scan.value = null
        _scanHint.value = null
    }

    /** A text of a QR code: a login code opens, anything else leaves the scanner open with a hint. */
    fun onScanned(text: String) {
        val from = _scan.value ?: return
        val qr = QrLogin.parse(text)
        if (qr == null) {
            _scanHint.value = "Это не код входа AshCRM"
            return
        }
        when (from) {
            ScanFrom.Portal -> {
                closeScanner()
                _qrPortal.value = qr
            }
            ScanFrom.Page -> {
                val p = _portal.value ?: return closeScanner()
                if (!Portal.sameOrigin(p, qr.portal)) {
                    _scanHint.value = "Код другого портала: ${qr.host}"
                    return
                }
                closeScanner()
                _screen.value = Screen.Web
                viewModelScope.launch { _navigate.emit(qr.url) }
            }
        }
    }

    fun dismissQrPortal() {
        _qrPortal.value = null
    }

    // ---- settings ----

    /**
     * [fromPage]: the page sends here when it lacks something (the place of a mark of «Доставка», §7.2) — then the
     * tab «Разрешения» opens if anything there is not given; a phone without notifications connected sees them first.
     */
    fun openSettings(fromPage: Boolean = false) {
        val tab = if (fromPage && connected.value == true && Perms.anyMissing(getApplication())) SettingsTab.Permissions else SettingsTab.Notifications
        if (_screen.value == Screen.Loading) pendingSettings = tab
        if (_portal.value != null && _screen.value !is Screen.MustUpdate) _screen.value = Screen.Settings(firstRun = false, tab = tab)
    }

    fun closeSettings() {
        viewModelScope.launch { g.prefs.set(Prefs.SETUP_DONE, true) }
        _test.value = TestState.Idle
        _screen.value = Screen.Web
    }

    /** «Проверить уведомления»: the CRM sends a test to every device of the employee; this one waits for its own. */
    fun sendTest() {
        val p = _portal.value ?: return
        _test.value = TestState.Sending
        viewModelScope.launch {
            _test.value = try {
                val access = g.auth.accessToken(p) ?: throw IOException("Уведомления не подключены")
                val arrived = launchWaiter()
                val res = g.api.pushTest(p, access)
                val devices = res["devices"]?.jsonPrimitive?.int ?: 0
                if (devices == 0) TestState.Failed("CRM не знает этот телефон — подождите минуту и повторите")
                else if (withTimeoutOrNull(30_000) { arrived.await() } == true) TestState.Arrived
                else TestState.Failed("CRM отправила, но телефон не получил за 30 секунд")
            } catch (e: Exception) {
                TestState.Failed(e.message ?: "Ошибка")
            }
        }
    }

    private fun launchWaiter() = kotlinx.coroutines.CompletableDeferred<Boolean>().also { d ->
        viewModelScope.launch {
            withTimeoutOrNull(31_000) { Notifier.received.first { it.type == "test" } }?.let { d.complete(true) }
        }
    }

    /** A notification was tapped: leave the settings so the page it opens is seen. */
    fun showWeb() {
        if (_screen.value is Screen.Settings) _screen.value = Screen.Web
    }

}
