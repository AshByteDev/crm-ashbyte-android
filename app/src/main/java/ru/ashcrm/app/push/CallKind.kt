package ru.ashcrm.app.push

import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan

/**
 * The kind of a phone call the notification is about (docs/ANDROID_CONTRACT.md §5.2.1): a handset of its color goes
 * before the text. The text of a notification reaches the system only with parcelable spans — an ImageSpan with
 * the lucide drawable is dropped — so the handset is a symbol colored by the kind, as §5.2.1 allows.
 */
enum class CallKind(val key: String, val color: Int) {
    In("in", 0xFF2EAD5B.toInt()),
    Out("out", 0xFF3390EC.toInt()),
    Missed("missed", 0xFFD93B30.toInt()),
    Dropped("dropped", 0xFF8A8F98.toInt()),
    Spam("spam", 0xFFF97316.toInt()),
    Ringing("ringing", 0xFF2EAD5B.toInt()),
    Unknown("", 0xFF8A8F98.toInt());

    companion object {
        const val HANDSET = "✆"

        /** No `call_kind` — not a call (null); an unknown one — a gray handset. */
        fun of(key: String?): CallKind? =
            if (key.isNullOrEmpty()) null else entries.firstOrNull { it.key == key && it != Unknown } ?: Unknown

        /** «✆ 2:35 · 08. Ремзона · на смене: …» with the handset in the color of the kind; not a call — the body as is. */
        fun text(kind: CallKind?, body: String): CharSequence {
            if (kind == null) return body
            return SpannableStringBuilder().apply {
                append(HANDSET, ForegroundColorSpan(kind.color), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                if (body.isNotEmpty()) append(' ').append(body)
            }
        }
    }
}
