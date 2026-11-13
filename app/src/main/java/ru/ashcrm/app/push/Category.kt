package ru.ashcrm.app.push

import androidx.annotation.DrawableRes
import ru.ashcrm.app.R

/** Where a notification comes from (docs/ANDROID_CONTRACT.md §5.2): its icon, color and group of the summary. */
enum class Category(
    val key: String,
    @param:DrawableRes val icon: Int,
    @param:DrawableRes val large: Int,
    val color: Int,
    val kind: Kind,
) {
    CallMissed("call_missed", R.drawable.ic_cat_call_missed, R.drawable.notify_call_missed, 0xFFD93B30.toInt(), Kind.Call),
    CallIncoming("call_incoming", R.drawable.ic_cat_call_incoming, R.drawable.notify_call_incoming, 0xFF2EAD5B.toInt(), Kind.Call),
    Queue("queue", R.drawable.ic_cat_queue, R.drawable.notify_queue, 0xFF3390EC.toInt(), Kind.Client),
    ClientMessage("client_message", R.drawable.ic_cat_client_message, R.drawable.notify_client_message, 0xFF3390EC.toInt(), Kind.Client),
    ColleagueDirect("colleague_direct", R.drawable.ic_cat_colleague_direct, R.drawable.notify_colleague_direct, 0xFF8E5CD9.toInt(), Kind.Colleague),
    GroupMessage("group_message", R.drawable.ic_cat_group_message, R.drawable.notify_group_message, 0xFF8E5CD9.toInt(), Kind.Colleague),
    Mention("mention", R.drawable.ic_cat_mention, R.drawable.notify_mention, 0xFF8E5CD9.toInt(), Kind.Colleague),
    ConversationAssigned("conversation_assigned", R.drawable.ic_cat_conversation_assigned, R.drawable.notify_conversation_assigned, 0xFF0E8F86.toInt(), Kind.Other),
    Task("task", R.drawable.ic_cat_task, R.drawable.notify_task, 0xFF0E8F86.toInt(), Kind.Other),
    Calendar("calendar", R.drawable.ic_cat_calendar, R.drawable.notify_calendar, 0xFF0E8F86.toInt(), Kind.Other),
    Policy("policy", R.drawable.ic_cat_policy, R.drawable.notify_policy, 0xFF0E8F86.toInt(), Kind.Other),
    Delivery("delivery", R.drawable.ic_cat_delivery, R.drawable.notify_delivery, 0xFF0E8F86.toInt(), Kind.Other),
    Alert("alert", R.drawable.ic_cat_alert, R.drawable.notify_alert, 0xFFD93B30.toInt(), Kind.Other);

    enum class Kind { Call, Client, Colleague, Other }

    companion object {
        /** No category or an unknown one — null: the notification looks as before 1.6. */
        fun of(key: String?): Category? = entries.firstOrNull { it.key == key }

        /** «5 уведомлений: 2 звонка, 2 клиента, 1 коллега»; the uncounted ones — «ещё N». */
        fun summary(categories: List<Category?>): String {
            val n = categories.size
            val total = "$n ${plural(n, "уведомление", "уведомления", "уведомлений")}"
            val by = categories.groupingBy { it?.kind ?: Kind.Other }.eachCount()
            val parts = buildList {
                by[Kind.Call]?.let { add("$it ${plural(it, "звонок", "звонка", "звонков")}") }
                by[Kind.Client]?.let { add("$it ${plural(it, "клиент", "клиента", "клиентов")}") }
                by[Kind.Colleague]?.let { add("$it ${plural(it, "коллега", "коллеги", "коллег")}") }
            }
            if (parts.isEmpty()) return total
            val rest = by[Kind.Other]?.let { listOf("ещё $it") }.orEmpty()
            return "$total: ${(parts + rest).joinToString(", ")}"
        }

        fun plural(n: Int, one: String, few: String, many: String): String {
            val d = n % 10
            val dd = n % 100
            return when {
                d == 1 && dd != 11 -> one
                d in 2..4 && dd !in 12..14 -> few
                else -> many
            }
        }
    }
}
