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
import androidx.core.net.toUri
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
    const val CH_DELIVERY = "delivery"
    const val CH_DELIVERY_ALERT = "delivery_alert"

    const val EXTRA_PATH = "path"
    const val ACTION_OPEN = "ru.ashcrm.app.OPEN"
    private const val ID = 1

    private val _received = MutableSharedFlow<Payload>(extraBufferCapacity = 8)
    /** Every push that came: the settings screen waits for its test here. */
    val received: SharedFlow<Payload> = _received

    /** Keys the CRM can mark read by «Прочитано» (§5.1); the test card and unknown ones get no button. */
    private val readable = listOf("chat-", "task-", "event-", "channel-", "queue-", "alert-", "missedcall-", "delivery-")

    fun createChannels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannelGroup(NotificationChannelGroup("crm", "AshCRM"))
        fun ch(id: String, name: String, importance: Int, desc: String) = NotificationChannel(id, name, importance).apply {
            description = desc
            group = "crm"
            enableVibration(importance >= NotificationManager.IMPORTANCE_HIGH)
        }
        // «Доставка» has its own sound (§5.1), so a driver tells it from a message without looking at the phone.
        val sound = "android.resource://${ctx.packageName}/${R.raw.delivery}".toUri()
        val attrs = android.media.AudioAttributes.Builder()
            .setUsage(android.media.AudioAttributes.USAGE_NOTIFICATION)
            .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        fun delivery(id: String, name: String, importance: Int, desc: String) = ch(id, name, importance, desc).apply { setSound(sound, attrs) }
        nm.createNotificationChannels(
            listOf(
                ch(CH_CLIENTS, "Клиенты и очередь", NotificationManager.IMPORTANCE_HIGH, "Сообщения клиентов, новые беседы в очереди, «никого нет на смене»"),
                ch(CH_TEAM, "Сотрудники", NotificationManager.IMPORTANCE_HIGH, "Личные сообщения, группы, упоминания"),
                ch(CH_CALLS, "Звонки", NotificationManager.IMPORTANCE_HIGH, "Звонит клиент, идёт разговор"),
                ch(CH_WORK, "Задачи и встречи", NotificationManager.IMPORTANCE_DEFAULT, "Задачи, напоминания о встречах, документы на подпись"),
                delivery(CH_DELIVERY, "Доставка", NotificationManager.IMPORTANCE_DEFAULT, "Заявки доставки: назначили, изменили, сообщения в чате заявки"),
                delivery(CH_DELIVERY_ALERT, "Доставка — важное", NotificationManager.IMPORTANCE_HIGH, "Пинг диспетчера, «Не удалось», срок прошёл, срочная заявка, водитель снят"),
                ch(CH_SERVICE, "Служебные", NotificationManager.IMPORTANCE_DEFAULT, "Проверка уведомлений, обновления приложения"),
            )
        )
    }

    /** `alert` counts only for `delivery` (§5.1); an unknown type goes with the messages of the team, as `dm`. */
    fun channelOf(type: String, alert: Boolean = false): String = when (type) {
        "delivery" -> if (alert) CH_DELIVERY_ALERT else CH_DELIVERY
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
        val b = NotificationCompat.Builder(ctx, channelOf(p.type, p.alert))
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
            .setPriority(
                if (p.type in setOf("tasks", "calendar", "policy", "test") || (p.type == "delivery" && !p.alert)) NotificationCompat.PRIORITY_DEFAULT
                else NotificationCompat.PRIORITY_HIGH
            )
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
