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
import ru.ashcrm.app.data.Prefs
import ru.ashcrm.app.graph
import ru.ashcrm.app.net.HttpError
import ru.ashcrm.app.push.Notifier
import ru.ashcrm.app.push.Register
import ru.ashcrm.app.update.UpdateDecision
import java.io.IOException

/** What the screen shows; the WebView itself lives under all of them once the portal is known. */
sealed interface Screen {
    data object Loading : Screen
    data object Portal : Screen
    data object Web : Screen
    data class Settings(val firstRun: Boolean) : Screen
    data class MustUpdate(val info: ru.ashcrm.app.net.AppInfo) : Screen
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

    /** Null until the CRM answered; Unsupported — the CRM has no API of the app. */
    private val _decision = MutableStateFlow<UpdateDecision?>(null)
    val decision: StateFlow<UpdateDecision?> = _decision

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
    var pendingSettings = false

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
    suspend fun setPortal(input: String): String? {
        val p = Portal.normalize(input) ?: return "Введите адрес вида crm.company.ru (http:// — только для локальной сети)"
        val decision = try {
            ru.ashcrm.app.update.VersionRules.decide(g.api.appInfo(p), ru.ashcrm.app.BuildConfig.VERSION_CODE, ru.ashcrm.app.BuildConfig.CONTRACT, null)
        } catch (e: HttpError) {
            return "По этому адресу не отвечает AshCRM (ответ ${e.status})"
        } catch (e: IOException) {
            return "Нет связи с $p: ${e.message ?: "проверьте адрес и интернет"}"
        } catch (e: Exception) {
            return "По этому адресу не отвечает AshCRM"
        }
        if (decision is UpdateDecision.Unsupported && !isAshCrm(p)) return "По этому адресу не отвечает AshCRM"
        g.prefs.set(Prefs.PORTAL, p)
        g.prefs.set(Prefs.CONNECT_SKIPPED, null)
        _portal.value = p
        open(p)
        return null
    }

    /** An AshCRM without the app API answers the probe of the Windows app with a JSON 401 (NOTIFY_CONTRACT §3.2). */
    private suspend fun isAshCrm(p: String) = try {
        g.api.token(p, "", ""); true
    } catch (e: HttpError) {
        e.status == 401 && e.code != null
    } catch (_: Exception) {
        false
    }

    private suspend fun open(p: String) {
        val decision = runCatching { g.updater.check(p) }.getOrNull()
        _decision.value = decision
        if (decision is UpdateDecision.Mandatory) {
            _screen.value = Screen.MustUpdate(decision.info)
            return
        }
        val supported = decision != null && decision !is UpdateDecision.Unsupported && decision !is UpdateDecision.CrmTooOld
        val connected = g.auth.connected.filterNotNull().first()
        startUrl = when {
            supported && !connected && g.prefs.get(Prefs.CONNECT_SKIPPED) != true -> g.auth.connectUrl(p)
            else -> pendingPath?.also { pendingPath = null } ?: "/"
        }
        _screen.value = when {
            pendingSettings -> Screen.Settings(firstRun = false).also { pendingSettings = false }
            supported && connected && g.prefs.get(Prefs.SETUP_DONE) != true -> Screen.Settings(firstRun = true)
            else -> Screen.Web
        }
        // No answer of the CRM at the start (no network): the WebView shows its error, the version is checked on resume.
        if (connected) Register.enqueue(getApplication())
    }

    /** Called on every return to the app: a version check at most every 6 hours. */
    fun onResume() {
        val p = _portal.value ?: return
        viewModelScope.launch {
            val last = g.prefs.get(Prefs.UPDATE_CHECKED_AT) ?: 0
            // «Не поддерживает» (or no answer) is checked on every return: the CRM may have been updated meanwhile.
            val settled = _decision.value.let { it != null && it !is UpdateDecision.Unsupported }
            if (settled && System.currentTimeMillis() - last < 6 * 3600_000L) return@launch
            val d = runCatching { g.updater.check(p) }.getOrNull() ?: return@launch
            _decision.value = d
            if (d is UpdateDecision.Mandatory) _screen.value = Screen.MustUpdate(d.info)
        }
    }

    suspend fun declineUpdate() {
        (_decision.value as? UpdateDecision.Optional)?.let { g.updater.decline(it.info) }
        _decision.value = UpdateDecision.UpToDate
    }

    fun dismissCrmTooOld() {
        _decision.value = UpdateDecision.UpToDate
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
            _navigate.emit("/")
            if (g.prefs.get(Prefs.SETUP_DONE) != true) _screen.value = Screen.Settings(firstRun = true)
        }
    }

    fun connect() {
        val p = _portal.value ?: return
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
            "settings" -> openSettings()
            "logout" -> {
                loggedOut = true
                disconnect()
                viewModelScope.launch { g.prefs.set(Prefs.CONNECT_SKIPPED, null) }
            }
            "login" -> viewModelScope.launch {
                loggedOut = false
                val supported = _decision.value.let { it != null && it !is UpdateDecision.Unsupported && it !is UpdateDecision.CrmTooOld }
                if (supported && g.auth.connected.value == false && g.prefs.get(Prefs.CONNECT_SKIPPED) != true) connect()
            }
        }
    }

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
            _decision.value = null
            _screen.value = Screen.Portal
        }
    }

    // ---- settings ----

    fun openSettings() {
        if (_screen.value == Screen.Loading) pendingSettings = true
        if (_portal.value != null && _screen.value !is Screen.MustUpdate) _screen.value = Screen.Settings(firstRun = false)
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
