package ru.ashcrm.app

import android.app.Application
import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import ru.ashcrm.app.data.Prefs
import ru.ashcrm.app.data.SecureBox
import ru.ashcrm.app.net.CrmApi
import ru.ashcrm.app.net.DeviceAuth
import ru.ashcrm.app.push.Notifier
import ru.ashcrm.app.push.PushKeys
import ru.ashcrm.app.update.Updater

/** The single set of services of the process: the activity, the push service and the workers share it. */
class Graph(app: Application) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val prefs = Prefs(app)
    val box = SecureBox()
    val api = CrmApi()
    val auth = DeviceAuth(prefs, box, api)
    val pushKeys = PushKeys(prefs, box)
    val updater = Updater(app, prefs, api)
}

class App : Application() {
    lateinit var graph: Graph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = Graph(this)
        // chrome://inspect on the computer sees the page of a debug build.
        if (BuildConfig.DEBUG) android.webkit.WebView.setWebContentsDebuggingEnabled(true)
        Notifier.createChannels(this)
        graph.scope.launch { graph.auth.load() }
        ru.ashcrm.app.net.KeepAlive.schedule(this)
    }
}

val Context.graph: Graph get() = (applicationContext as App).graph
