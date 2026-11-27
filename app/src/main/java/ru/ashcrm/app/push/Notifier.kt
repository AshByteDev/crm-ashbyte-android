package ru.ashcrm.app.push

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationChannelGroup
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.graphics.Typeface
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
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
    /** Since 1.11: reminders of personal notes, muted apart from «Задачи и встречи». */
    const val CH_NOTES = "notes"
    const val CH_DELIVERY = "delivery"
    const val CH_DELIVERY_ALERT = "delivery_alert"
    /** Since 1.8: «Уточнить координаты» and any other `silent` push — no sound, no vibration (§5.1, §7.5). */
    const val CH_DELIVERY_SILENT = "delivery_silent"

    const val EXTRA_PATH = "path"
    const val EXTRA_TAG = "tag"
    const val ACTION_OPEN = "ru.ashcrm.app.OPEN"
    private const val ID = 1

    /** All cards of the CRM are one group; its summary counts them by category (§5.2). */
    private const val GROUP = "crm"
    private const val SUMMARY = "summary"
    private const val EXTRA_CATEGORY = "ru.ashcrm.app.category"
    private const val SUMMARY_ROWS = 7

    private val _received = MutableSharedFlow<Payload>(extraBufferCapacity = 8)
    /** Every push that came: the settings screen waits for its test here. */
    val received: SharedFlow<Payload> = _received

    /** Keys the CRM can mark read by «Прочитано» (§5.1); the test card and unknown ones get no button. */
    private val readable = listOf("chat-", "task-", "event-", "channel-", "queue-", "alert-", "missedcall-", "delivery-", "note-")

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
                ch(CH_NOTES, "Заметки", NotificationManager.IMPORTANCE_DEFAULT, "Напоминания по личным заметкам"),
                delivery(CH_DELIVERY, "Доставка", NotificationManager.IMPORTANCE_DEFAULT, "Заявки доставки: назначили, изменили, сообщения в чате заявки"),
                delivery(CH_DELIVERY_ALERT, "Доставка — важное", NotificationManager.IMPORTANCE_HIGH, "Пинг диспетчера, «Не удалось», срок прошёл, срочная заявка, водитель снят"),
                ch(CH_DELIVERY_SILENT, "Доставка — без звука", NotificationManager.IMPORTANCE_LOW, "«Уточнить координаты»: место ушло само, без звука и вибрации"),
                ch(CH_SERVICE, "Служебные", NotificationManager.IMPORTANCE_DEFAULT, "Проверка уведомлений, обновления приложения"),
            )
        )
    }

    /**
     * `silent` — any type goes to «Доставка — без звука» (§5.1, 1.8). `alert` counts only for `delivery` (§5.1);
     * an unknown type goes with the messages of the team, as `dm`.
     */
    fun channelOf(type: String, alert: Boolean = false, silent: Boolean = false): String = when {
        silent -> CH_DELIVERY_SILENT
        type == "delivery" && alert -> CH_DELIVERY_ALERT
        type == "delivery" -> CH_DELIVERY
        type in setOf("mine", "queue", "alerts") -> CH_CLIENTS
        type == "call" -> CH_CALLS
        type in setOf("tasks", "calendar", "policy") -> CH_WORK
        type == "notes" -> CH_NOTES
        type == "test" -> CH_SERVICE
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
            Intent(ctx, MainActivity::class.java).setAction(ACTION_OPEN).putExtra(EXTRA_PATH, p.url).putExtra(EXTRA_TAG, tag)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val cat = Category.of(p.category)
        val text = CallKind.text(CallKind.of(p.callKind), p.body)
        val quiet = silent || p.silent
        val b = NotificationCompat.Builder(ctx, channelOf(p.type, p.alert, quiet))
            .setSmallIcon(cat?.icon ?: R.drawable.ic_notify)
            .setColor(cat?.color ?: ContextCompat.getColor(ctx, R.color.accent))
            .setContentTitle(p.title.ifEmpty { "AshCRM" })
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setAutoCancel(true)
            .setShowWhen(true)
            .setSilent(quiet)
            .setCategory(
                when {
                    quiet -> NotificationCompat.CATEGORY_STATUS
                    p.type == "call" -> NotificationCompat.CATEGORY_CALL
                    p.type in setOf("tasks", "calendar", "policy", "notes") -> NotificationCompat.CATEGORY_REMINDER
                    p.type == "test" -> NotificationCompat.CATEGORY_STATUS
                    else -> NotificationCompat.CATEGORY_MESSAGE
                }
            )
            .setPriority(
                when {
                    quiet -> NotificationCompat.PRIORITY_LOW
                    p.type in setOf("tasks", "calendar", "policy", "notes", "test") || (p.type == "delivery" && !p.alert) -> NotificationCompat.PRIORITY_DEFAULT
                    else -> NotificationCompat.PRIORITY_HIGH
                }
            )
        cat?.let { b.setLargeIcon(BitmapFactory.decodeResource(ctx.resources, it.large)) }
        // Notices of the app itself (a test, an update) stay apart from the cards of the CRM.
        val grouped = p.type != "test"
        if (grouped) {
            b.setGroup(GROUP)
            b.addExtras(Bundle().apply { putString(EXTRA_CATEGORY, p.category) })
            b.setDeleteIntent(
                PendingIntent.getBroadcast(
                    ctx, tag.hashCode(),
                    Intent(ctx, ActionReceiver::class.java).setAction(ActionReceiver.ACTION_GONE).putExtra(ActionReceiver.EXTRA_KEY, tag),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                )
            )
        }
        if (readable.any { tag.startsWith(it) }) {
            val read = PendingIntent.getBroadcast(
                ctx, tag.hashCode(),
                Intent(ctx, ActionReceiver::class.java).setAction(ActionReceiver.ACTION_READ).putExtra(ActionReceiver.EXTRA_KEY, tag),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            b.addAction(0, "Прочитано", read)
        }
        val n = b.build()
        runCatching { NotificationManagerCompat.from(ctx).notify(tag, ID, n) }
        if (grouped) runCatching { summary(ctx, added = tag to n) }
    }

    fun clear(ctx: Context, keys: List<String>) {
        val nm = NotificationManagerCompat.from(ctx)
        keys.forEach { nm.cancel(it, ID) }
        runCatching { summary(ctx, removed = keys.toSet()) }
    }

    /** A card went away by itself (swiped or tapped): the summary forgets it. */
    fun gone(ctx: Context, key: String) {
        runCatching { summary(ctx, removed = setOf(key)) }
    }

    /**
     * The summary of the group: «5 уведомлений: 2 звонка, …» and a row per card with the dot of its category.
     * The list of active notifications lags behind notify/cancel, so the card just posted or removed is passed in.
     * Cancelling a summary cancels its children as well: it goes only when no card is left.
     */
    private fun summary(ctx: Context, added: Pair<String, Notification>? = null, removed: Set<String> = emptySet()) {
        val all = ctx.getSystemService(NotificationManager::class.java).activeNotifications
        val hasSummary = all.any { it.id == ID && it.tag == SUMMARY }
        val cards = (all
            .filter { it.id == ID && it.tag != SUMMARY && it.notification.group == GROUP && it.tag !in removed && it.tag != added?.first }
            .sortedByDescending { it.postTime }
            .map { it.notification }
                ).let { rest -> listOfNotNull(added?.second) + rest }
        val nm = NotificationManagerCompat.from(ctx)
        if (cards.isEmpty()) {
            if (hasSummary) nm.cancel(SUMMARY, ID)
            return
        }
        if (cards.size < 2 && !hasSummary) return
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED &&
            android.os.Build.VERSION.SDK_INT >= 33
        ) return
        val cats = cards.map { Category.of(it.extras.getString(EXTRA_CATEGORY)) }
        val text = Category.summary(cats)
        val style = NotificationCompat.InboxStyle().setBigContentTitle(text)
        cards.zip(cats).take(SUMMARY_ROWS).forEach { (n, c) -> style.addLine(row(ctx, n, c)) }
        if (cards.size > SUMMARY_ROWS) style.setSummaryText("ещё ${cards.size - SUMMARY_ROWS}")
        val open = PendingIntent.getActivity(
            ctx, SUMMARY.hashCode(),
            Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        nm.notify(
            SUMMARY, ID,
            NotificationCompat.Builder(ctx, cards.first().channelId ?: CH_TEAM)
                .setSmallIcon(R.drawable.ic_notify)
                .setColor(ContextCompat.getColor(ctx, R.color.accent))
                .setContentTitle(text)
                .setContentText(row(ctx, cards.first(), cats.first()))
                // Android 12+ draws a collapsed group from the cards itself; the count by category goes to its header.
                .setSubText(text.substringAfter(": ", ""))
                .setStyle(style)
                .setGroup(GROUP)
                .setGroupSummary(true)
                .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_CHILDREN)
                .setSilent(true)
                .setContentIntent(open)
                .build(),
        )
    }

    /** «● Анна Лебедева  15:04 · ждал 12 с»: the dot in the color of the category, the title in bold. */
    private fun row(ctx: Context, n: Notification, c: Category?): CharSequence {
        val title = n.extras.getCharSequence(NotificationCompat.EXTRA_TITLE)?.toString().orEmpty()
        // The body keeps its spans: the handset of a call stays in its color (§5.2.1).
        val body = n.extras.getCharSequence(NotificationCompat.EXTRA_TEXT) ?: ""
        return SpannableStringBuilder().apply {
            append("● ", ForegroundColorSpan(c?.color ?: ContextCompat.getColor(ctx, R.color.accent)), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            append(title, StyleSpan(Typeface.BOLD), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            if (body.isNotEmpty()) append("  ").append(body)
        }
    }

    /** A notice of the app itself (an update is ready, the device was disconnected). */
    fun service(ctx: Context, tag: String, title: String, body: String) =
        show(ctx, Payload(type = "test", title = title, body = body, tag = tag), silent = false)
}
