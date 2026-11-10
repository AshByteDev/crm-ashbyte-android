package ru.ashcrm.app.push

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
)
