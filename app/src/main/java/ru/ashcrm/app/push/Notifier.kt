package ru.ashcrm.app.push

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationChannelGroup
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import ru.ashcrm.app.MainActivity
import ru.ashcrm.app.R

/** Shows the cards of the CRM as notifications: one per key, a newer one replaces it, a read one is removed. */
object Notifier {
    const val CH_TEAM = "team"
    const val CH_CLIENTS = "clients"
    const val CH_CALLS = "calls"
    const val CH_WORK = "work"
    const val CH_SERVICE = "service"

    const val EXTRA_PATH = "path"
    const val ACTION_OPEN = "ru.ashcrm.app.OPEN"
    private const val ID = 1

    private val _received = MutableSharedFlow<Payload>(extraBufferCapacity = 8)
    /** Every push that came: the settings screen waits for its test here. */
    val received: SharedFlow<Payload> = _received

    /** Keys the CRM can mark read by «Прочитано» (§5.1); the test card and unknown ones get no button. */
    private val readable = listOf("chat-", "task-", "event-", "channel-", "queue-", "alert-", "missedcall-")

    fun createChannels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannelGroup(NotificationChannelGroup("crm", "AshCRM"))
        fun ch(id: String, name: String, importance: Int, desc: String) = NotificationChannel(id, name, importance).apply {
            description = desc
            group = "crm"
            enableVibration(importance >= NotificationManager.IMPORTANCE_HIGH)
        }
        nm.createNotificationChannels(
            listOf(
                ch(CH_CLIENTS, "Клиенты и очередь", NotificationManager.IMPORTANCE_HIGH, "Сообщения клиентов, новые беседы в очереди, «никого нет на смене»"),
                ch(CH_TEAM, "Сотрудники", NotificationManager.IMPORTANCE_HIGH, "Личные сообщения, группы, упоминания"),
                ch(CH_CALLS, "Звонки", NotificationManager.IMPORTANCE_HIGH, "Звонит клиент, идёт разговор"),
                ch(CH_WORK, "Задачи и встречи", NotificationManager.IMPORTANCE_DEFAULT, "Задачи, напоминания о встречах, документы на подпись"),
                ch(CH_SERVICE, "Служебные", NotificationManager.IMPORTANCE_DEFAULT, "Проверка уведомлений, обновления приложения"),
            )
        )
    }

    fun channelOf(type: String): String = when (type) {
        "mine", "queue", "alerts" -> CH_CLIENTS
        "call" -> CH_CALLS
        "tasks", "calendar", "policy" -> CH_WORK
        "test" -> CH_SERVICE
        else -> CH_TEAM
    }

    fun handle(ctx: Context, p: Payload, silent: Boolean) {
        _received.tryEmit(p)
        if (p.type == "clear") clear(ctx, p.clear) else show(ctx, p, silent)
    }

    fun show(ctx: Context, p: Payload, silent: Boolean) {
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED &&
            android.os.Build.VERSION.SDK_INT >= 33
        ) return
        val tag = p.tag.ifEmpty { "n-${System.nanoTime()}" }
        val open = PendingIntent.getActivity(
            ctx, tag.hashCode(),
            Intent(ctx, MainActivity::class.java).setAction(ACTION_OPEN).putExtra(EXTRA_PATH, p.url)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val b = NotificationCompat.Builder(ctx, channelOf(p.type))
            .setSmallIcon(R.drawable.ic_notify)
            .setColor(ContextCompat.getColor(ctx, R.color.accent))
            .setContentTitle(p.title.ifEmpty { "AshCRM" })
            .setContentText(p.body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(p.body))
            .setContentIntent(open)
            .setAutoCancel(true)
            .setShowWhen(true)
            .setSilent(silent)
            .setCategory(
                when (p.type) {
                    "call" -> NotificationCompat.CATEGORY_CALL
                    "tasks", "calendar", "policy" -> NotificationCompat.CATEGORY_REMINDER
                    "test" -> NotificationCompat.CATEGORY_STATUS
                    else -> NotificationCompat.CATEGORY_MESSAGE
                }
            )
            .setPriority(if (p.type in setOf("tasks", "calendar", "policy", "test")) NotificationCompat.PRIORITY_DEFAULT else NotificationCompat.PRIORITY_HIGH)
        if (readable.any { tag.startsWith(it) }) {
            val read = PendingIntent.getBroadcast(
                ctx, tag.hashCode(),
                Intent(ctx, ActionReceiver::class.java).setAction(ActionReceiver.ACTION_READ).putExtra(ActionReceiver.EXTRA_KEY, tag),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            b.addAction(0, "Прочитано", read)
        }
        runCatching { NotificationManagerCompat.from(ctx).notify(tag, ID, b.build()) }
    }

    fun clear(ctx: Context, keys: List<String>) {
        val nm = NotificationManagerCompat.from(ctx)
        keys.forEach { nm.cancel(it, ID) }
    }

    /** A notice of the app itself (an update is ready, the device was disconnected). */
    fun service(ctx: Context, tag: String, title: String, body: String) =
        show(ctx, Payload(type = "test", title = title, body = body, tag = tag), silent = false)
}
