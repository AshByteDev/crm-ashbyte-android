package ru.ashcrm.app.push

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** What a push carries after decryption — the same as a web push of the CRM (docs/ANDROID_CONTRACT.md §5.1). */
@Serializable
data class Payload(
    val type: String = "",
    val title: String = "",
    val body: String = "",
    val url: String = "",
    val tag: String = "",
    val clear: List<String> = emptyList(),
    /** Since 1.5: a notice to see at once; only `delivery` has it now (§5.1). */
    val alert: Boolean = false,
    /** Since 1.6: where the event comes from — the icon and color (§5.2). */
    val category: String = "",
    /** Since 1.7: the event is a phone call of this kind — a handset before the text (§5.2.1); empty — not a call. */
    @SerialName("call_kind") val callKind: String = "",
    /** Since 1.8: no sound and no vibration — the channel «Доставка — без звука» (§5.1). */
    val silent: Boolean = false,
    /** Since 1.8: «Уточнить координаты» — the id of the request; the app answers by itself (§7.5). */
    val locate: String = "",
)
