package ru.ashcrm.app.data

import java.net.URI

/**
 * A QR code of the login (docs/ANDROID_CONTRACT.md §3.4): `<CRM>/qr#in.<token>` or `<CRM>/qr#out.<token>`, the token —
 * base64url, 43 characters. Anything else is not ours. The token and the address are never written to the log.
 */
data class QrLogin(val portal: String, val url: String) {
    val host: String get() = URI(portal).host

    companion object {
        private val FRAGMENT = Regex("^(in|out)\\.[A-Za-z0-9_-]{43}$")

        fun parse(text: String): QrLogin? {
            val uri = runCatching { URI(text.trim()) }.getOrNull() ?: return null
            if (uri.path != "/qr" || uri.rawQuery != null || uri.rawUserInfo != null) return null
            val fragment = uri.rawFragment ?: return null
            if (!FRAGMENT.matches(fragment)) return null
            val port = if (uri.port == -1) "" else ":${uri.port}"
            // The same rules as a typed address (§1): http:// only for the local network.
            val portal = Portal.normalize("${uri.scheme}://${uri.host}$port") ?: return null
            return QrLogin(portal, "$portal/qr#$fragment")
        }
    }
}
